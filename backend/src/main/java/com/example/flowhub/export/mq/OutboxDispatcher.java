package com.example.flowhub.export.mq;

import com.example.flowhub.common.web.trace.TraceIdSupport;
import com.example.flowhub.common.web.util.ExceptionUtils;
import com.example.flowhub.export.entity.OutboxEventEntity;
import com.example.flowhub.export.mapper.OutboxEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 分发器：扫描未发布事件发 RabbitMQ，拿到 Confirm ACK 且无 Returned 才标记已发布。
 * <p>
 * 失败/NACK/退回/超时一律保留事件（published_at 保持 NULL）下轮补发——Outbox 没有 FAILED，Job 不改状态。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    /** 单轮扫描上限，防积压时一次性全量发送（单线程调度器最坏一轮约 limit × confirm 超时）。 */
    private static final int BATCH_LIMIT = 100;

    private final OutboxEventMapper outboxEventMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final long confirmTimeoutMs;

    public OutboxDispatcher(OutboxEventMapper outboxEventMapper,
                            RabbitTemplate rabbitTemplate,
                            ObjectMapper objectMapper,
                            @Value("${export.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    /** 定时扫描并逐条发布；初始延迟与间隔共用同一配置，测试将其调大即可整体禁用调度。 */
    @Scheduled(fixedDelayString = "${export.outbox.dispatch-delay-ms:5000}",
               initialDelayString = "${export.outbox.dispatch-delay-ms:5000}")
    public void dispatchPendingEvents() {
        List<OutboxEventEntity> events = outboxEventMapper.findUnpublished(
                OutboxEventEntity.AGGREGATE_TYPE_EXPORT_JOB,
                OutboxEventEntity.EVENT_TYPE_EXPORT_JOB_CREATED, BATCH_LIMIT);
        for (OutboxEventEntity event : events) {
            try {
                publishAfterConfirm(event);
            } catch (InterruptedException ex) {
                // 恢复中断位并停止本轮，尊重应用 shutdown
                Thread.currentThread().interrupt();
                logDeferred(event, "interrupted");
                break;
            } catch (Exception ex) {
                // 单条失败不阻断本轮其余事件；事件保留待下轮补发
                logDeferred(event, ExceptionUtils.messageOrTypeName(ex));
            }
        }
    }

    /**
     * 发布单条事件并等待 Broker 证据：send 仅表示交给客户端库，
     * 是否被接收（Confirm）与是否成功路由（无 Returned）需分别取证，两者齐备才回填 published_at。
     */
    private void publishAfterConfirm(OutboxEventEntity event) throws InterruptedException, JsonProcessingException {
        ExportJobMessage message = messageFor(event);
        CorrelationData correlation = new CorrelationData("outbox-" + event.id());
        rabbitTemplate.send(RabbitConfig.JOB_EXCHANGE, RabbitConfig.JOB_ROUTING_KEY,
                toAmqpMessage(event, message), correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // 回执丢失时无法断定结果，保守保留事件（可能重复发布，由消费端条件抢占兜住）
            logDeferred(event, "timeout");
            return;
        } catch (ExecutionException ex) {
            logDeferred(event, "confirm-error");
            return;
        }
        if (!confirm.isAck()) {
            logDeferred(event, "nack:" + confirm.getReason());
            return;
        }
        if (correlation.getReturned() != null) {
            // ACK 只证明 Broker 接收；被退回说明没有路由到任何队列，不能标记
            logDeferred(event, "returned(不可路由)");
            return;
        }
        int updated = outboxEventMapper.markPublished(event.id(), LocalDateTime.now());
        if (updated > 0) {
            log.info("outbox_published outbox_event_id={} job_id={} message_id={} trace_id={}",
                    event.id(), event.aggregateId(), message.messageId(), event.traceId());
        }
    }

    /** 构造消息契约：只带执行定位信息，执行数据以库内 Job 为准。 */
    private static ExportJobMessage messageFor(OutboxEventEntity event) {
        return new ExportJobMessage(ExportJobMessage.SCHEMA_VERSION, ExportJobMessage.messageIdFor(event.id()),
                event.aggregateId(), ExportJobMessage.EVENT_VERSION);
    }

    /** 消息属性：JSON 正文 + 持久化投递 + 继承创建请求的 trace_id（仅诊断用途）。 */
    private Message toAmqpMessage(OutboxEventEntity event, ExportJobMessage message) throws JsonProcessingException {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        String traceId = event.traceId();
        if (traceId != null && !traceId.isBlank()) {
            properties.setHeader(TraceIdSupport.HEADER_NAME, traceId);
        }
        return new Message(objectMapper.writeValueAsBytes(message), properties);
    }

    private void logDeferred(OutboxEventEntity event, String reason) {
        log.warn("outbox_publish_deferred outbox_event_id={} job_id={} message_id={} trace_id={} reason={}",
                event.id(), event.aggregateId(), ExportJobMessage.messageIdFor(event.id()), event.traceId(), reason);
    }
}
