package com.example.exportflow.export.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.exportflow.export.entity.OutboxEventEntity;
import com.example.exportflow.order.mapper.TestOrderDataSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Outbox 分发器集成验证：Confirm ACK 且无 Returned 才回填 published_at，
 * 失败/NACK/退回保留事件且 Job 不改状态，消息契约（字段/持久化/X-Trace-Id）与批量隔离。
 * <p>真实 OutboxEventMapper + H2 执行真 SQL；@MockBean RabbitTemplate 全程不连真实 Broker；
 * 调度由测试 yml 静默，各用例直接调用 dispatchPendingEvents()。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OutboxDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxDispatcher outboxDispatcher;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void seedDeterministicOrders() {
        TestOrderDataSeeder.seed(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM export_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    // ==================== 发布确认闭环 ====================

    @Test
    void ackWithoutReturnedMarksPublished() throws Exception {
        int outboxId = createJob("key-ack-1");
        stubAck();
        outboxDispatcher.dispatchPendingEvents();
        assertThat(publishedAtOf(outboxId)).isNotNull();
    }

    @Test
    void sendFailureKeepsEventUnpublishedAndJobPending(CapturedOutput output) throws Exception {
        int outboxId = createJob("key-send-fail");
        doThrow(new RuntimeException("broker down")).when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        outboxDispatcher.dispatchPendingEvents();

        // 事件保留、任务保持 PENDING：发布失败是投递问题，不是业务失败
        assertThat(publishedAtOf(outboxId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM export_jobs", String.class)).isEqualTo("PENDING");
        assertThat(output).contains("outbox_publish_deferred").contains("outbox_event_id=" + outboxId);
    }

    @Test
    void nackKeepsEventUnpublished(CapturedOutput output) throws Exception {
        int outboxId = createJob("key-nack");
        doAnswer(invocation -> {
            invocation.getArgument(3, CorrelationData.class)
                    .getFuture().complete(new CorrelationData.Confirm(false, "internal-error"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        outboxDispatcher.dispatchPendingEvents();

        assertThat(publishedAtOf(outboxId)).isNull();
        assertThat(output).contains("outbox_publish_deferred").contains("nack:internal-error");
    }

    @Test
    void returnedKeepsEventUnpublished(CapturedOutput output) throws Exception {
        int outboxId = createJob("key-returned");
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3, CorrelationData.class);
            // 不可路由退回时 Confirm 依然 ACK——只看 ACK 会误标，必须同时看 Returned
            correlation.setReturned(new ReturnedMessage(invocation.getArgument(2, Message.class),
                    312, "NO_ROUTE", RabbitConfig.JOB_EXCHANGE, RabbitConfig.JOB_ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        outboxDispatcher.dispatchPendingEvents();

        assertThat(publishedAtOf(outboxId)).isNull();
        assertThat(output).contains("outbox_publish_deferred").contains("returned");
    }

    // ==================== 消息契约 ====================

    @Test
    void messageJsonAndTraceHeaderContract() throws Exception {
        int outboxId = createJob("key-contract", "outbox-trace-header-01");
        stubAck();
        outboxDispatcher.dispatchPendingEvents();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).send(eq(RabbitConfig.JOB_EXCHANGE), eq(RabbitConfig.JOB_ROUTING_KEY),
                messageCaptor.capture(), correlationCaptor.capture());

        MessageProperties properties = messageCaptor.getValue().getMessageProperties();
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        // getHeader 是泛型方法，显式赋值避免 assertThat 重载歧义
        Object traceHeader = properties.getHeader("X-Trace-Id");
        assertThat(traceHeader)
                .isEqualTo(jdbcTemplate.queryForObject("SELECT trace_id FROM outbox_events WHERE id = " + outboxId, String.class));
        assertThat(correlationCaptor.getValue().getId()).isEqualTo("outbox-" + outboxId);

        // 正文为最小契约：schema/event 版本、job_id 取库内聚合 ID、message_id 由 outbox id 稳定派生
        JsonNode body = objectMapper.readTree(messageCaptor.getValue().getBody());
        assertThat(body.get("schema_version").asInt()).isEqualTo(ExportJobMessage.SCHEMA_VERSION);
        assertThat(body.get("event_version").asInt()).isEqualTo(ExportJobMessage.EVENT_VERSION);
        assertThat(body.get("job_id").asLong())
                .isEqualTo(jdbcTemplate.queryForObject("SELECT aggregate_id FROM outbox_events WHERE id = " + outboxId, Long.class));
        assertThat(body.get("message_id").asText()).isEqualTo(ExportJobMessage.messageIdFor(outboxId));
    }

    @Test
    void emptyTraceIdSkipsHeader() throws Exception {
        int outboxId = insertOutboxEvent(999, "");
        stubAck();
        outboxDispatcher.dispatchPendingEvents();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(RabbitConfig.JOB_EXCHANGE), eq(RabbitConfig.JOB_ROUTING_KEY),
                messageCaptor.capture(), any(CorrelationData.class));
        Object traceHeader = messageCaptor.getValue().getMessageProperties().getHeader("X-Trace-Id");
        assertThat(traceHeader).isNull();
        // trace 缺失不影响投递
        assertThat(publishedAtOf(outboxId)).isNotNull();
    }

    // ==================== 批量隔离 ====================

    @Test
    void singleFailureDoesNotBlockBatch() throws Exception {
        int failedId = insertOutboxEvent(888, "trace-888");
        int publishedId = insertOutboxEvent(999, "trace-999");
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2, Message.class);
            long jobId = objectMapper.readTree(message.getBody()).get("job_id").asLong();
            if (jobId == 888) {
                throw new RuntimeException("first down");
            }
            invocation.getArgument(3, CorrelationData.class)
                    .getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        outboxDispatcher.dispatchPendingEvents();

        assertThat(publishedAtOf(failedId)).isNull();
        assertThat(publishedAtOf(publishedId)).isNotNull();
    }

    // ==================== 桩与数据准备 ====================

    /** ACK 且无退回的公共桩：在 send 调用内完成 CorrelationData 的 Confirm future。 */
    private void stubAck() {
        doAnswer(invocation -> {
            invocation.getArgument(3, CorrelationData.class)
                    .getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq(RabbitConfig.JOB_EXCHANGE), eq(RabbitConfig.JOB_ROUTING_KEY),
                any(Message.class), any(CorrelationData.class));
    }

    /** 走真实创建链路造一条未发布事件，返回 outbox 主键（INSERT 不回填，按单行查询取回）。 */
    private int createJob(String idempotencyKey) throws Exception {
        return createJob(idempotencyKey, null);
    }

    private int createJob(String idempotencyKey, String traceId) throws Exception {
        var request = post("/api/v1/export-jobs")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"selection":{"mode":"SELECTED_IDS","order_ids":[1]},"columns":["order_no"]}
                        """);
        if (traceId != null) {
            request.header("X-Trace-Id", traceId);
        }
        mockMvc.perform(request).andExpect(status().isAccepted());
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_events", Integer.class);
    }

    /** 直接插入 outbox 事件（绕过创建链路，用于批量/空 trace 场景），返回自增主键。 */
    private int insertOutboxEvent(long aggregateId, String traceId) {
        jdbcTemplate.update("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, trace_id, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, OutboxEventEntity.AGGREGATE_TYPE_EXPORT_JOB, aggregateId,
                OutboxEventEntity.EVENT_TYPE_EXPORT_JOB_CREATED,
                "{\"job_id\":" + aggregateId + "}", traceId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_events WHERE aggregate_id = " + aggregateId, Integer.class);
    }

    private String publishedAtOf(long outboxId) {
        return jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_events WHERE id = " + outboxId, String.class);
    }
}
