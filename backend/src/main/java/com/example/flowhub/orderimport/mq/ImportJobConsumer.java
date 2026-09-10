package com.example.flowhub.orderimport.mq;

import com.example.flowhub.common.web.trace.MdcScope;
import com.example.flowhub.common.web.trace.TraceIdSupport;
import com.example.flowhub.common.web.util.ExceptionUtils;
import com.example.flowhub.orderimport.service.ImportExecutionService;
import com.example.flowhub.orderimport.service.ImportJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 导入任务消费者：手动确认 + 数据库条件抢占，把至少一次投递收敛为最多一次有效执行。
 * <p>
 * 与导出消费端同一套协作模式：RabbitMQ 交付、MySQL Job 状态裁决执行权（claimPendingJob）、
 * Attempt 保存执行历史；Ack 永远在处理路径完成之后。
 */
@Component
public class ImportJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImportJobConsumer.class);

    private final ImportJobService importJobService;
    private final ImportExecutionService importExecutionService;
    private final ObjectMapper objectMapper;

    public ImportJobConsumer(ImportJobService importJobService,
                             ImportExecutionService importExecutionService,
                             ObjectMapper objectMapper) {
        this.importJobService = importJobService;
        this.importExecutionService = importExecutionService;
        this.objectMapper = objectMapper;
    }

    /** 消费 IMPORT_JOB_CREATED：trace 恢复 → 契约校验 → 条件抢占 → 执行 → Ack。 */
    @RabbitListener(queues = ImportRabbitConfig.JOB_QUEUE, ackMode = "MANUAL", concurrency = "2")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = deliveryTagOf(message);
        String traceId = resolveTraceId(message);
        try (MdcScope ignored = MdcScope.withTraceId(traceId)) {
            ImportJobMessage body = parseBody(message);
            if (body == null || !body.isSupported() || body.jobId() == null) {
                // 契约不支持（schema 未知/正文非法/字段缺失）：反复投递不会让未知协议变有效，转 DLQ 排查
                log.warn("import_message_rejected delivery_tag={} trace_id={}", deliveryTag, traceId);
                channel.basicReject(deliveryTag, false);
                return;
            }
            log.info("import_consume job_id={} message_id={} delivery_tag={} trace_id={}",
                    body.jobId(), body.messageId(), deliveryTag, traceId);
            if (!importJobService.claimPendingJob(body.jobId())) {
                // 重复投递/已被抢占/达尝试上限/任务不存在：无副作用，直接 Ack 收敛
                channel.basicAck(deliveryTag, false);
                return;
            }
            // 业务异常在执行服务内部收敛为 FAILED 后正常返回，此处 Ack 使失败成为可查询事实
            importExecutionService.execute(body.jobId());
            channel.basicAck(deliveryTag, false);
        } catch (IOException | RuntimeException ex) {
            // 抢占事务失败或 Channel 已关：不能假装已确认，穿出保留重投机会
            log.warn("import_consume_failed delivery_tag={} reason={} trace_id={}",
                    deliveryTag, ExceptionUtils.messageOrTypeName(ex), traceId);
            throw ex;
        }
    }

    /** trace 恢复：Header 合法则延续链路，非法/缺失则新建。 */
    private static String resolveTraceId(Message message) {
        Object header = message.getMessageProperties().getHeader(TraceIdSupport.HEADER_NAME);
        if (header instanceof String value && TraceIdSupport.isValid(value)) {
            return value;
        }
        return TraceIdSupport.newTraceId();
    }

    /** 反序列化消息契约；非法正文返回 null，与契约不支持同走 Reject 路径。 */
    private ImportJobMessage parseBody(Message message) {
        try {
            return objectMapper.readValue(message.getBody(), ImportJobMessage.class);
        } catch (IOException ex) {
            return null;
        }
    }

    private static long deliveryTagOf(Message message) {
        return message.getMessageProperties().getDeliveryTag();
    }
}