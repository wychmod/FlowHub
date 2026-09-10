package com.example.flowhub.orderimport.service;

import com.example.flowhub.orderimport.event.ImportJobChanged;
import com.example.flowhub.orderimport.mapper.ImportJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * 导入进度与 SSE 集成矩阵：进度条件守卫（单调/终态/fail-fast）、version 单调（事件 id 栅栏基础）、
 * Redis 降级不阻塞、AFTER_COMMIT 广播语义（回滚不广播）、PARTIAL 终态广播、坏连接隔离与 SSE 端点。
 * <p>Redis 以 SpyBean 注入受控行为；SSE 连接以 Mockito mock 经 attach 包级入口注入。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportProgressSseIntegrationTest {

    @Autowired
    private ImportProgressService importProgressService;

    @Autowired
    private ImportJobService importJobService;

    @SpyBean
    private ImportSseService importSseService;

    @SpyBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ImportJobMapper importJobMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM import_job_attempts");
        jdbcTemplate.update("DELETE FROM import_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    // ==================== 条件守卫：单调递增 + 终态不可写 + fail-fast ====================

    @Test
    void staleBatchRejectedByMonotonicGuard() {
        long jobId = insertRunningJob(100);

        importProgressService.report(jobId, 500, 450, 50, 100);

        // 迟到的旧批次（500 之后报 200）：0 行 → fail-fast，计数不被回退
        assertThatThrownBy(() -> importProgressService.report(jobId, 200, 180, 20, 100))
                .isInstanceOf(IllegalStateException.class);
        assertThat(processedRows(jobId)).isEqualTo(500L);
    }

    @Test
    void terminalJobRejectsProgressWrite() {
        long jobId = insertRunningJob(100);
        importProgressService.report(jobId, 300, 250, 50, 100);
        importJobService.markFailed(jobId, "IMPORT_EXECUTION_FAILED", "boom");

        assertThatThrownBy(() -> importProgressService.report(jobId, 600, 500, 100, 100))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(processedRows(jobId)).isEqualTo(300L);
    }

    @Test
    void versionIncrementsMonotonicallyOnProgress() {
        long jobId = insertRunningJob(100);
        assertThat(jobVersion(jobId)).isEqualTo(0L);

        importProgressService.report(jobId, 300, 250, 50, 100);
        assertThat(jobVersion(jobId)).isEqualTo(1L);

        importProgressService.report(jobId, 600, 500, 100, 100);
        assertThat(jobVersion(jobId)).isEqualTo(2L);
    }

    // ==================== 降级：Redis 写失败不阻塞进度推进 ====================

    @Test
    void redisFailureDoesNotBlockProgressOrConvergence() {
        doThrow(new RuntimeException("redis down")).when(stringRedisTemplate).opsForHash();
        long jobId = insertRunningJob(100);

        importProgressService.report(jobId, 400, 350, 50, 100);

        // 事实源照常推进：缓存不是事实源
        assertThat(processedRows(jobId)).isEqualTo(400L);
        assertThat(jobVersion(jobId)).isEqualTo(1L);
    }

    // ==================== AFTER_COMMIT：回滚不广播，提交才广播 ====================

    @Test
    void rolledBackFailureDoesNotBroadcast() {
        long jobId = insertRunningJob(100);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.execute(status -> {
            importJobService.markFailed(jobId, "IMPORT_EXECUTION_FAILED", "boom");
            status.setRollbackOnly();
            return null;
        });

        // 回滚：事实未落定 → 广播不发生；DB 回到 RUNNING
        verify(importSseService, never()).importChanged(any(ImportJobChanged.class));
        assertThat(jobStatus(jobId)).isEqualTo("RUNNING");
    }

    @Test
    void committedFailureBroadcastsOnce() {
        long jobId = insertRunningJob(100);

        importJobService.markFailed(jobId, "IMPORT_EXECUTION_FAILED", "boom");

        // 事务提交后 AFTER_COMMIT 同步触发：一次失败收敛一次广播
        verify(importSseService, times(1)).importChanged(any(ImportJobChanged.class));
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
    }

    @Test
    void partialConvergenceRegistersReportAndBroadcasts() {
        long jobId = insertRunningJob(100);

        importJobService.markPartial(jobId, "2026-01-01/IMPTEST/errors-attempt-1.xlsx",
                "[{\"reason\":\"订单状态非法\",\"count\":1}]");

        // PARTIAL 终态登记报告路径并广播（import.partial 事件，error_report_available 随 Job 事实派生）
        verify(importSseService, times(1)).importChanged(any(ImportJobChanged.class));
        assertThat(jobStatus(jobId)).isEqualTo("PARTIAL");
        assertThat(errorReportPath(jobId)).isEqualTo("2026-01-01/IMPTEST/errors-attempt-1.xlsx");
        assertThat(errorReportAvailable(jobId)).isEqualTo(1L);
    }

    // ==================== SSE：坏连接隔离，不扩散 ====================

    @Test
    void badConnectionRemovedWithoutAffectingOthers() throws Exception {
        long jobId = insertRunningJob(100);
        SseEmitter bad = Mockito.mock(SseEmitter.class);
        SseEmitter good = Mockito.mock(SseEmitter.class);
        doThrow(new IOException("dead pipe")).when(bad).send(any(SseEmitter.SseEventBuilder.class));
        importSseService.attach("bad", bad);
        importSseService.attach("good", good);

        importJobService.markFailed(jobId, "IMPORT_EXECUTION_FAILED", "boom");
        importSseService.broadcastHeartbeat();

        // good 正常收到终态广播与心跳；bad 仅在终态时尝试过一次，随后被移除不再收心跳
        verify(good, Mockito.atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
        verify(bad, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ==================== 端点：SSE 异步流 ====================

    @Test
    void eventsEndpointStartsAsyncStream() throws Exception {
        mockMvc.perform(get("/api/v1/import-jobs/events"))
                .andExpect(request().asyncStarted());
    }

    // ==================== 数据准备与查询辅助 ====================

    /** 直插一条 RUNNING 任务（抢占链路由消费端测试覆盖），返回自增主键。 */
    private long insertRunningJob(int totalRows) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO import_jobs (job_no, status, file_name, total_rows, created_at)
                VALUES (?, 'RUNNING', 'demo.xlsx', ?, CURRENT_TIMESTAMP)
                """, "IMP-TEST-" + unique, totalRows);
        return jdbcTemplate.queryForObject("SELECT id FROM import_jobs WHERE job_no = ?", Long.class,
                "IMP-TEST-" + unique);
    }

    private Long processedRows(long jobId) {
        return jdbcTemplate.queryForObject("SELECT processed_rows FROM import_jobs WHERE id = ?", Long.class, jobId);
    }

    private String jobStatus(long jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM import_jobs WHERE id = ?", String.class, jobId);
    }

    private Long jobVersion(long jobId) {
        return jdbcTemplate.queryForObject("SELECT version FROM import_jobs WHERE id = ?", Long.class, jobId);
    }

    private String errorReportPath(long jobId) {
        return jdbcTemplate.queryForObject("SELECT error_report_path FROM import_jobs WHERE id = ?", String.class, jobId);
    }

    private Long errorReportAvailable(long jobId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM import_jobs
                WHERE id = ? AND status = 'PARTIAL' AND error_report_path IS NOT NULL
                """, Long.class, jobId);
    }
}
