package com.example.flowhub.export.service;

import com.example.flowhub.export.event.ExportJobChanged;
import com.example.flowhub.export.mapper.ExportJobMapper;
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
 * 进度集成矩阵：进度条件守卫（单调/终态/fail-fast）、version 单调、Redis 降级不阻塞、
 * SSE 广播与坏连接隔离、事务回滚不广播（AFTER_COMMIT 语义）、SSE 端点异步流。
 * <p>Redis 以 SpyBean 注入受控行为；SSE 连接以 Mockito mock 注入（attach 包级入口）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportProgressSseIntegrationTest {

    @Autowired
    private ExportProgressService exportProgressService;

    @Autowired
    private ExportJobService exportJobService;

    @SpyBean
    private ExportSseService exportSseService;

    @SpyBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ExportJobMapper exportJobMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM export_job_attempts");
        jdbcTemplate.update("DELETE FROM export_jobs");
    }

    // ==================== 条件守卫：单调递增 + 终态不可写 + fail-fast ====================

    @Test
    void staleBatchRejectedByMonotonicGuard() {
        long jobId = insertRunningJob(100L);
        exportProgressService.report(jobId, 500, 100);

        // 迟到的旧批次（500 之后报 200）：0 行 → fail-fast，事实不被回退
        assertThatThrownBy(() -> exportProgressService.report(jobId, 200, 100))
                .isInstanceOf(IllegalStateException.class);
        assertThat(processedRows(jobId)).isEqualTo(500L);
    }

    @Test
    void terminalJobRejectsProgressWrite() {
        long jobId = insertRunningJob(100L);
        exportProgressService.report(jobId, 300, 100);
        exportJobService.markFailed(jobId, "FILE_GENERATION_FAILED", "boom");

        assertThatThrownBy(() -> exportProgressService.report(jobId, 600, 100))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(processedRows(jobId)).isEqualTo(300L);
    }

    @Test
    void versionIncrementsMonotonicallyOnProgress() {
        long jobId = insertRunningJob(100L);
        assertThat(jobVersion(jobId)).isEqualTo(0L);

        exportProgressService.report(jobId, 300, 100);
        assertThat(jobVersion(jobId)).isEqualTo(1L);

        exportProgressService.report(jobId, 600, 100);
        assertThat(jobVersion(jobId)).isEqualTo(2L);
    }

    // ==================== 降级：Redis 写失败不阻塞进度推进 ====================

    @Test
    void redisFailureDoesNotBlockProgressOrConvergence() {
        doThrow(new RuntimeException("redis down")).when(stringRedisTemplate).opsForHash();
        long jobId = insertRunningJob(100L);

        exportProgressService.report(jobId, 400, 100);

        // 事实源照常推进：缓存不是事实源
        assertThat(processedRows(jobId)).isEqualTo(400L);
        assertThat(jobVersion(jobId)).isEqualTo(1L);
    }

    // ==================== AFTER_COMMIT：回滚不广播，提交才广播 ====================

    @Test
    void rolledBackFailureDoesNotBroadcast() {
        long jobId = insertRunningJob(100L);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.execute(status -> {
            exportJobService.markFailed(jobId, "FILE_GENERATION_FAILED", "boom");
            status.setRollbackOnly();
            return null;
        });

        // 回滚：事实未落定 → 广播不发生；DB 回到 RUNNING
        verify(exportSseService, never()).jobChanged(any(ExportJobChanged.class));
        assertThat(jobStatus(jobId)).isEqualTo("RUNNING");
    }

    @Test
    void committedFailureBroadcastsOnce() {
        long jobId = insertRunningJob(100L);

        exportJobService.markFailed(jobId, "FILE_GENERATION_FAILED", "boom");

        // 事务提交后 AFTER_COMMIT 同步触发：一次失败收敛一次广播
        verify(exportSseService, times(1)).jobChanged(any(ExportJobChanged.class));
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
    }

    // ==================== SSE：坏连接隔离，不扩散 ====================

    @Test
    void badConnectionRemovedWithoutAffectingOthers() throws Exception {
        long jobId = insertRunningJob(100L);
        SseEmitter bad = Mockito.mock(SseEmitter.class);
        SseEmitter good = Mockito.mock(SseEmitter.class);
        doThrow(new IOException("dead pipe")).when(bad).send(any(SseEmitter.SseEventBuilder.class));
        exportSseService.attach("bad", bad);
        exportSseService.attach("good", good);

        exportJobService.markFailed(jobId, "FILE_GENERATION_FAILED", "boom");
        exportSseService.broadcastHeartbeat();

        // good 正常收到终态广播与心跳；bad 仅在终态时尝试过一次，随后被移除不再收心跳
        verify(good, Mockito.atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
        verify(bad, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    // ==================== 端点：SSE 异步流 ====================

    @Test
    void eventsEndpointStartsAsyncStream() throws Exception {
        mockMvc.perform(get("/api/v1/export-jobs/events"))
                .andExpect(request().asyncStarted());
    }

    // ==================== 数据准备与查询辅助 ====================

    /** 直插一条 RUNNING 任务（抢占链路由消费端测试覆盖），返回自增主键。 */
    private long insertRunningJob(long filterCount) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO export_jobs (job_no, status, max_order_id_at_create, idempotency_key, request_hash,
                    filter_count, filter_snapshot, selected_order_ids, selected_columns, requested_file_name,
                    created_at, updated_at)
                VALUES (?, 'RUNNING', 1000, ?, ?, ?, '{}', '[]', '["order_no"]', 'demo.xlsx',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "EXP-TEST-" + unique, "idem-" + unique, "h".repeat(64), filterCount);
        return jdbcTemplate.queryForObject("SELECT id FROM export_jobs WHERE idempotency_key = ?", Long.class,
                "idem-" + unique);
    }

    private Long processedRows(long jobId) {
        return jdbcTemplate.queryForObject("SELECT processed_rows FROM export_jobs WHERE id = ?", Long.class, jobId);
    }

    private String jobStatus(long jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM export_jobs WHERE id = ?", String.class, jobId);
    }

    private Long jobVersion(long jobId) {
        return jdbcTemplate.queryForObject("SELECT version FROM export_jobs WHERE id = ?", Long.class, jobId);
    }
}
