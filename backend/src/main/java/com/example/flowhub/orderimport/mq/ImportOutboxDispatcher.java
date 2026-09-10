package com.example.flowhub.orderimport.mq;

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
 * 导入 Outbox 分发器：扫描 IMPORT_JOB 未发布事件发 RabbitMQ，拿到 Confirm ACK 且无 Returned 才标记已发布。
 * <p>
 * 与导出 {@code OutboxDispatcher} 对称但垂直自治（只捞 IMPORT_JOB 聚合类型、发到 import.* 拓扑），
 * 复用同一张 outbox 表与可靠投递纪律：失败/NACK/退回/超时一律保留事件下轮补发，Job 不改状态。
 */
@Component
public class ImportOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ImportOutboxDispatcher.class);
    /** 单轮扫描上限，防积压时一次性全量发送。 */
    private static final int BATCH_LIMIT = 100;

    private final OutboxEventMapper outboxEventMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final long confirmTimeoutMs;

    public ImportOutboxDispatcher(OutboxEventMapper outboxEventMapper,
                                  RabbitTemplate rabbitTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${import.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    /** 定时扫描并逐条发布；初始延迟与间隔共用同一配置，测试置大即可整体禁用调度。 */
    @Scheduled(fixedDelayString = "${import.outbox.dispatch-delay-ms:5000}",
               initialDelayString = "${import.outbox.dispatch-delay-ms:5000}")
    public void dispatchPendingEvents() {
        List<OutboxEventEntity> events = outboxEventMapper.findUnpublished(
                OutboxEventEntity.AGGREGATE_TYPE_IMPORT_JOB,
                OutboxEventEntity.EVENT_TYPE_IMPORT_JOB_CREATED, BATCH_LIMIT);
        for (OutboxEventEntity event : events) {
            try {
                publishAfterConfirm(event);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                logDeferred(event, "interrupted");
                break;
            } catch (Exception ex) {
                logDeferred(event, ExceptionUtils.messageOrTypeName(ex));
            }
        }
    }

    /** 发布单条事件并等待 Broker 证据：ACK 且无 Returned 才回填 published_at。 */
    private void publishAfterConfirm(OutboxEventEntity event) throws InterruptedException, JsonProcessingException {
        ImportJobMessage message = messageFor(event);
        CorrelationData correlation = new CorrelationData("import-outbox-" + event.id());
        rabbitTemplate.send(ImportRabbitConfig.JOB_EXCHANGE, ImportRabbitConfig.JOB_ROUTING_KEY,
                toAmqpMessage(event, message), correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
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
            logDeferred(event, "returned(不可路由)");
            return;
        }
        int updated = outboxEventMapper.markPublished(event.id(), LocalDateTime.now());
        if (updated > 0) {
            log.info("import_outbox_published outbox_event_id={} job_id={} message_id={} trace_id={}",
                    event.id(), event.aggregateId(), message.messageId(), event.traceId());
        }
    }

    private static ImportJobMessage messageFor(OutboxEventEntity event) {
        return new ImportJobMessage(ImportJobMessage.SCHEMA_VERSION,
                ImportJobMessage.messageIdFor(event.id()), event.aggregateId(), ImportJobMessage.EVENT_VERSION);
    }

    /** 消息属性：JSON 正文 + 持久化投递 + 继承创建请求的 trace_id。 */
    private Message toAmqpMessage(OutboxEventEntity event, ImportJobMessage message) throws JsonProcessingException {
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
        log.warn("import_outbox_publish_deferred outbox_event_id={} job_id={} message_id={} trace_id={} reason={}",
                event.id(), event.aggregateId(), ImportJobMessage.messageIdFor(event.id()), event.traceId(), reason);
    }
}