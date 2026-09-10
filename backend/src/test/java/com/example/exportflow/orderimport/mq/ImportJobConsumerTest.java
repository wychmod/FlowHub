package com.example.exportflow.orderimport.mq;

import com.example.exportflow.common.web.trace.TraceIdSupport;
import com.example.exportflow.orderimport.excel.ExcelImportReader;
import com.example.exportflow.orderimport.excel.ExcelImportReader.RowHandler;
import com.example.exportflow.orderimport.mapper.ImportJobAttemptMapper;
import com.example.exportflow.orderimport.service.ImportExecutionService;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * 导入消费端集成验证：重复投递条件抢占收敛（单 Attempt 双 Ack）、执行失败收敛 FAILED、
 * 契约不支持转 DLQ、trace 恢复/新建、Attempt 插入失败回滚不留孤儿 RUNNING。
 * <p>真实 Mapper + H2 执行真 SQL；Channel 以 Mockito 模拟，Consumer 逻辑直接方法调用验证。
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class ImportJobConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ImportJobConsumer importJobConsumer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private ImportExecutionService importExecutionService;

    @SpyBean
    private ImportJobAttemptMapper importJobAttemptMapper;

    @SpyBean
    private ExcelImportReader excelImportReader;

    private Channel channel;

    @BeforeEach
    void cleanTablesAndChannel() {
        jdbcTemplate.update("DELETE FROM import_job_attempts");
        jdbcTemplate.update("DELETE FROM import_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
        channel = mock(Channel.class);
    }

    // ==================== 重复投递收敛 ====================

    @Test
    void duplicateDeliveryAcksBothAndCreatesSingleAttempt() throws Exception {
        long jobId = insertPendingJob();
        // execute 置为 no-op：Job 停在 RUNNING，模拟「执行期间重复消息到达」
        doNothing().when(importExecutionService).execute(anyLong());

        importJobConsumer.onMessage(message(jobId, 1L, null), channel);
        importJobConsumer.onMessage(message(jobId, 2L, null), channel);

        verify(channel).basicAck(1L, false);
        verify(channel).basicAck(2L, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        assertThat(jobStatus()).isEqualTo("RUNNING");
        assertThat(attemptRows()).isOne();
        assertThat(attemptColumn("attempt_no")).isEqualTo(1);
    }

    // ==================== 执行失败收敛 ====================

    @Test
    void executionFailureConvergesJobAndAttemptFailed(CapturedOutput output) throws Exception {
        long jobId = insertPendingJob();
        // 定制 SAX 读抛 IOException：真实执行壳在解析入口失败，收敛为可查询事实后正常 Ack
        doThrow(new IOException("parse failed")).when(excelImportReader)
                .readRows(any(Path.class), any(RowHandler.class));

        importJobConsumer.onMessage(message(jobId, 1L, null), channel);

        verify(channel).basicAck(1L, false);
        assertThat(jobStatus()).isEqualTo("FAILED");
        assertThat(jobColumn("error_code")).isEqualTo(ImportExecutionService.ERROR_CODE_EXECUTION);
        assertThat((String) jobColumn("error_message")).contains("parse failed");
        assertThat(jobColumn("finished_at")).isNotNull();
        assertThat(attemptRows()).isOne();
        assertThat(attemptColumn("status")).isEqualTo("FAILED");
        assertThat(attemptColumn("error_code")).isEqualTo(ImportExecutionService.ERROR_CODE_EXECUTION);
        assertThat(output).contains("import_execution_failed");
    }

    // ==================== 契约分流：Reject → DLQ ====================

    @Test
    void unsupportedSchemaRejectedToDlq() throws Exception {
        long jobId = insertPendingJob();
        String json = "{\"schema_version\":2,\"message_id\":\"m1\",\"job_id\":" + jobId
                + ",\"event_version\":1}";

        importJobConsumer.onMessage(rawMessage(json, 7L, null), channel);

        verify(channel).basicReject(7L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(jobStatus()).isEqualTo("PENDING");
        assertThat(attemptRows()).isZero();
    }

    @Test
    void malformedBodyRejectedToDlq() throws Exception {
        importJobConsumer.onMessage(rawMessage("not-json", 8L, null), channel);

        verify(channel).basicReject(8L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(attemptRows()).isZero();
    }

    // ==================== trace Header 恢复 / 新建 ====================

    @Test
    void validTraceHeaderRestoredIntoMdcDuringExecution() throws Exception {
        long jobId = insertPendingJob();
        AtomicReference<String> mdcTrace = captureMdcDuringExecution();

        importJobConsumer.onMessage(message(jobId, 1L, "trace-header-valid-001"), channel);

        assertThat(mdcTrace.get()).isEqualTo("trace-header-valid-001");
        assertThat(MDC.get(TraceIdSupport.MDC_KEY)).isNull();
    }

    @Test
    void invalidTraceHeaderCreatesNewTraceDuringExecution() throws Exception {
        long jobId = insertPendingJob();
        AtomicReference<String> mdcTrace = captureMdcDuringExecution();

        importJobConsumer.onMessage(message(jobId, 1L, "short"), channel);

        assertThat(TraceIdSupport.isValid(mdcTrace.get())).isTrue();
    }

    // ==================== 事务回滚：不留孤儿 RUNNING ====================

    @Test
    void attemptInsertFailureRollsBackClaimWithoutOrphanRunning() throws Exception {
        long jobId = insertPendingJob();
        doThrow(new RuntimeException("attempt insert down"))
                .when(importJobAttemptMapper).insertRunning(anyLong(), any(LocalDateTime.class));

        assertThatThrownBy(() -> importJobConsumer.onMessage(message(jobId, 1L, null), channel))
                .isInstanceOf(RuntimeException.class);

        // 异常穿出不确认（保留重投机会）；抢占 UPDATE 随事务回滚，Job 仍 PENDING、无 Attempt
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        assertThat(jobStatus()).isEqualTo("PENDING");
        assertThat(attemptRows()).isZero();
    }

    // ==================== 无副作用 Ack 的其余路径 ====================

    @Test
    void unknownJobAcksWithoutSideEffects() throws Exception {
        importJobConsumer.onMessage(message(99999L, 1L, null), channel);

        verify(channel).basicAck(1L, false);
        assertThat(attemptRows()).isZero();
    }

    @Test
    void maxAttemptsReachedAcksAndKeepsPending() throws Exception {
        long jobId = insertPendingJob();
        jdbcTemplate.update("UPDATE import_jobs SET attempt_count = 3 WHERE id = " + jobId);

        importJobConsumer.onMessage(message(jobId, 1L, null), channel);

        verify(channel).basicAck(1L, false);
        assertThat(jobStatus()).isEqualTo("PENDING");
        assertThat(attemptRows()).isZero();
    }

    // ==================== 数据准备与查询辅助 ====================

    /** 直插一条 PENDING 导入任务（绕过创建链路，消费测试不依赖 HTTP），返回自增主键。 */
    private long insertPendingJob() {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO import_jobs (job_no, status, file_name, file_path, total_rows, created_at)
                VALUES (?, 'PENDING', 'upload.xlsx', ?, 0, CURRENT_TIMESTAMP)
                """, "IMP-TEST-" + unique, "2026-09-01/" + unique + "/upload.xlsx");
        return jdbcTemplate.queryForObject("SELECT id FROM import_jobs", Long.class);
    }

    /** 构造合法契约消息（schema=1）。 */
    private Message message(long jobId, long deliveryTag, String traceId) throws Exception {
        ImportJobMessage body = new ImportJobMessage(ImportJobMessage.SCHEMA_VERSION,
                "msg-" + deliveryTag, jobId, ImportJobMessage.EVENT_VERSION);
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

    /** 在 execute 调用期间捕获 MDC trace_id。 */
    private AtomicReference<String> captureMdcDuringExecution() {
        AtomicReference<String> mdcTrace = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcTrace.set(MDC.get(TraceIdSupport.MDC_KEY));
            return null;
        }).when(importExecutionService).execute(anyLong());
        return mdcTrace;
    }

    private String jobStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM import_jobs", String.class);
    }

    private Object jobColumn(String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM import_jobs", Object.class);
    }

    private Object attemptColumn(String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM import_job_attempts", Object.class);
    }

    private int attemptRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM import_job_attempts", Integer.class);
    }
}