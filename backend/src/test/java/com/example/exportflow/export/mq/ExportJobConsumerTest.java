package com.example.exportflow.export.mq;

import com.example.exportflow.common.web.trace.TraceIdSupport;
import com.example.exportflow.export.mapper.ExportJobAttemptMapper;
import com.example.exportflow.export.service.ExportExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 消费端集成验证：重复投递条件抢占收敛（单 Attempt 双 Ack）、契约不支持转 DLQ、
 * 执行失败收敛 FAILED、trace Header 恢复/新建、Attempt 插入失败回滚不留孤儿 RUNNING。
 * <p>真实 Mapper + H2 执行真 SQL；Channel 以 Mockito 模拟（不连真实 Broker），
 * Consumer 逻辑直接方法调用验证；@SpyBean 执行服务/Attempt Mapper 按用例定制行为。
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class ExportJobConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ExportJobConsumer exportJobConsumer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private ExportExecutionService exportExecutionService;

    @SpyBean
    private ExportJobAttemptMapper exportJobAttemptMapper;

    private Channel channel;

    @BeforeEach
    void cleanTablesAndChannel() {
        jdbcTemplate.update("DELETE FROM export_job_attempts");
        jdbcTemplate.update("DELETE FROM export_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
        channel = mock(Channel.class);
    }

    // ==================== 重复投递收敛（P0-7 ①） ====================

    @Test
    void duplicateDeliveryAcksBothAndCreatesSingleAttempt() throws Exception {
        long jobId = insertPendingJob();
        // execute 置为 no-op：Job 停在 RUNNING，模拟「执行期间重复消息到达」
        doNothing().when(exportExecutionService).execute(anyLong());

        exportJobConsumer.onMessage(message(jobId, 1L, null), channel);
        exportJobConsumer.onMessage(message(jobId, 2L, null), channel);

        // 两条 delivery 均确认（重复消息无副作用），但只产生一次有效执行
        verify(channel).basicAck(1L, false);
        verify(channel).basicAck(2L, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        assertThat(jobStatus()).isEqualTo("RUNNING");
        assertThat(attemptCount()).isEqualTo(1);
        assertThat(attemptRows()).isEqualTo(1);
        assertThat(attemptColumn("attempt_no")).isEqualTo(1);
    }

    // ==================== 执行失败收敛 ====================

    @Test
    void executionFailureConvergesJobAndAttemptFailed(CapturedOutput output) throws Exception {
        long jobId = insertPendingJob();
        // 不定制 execute：真实执行壳失败，收敛为可查询事实后正常 Ack

        exportJobConsumer.onMessage(message(jobId, 1L, null), channel);

        verify(channel).basicAck(1L, false);
        assertThat(jobStatus()).isEqualTo("FAILED");
        assertThat(jobColumn("error_code")).isEqualTo(ExportExecutionService.ERROR_CODE_FILE_GENERATION);
        assertThat((String) jobColumn("error_message")).contains("尚未实现");
        assertThat(jobColumn("finished_at")).isNotNull();
        assertThat(attemptRows()).isEqualTo(1);
        assertThat(attemptColumn("status")).isEqualTo("FAILED");
        assertThat(attemptColumn("error_code")).isEqualTo(ExportExecutionService.ERROR_CODE_FILE_GENERATION);
        assertThat(attemptColumn("started_at")).isNotNull();
        assertThat(attemptColumn("finished_at")).isNotNull();
        assertThat(output).contains("export_execution_failed");
    }

    // ==================== 契约分流：Reject → DLQ ====================

    @Test
    void unsupportedSchemaRejectedToDlq() throws Exception {
        long jobId = insertPendingJob();
        String json = "{\"schema_version\":2,\"message_id\":\"m1\",\"job_id\":" + jobId
                + ",\"event_version\":1}";

        exportJobConsumer.onMessage(rawMessage(json, 7L, null), channel);

        // requeue=false 转入 DLQ；反复投递不会让未知协议变有效
        verify(channel).basicReject(7L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(jobStatus()).isEqualTo("PENDING");
        assertThat(attemptRows()).isZero();
    }

    @Test
    void malformedBodyRejectedToDlq() throws Exception {
        exportJobConsumer.onMessage(rawMessage("not-json", 8L, null), channel);

        verify(channel).basicReject(8L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(attemptRows()).isZero();
    }

    // ==================== trace Header 恢复 / 新建（P0-7 ②） ====================

    @Test
    void validTraceHeaderRestoredIntoMdcDuringExecution() throws Exception {
        long jobId = insertPendingJob();
        AtomicReference<String> mdcTrace = captureMdcDuringExecution();

        exportJobConsumer.onMessage(message(jobId, 1L, "trace-header-valid-001"), channel);

        assertThat(mdcTrace.get()).isEqualTo("trace-header-valid-001");
        // 作用域退出后还原，防止泄漏到监听线程的后续消息
        assertThat(MDC.get(TraceIdSupport.MDC_KEY)).isNull();
    }

    @Test
    void missingTraceHeaderCreatesNewTraceDuringExecution() throws Exception {
        long jobId = insertPendingJob();
        AtomicReference<String> mdcTrace = captureMdcDuringExecution();

        exportJobConsumer.onMessage(message(jobId, 1L, null), channel);

        assertThat(TraceIdSupport.isValid(mdcTrace.get())).isTrue();
    }

    @Test
    void invalidTraceHeaderCreatesNewTraceDuringExecution() throws Exception {
        long jobId = insertPendingJob();
        AtomicReference<String> mdcTrace = captureMdcDuringExecution();

        // 5 字符不满足 16-64 约束：非法与缺失同走新建分支
        exportJobConsumer.onMessage(message(jobId, 1L, "short"), channel);

        assertThat(TraceIdSupport.isValid(mdcTrace.get())).isTrue();
    }

    // ==================== 事务回滚：不留孤儿 RUNNING（P0-7 ③） ====================

    @Test
    void attemptInsertFailureRollsBackClaimWithoutOrphanRunning() throws Exception {
        long jobId = insertPendingJob();
        doThrow(new RuntimeException("attempt insert down"))
                .when(exportJobAttemptMapper).insertRunning(anyLong(), any(LocalDateTime.class));

        assertThatThrownBy(() -> exportJobConsumer.onMessage(message(jobId, 1L, null), channel))
                .isInstanceOf(RuntimeException.class);

        // 异常穿出不确认（保留重投机会）；抢占 UPDATE 随事务回滚，Job 仍 PENDING、无 Attempt
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        assertThat(jobStatus()).isEqualTo("PENDING");
        assertThat(attemptCount()).isEqualTo(0);
        assertThat(attemptRows()).isZero();
    }

    // ==================== 无副作用 Ack 的其余路径 ====================

    @Test
    void unknownJobAcksWithoutSideEffects() throws Exception {
        exportJobConsumer.onMessage(message(99999L, 1L, null), channel);

        verify(channel).basicAck(1L, false);
        assertThat(attemptRows()).isZero();
    }

    @Test
    void maxAttemptsReachedAcksAndKeepsPending() throws Exception {
        long jobId = insertPendingJob();
        jdbcTemplate.update("UPDATE export_jobs SET attempt_count = 3 WHERE id = " + jobId);

        exportJobConsumer.onMessage(message(jobId, 1L, null), channel);

        // 达尝试上限不可再抢占（attempt_count < 3 不满足）：Ack 收敛，状态与执行历史均不变
        verify(channel).basicAck(1L, false);
        assertThat(jobStatus()).isEqualTo("PENDING");
        assertThat(attemptCount()).isEqualTo(3);
        assertThat(attemptRows()).isZero();
    }

    // ==================== 数据准备与查询辅助 ====================

    /** 直插一条 PENDING 任务（绕过创建链路，消费测试不依赖 HTTP），返回自增主键。 */
    private long insertPendingJob() {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO export_jobs (job_no, status, max_order_id_at_create, idempotency_key, request_hash,
                    filter_count, filter_snapshot, selected_order_ids, selected_columns, requested_file_name,
                    created_at, updated_at)
                VALUES (?, 'PENDING', 0, ?, ?, 1, '{}', '[]', '["order_no"]', 'demo.xlsx',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "EXP-TEST-" + unique, "idem-" + unique, "h".repeat(64));
        return jdbcTemplate.queryForObject("SELECT id FROM export_jobs", Long.class);
    }

    /** 构造合法契约消息（schema=1）。 */
    private Message message(long jobId, long deliveryTag, String traceId) throws Exception {
        ExportJobMessage body = new ExportJobMessage(ExportJobMessage.SCHEMA_VERSION,
                "msg-" + deliveryTag, jobId, ExportJobMessage.EVENT_VERSION);
        return rawMessage(objectMapper.writeValueAsString(body), deliveryTag, traceId);
    }

    /** 构造原始 JSON 消息（契约分流用例用）。 */
    private Message rawMessage(String json, long deliveryTag, String traceId) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        if (traceId != null) {
            properties.setHeader(TraceIdSupport.HEADER_NAME, traceId);
        }
        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }

    /** 在 execute 调用期间捕获 MDC trace_id（验证 Header 恢复/新建是否落到执行上下文）。 */
    private AtomicReference<String> captureMdcDuringExecution() {
        AtomicReference<String> mdcTrace = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcTrace.set(MDC.get(TraceIdSupport.MDC_KEY));
            return null;
        }).when(exportExecutionService).execute(anyLong());
        return mdcTrace;
    }

    private String jobStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM export_jobs", String.class);
    }

    private Object jobColumn(String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM export_jobs", Object.class);
    }

    private Object attemptColumn(String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM export_job_attempts", Object.class);
    }

    private int attemptCount() {
        return jdbcTemplate.queryForObject("SELECT attempt_count FROM export_jobs", Integer.class);
    }

    private int attemptRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM export_job_attempts", Integer.class);
    }
}
