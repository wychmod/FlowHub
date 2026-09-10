package com.example.flowhub.export.mq;

import com.example.flowhub.common.web.trace.MdcScope;
import com.example.flowhub.common.web.trace.TraceIdSupport;
import com.example.flowhub.common.web.util.ExceptionUtils;
import com.example.flowhub.export.service.ExportExecutionService;
import com.example.flowhub.export.service.ExportJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 导出任务消费者：手动确认 + 数据库条件抢占，把至少一次投递收敛为最多一次有效执行。
 * <p>
 * 职责分工：RabbitMQ 负责交付，MySQL Job 状态负责裁决执行权（claimPendingJob），
 * Attempt 负责保存执行历史；三者协作靠本类的调用顺序——Ack 永远在处理路径完成之后。
 */
@Component
public class ExportJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportJobConsumer.class);

    private final ExportJobService exportJobService;
    private final ExportExecutionService exportExecutionService;
    private final ObjectMapper objectMapper;

    public ExportJobConsumer(ExportJobService exportJobService,
                             ExportExecutionService exportExecutionService,
                             ObjectMapper objectMapper) {
        this.exportJobService = exportJobService;
        this.exportExecutionService = exportExecutionService;
        this.objectMapper = objectMapper;
    }

    /** 消费 EXPORT_JOB_CREATED：trace 恢复 → 契约校验 → 条件抢占 → 执行 → Ack（控制流见笔记 4.8）。 */
    @RabbitListener(queues = RabbitConfig.JOB_QUEUE, ackMode = "MANUAL", concurrency = "2")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = deliveryTagOf(message);
        String traceId = resolveTraceId(message);
        try (MdcScope ignored = MdcScope.withTraceId(traceId)) {
            ExportJobMessage body = parseBody(message);
            if (body == null || !body.isSupported() || body.jobId() == null) {
                // 契约不支持（schema 未知/正文非法/字段缺失）：反复投递不会让未知协议变有效，转 DLQ 排查
                log.warn("export_message_rejected delivery_tag={} trace_id={}", deliveryTag, traceId);
                channel.basicReject(deliveryTag, false);
                return;
            }
            log.info("export_consume job_id={} message_id={} delivery_tag={} trace_id={}",
                    body.jobId(), body.messageId(), deliveryTag, traceId);
            if (!exportJobService.claimPendingJob(body.jobId())) {
                // 重复投递/已被抢占/达尝试上限/任务不存在：无副作用，直接 Ack 收敛
                channel.basicAck(deliveryTag, false);
                return;
            }
            // 业务异常在执行服务内部收敛为 FAILED 后正常返回，此处 Ack 使失败成为可查询事实
            exportExecutionService.execute(body.jobId());
            channel.basicAck(deliveryTag, false);
        } catch (IOException | RuntimeException ex) {
            // 抢占事务失败或 Channel 已关（Ack/Reject 自身失败）：不能假装已确认，穿出保留重投机会
            log.warn("export_consume_failed delivery_tag={} reason={} trace_id={}",
                    deliveryTag, ExceptionUtils.messageOrTypeName(ex), traceId);
            throw ex;
        }
    }

    /** trace 恢复：Header 合法则延续链路，非法/缺失则新建（诊断信息不是执行权，不阻塞导出）。 */
    private static String resolveTraceId(Message message) {
        Object header = message.getMessageProperties().getHeader(TraceIdSupport.HEADER_NAME);
        if (header instanceof String value && TraceIdSupport.isValid(value)) {
            return value;
        }
        return TraceIdSupport.newTraceId();
    }

    /** 反序列化消息契约；非法正文返回 null，与契约不支持同走 Reject 路径。 */
    private ExportJobMessage parseBody(Message message) {
        try {
            return objectMapper.readValue(message.getBody(), ExportJobMessage.class);
        } catch (IOException ex) {
            return null;
        }
    }

    /** 取消息的投递标签（Channel 内单调递增，手动 Ack/Reject 靠它定位本次投递）。 */
    private static long deliveryTagOf(Message message) {
        return message.getMessageProperties().getDeliveryTag();
    }
}
