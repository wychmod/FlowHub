package com.example.flowhub.export.service;

import com.example.flowhub.common.web.error.BusinessException;
import com.example.flowhub.export.vo.ExportJobAcceptedVO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 维护服务集成验证：恢复只收敛租约失效的 RUNNING（不误伤活跃执行）、人工重试
 * 保留 Attempt 历史 + 同事务新 Outbox + 重试上限、过期清理「删除成功才 EXPIRED」与路径防腐、
 * 孤儿文件三维对账（宽限期 + 活跃租约 + 引用检查）。
 * <p>真实 Mapper + H2 执行真 SQL；exportRoot 用测试专用临时目录隔离。
 */
@SpringBootTest
class ExportMaintenanceIntegrationTest {

    /** 测试专用 exportRoot：静态初始化先于 @DynamicPropertySource 上下文装配。 */
    private static final Path FILE_DIR;

    static {
        try {
            FILE_DIR = Files.createTempDirectory("export-maintenance-test");
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void fileProps(DynamicPropertyRegistry registry) {
        registry.add("export.files.dir", () -> FILE_DIR.toString());
    }

    @AfterAll
    static void cleanFileDir() throws IOException {
        try (Stream<Path> walk = Files.walk(FILE_DIR)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Autowired
    private ExportMaintenanceService maintenanceService;

    @Autowired
    private ExportJobService exportJobService;

    @Autowired
    private ExportFileService exportFileService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM export_job_attempts");
        jdbcTemplate.update("DELETE FROM export_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    // ==================== 启动恢复：只收敛租约已失效的 RUNNING ====================

    @Test
    void recoveryConvergesOnlyExpiredLeaseRunningJobs() {
        long expiredJob = insertRunningJob(LocalDateTime.now().minusMinutes(10));
        insertRunningAttempt(expiredJob, 1);
        long activeJob = insertRunningJob(LocalDateTime.now().plusMinutes(10));
        insertRunningAttempt(activeJob, 1);

        maintenanceService.recoverRunningJobs();

        // 到期：Job 与 Attempt 同步收敛为 FAILED(SERVICE_RESTARTED)，租约清空供重试后重新抢占
        assertThat(jobColumn(expiredJob, "status")).isEqualTo("FAILED");
        assertThat(jobColumn(expiredJob, "error_code")).isEqualTo(ExportMaintenanceService.ERROR_CODE_SERVICE_RESTARTED);
        assertThat(jobColumn(expiredJob, "lease_expires_at")).isNull();
        assertThat(attemptColumn(expiredJob, "status")).isEqualTo("FAILED");
        assertThat(attemptColumn(expiredJob, "error_code")).isEqualTo(ExportMaintenanceService.ERROR_CODE_SERVICE_RESTARTED);
        // 活跃租约在岗：Worker 可能仍在执行，恢复不得误伤
        assertThat(jobColumn(activeJob, "status")).isEqualTo("RUNNING");
        assertThat(attemptColumn(activeJob, "status")).isEqualTo("RUNNING");
    }

    // ==================== 人工重试：条件重置 + 历史保留 + 同事务新 Outbox ====================

    @Test
    void retryPreservesHistoryAppendsOutboxAndReclaimsThroughClaimPipeline() {
        long jobId = insertRunningJob(LocalDateTime.now().minusMinutes(10));
        insertRunningAttempt(jobId, 1);
        maintenanceService.recoverRunningJobs();
        long outboxBefore = outboxCount(jobId);

        ExportJobAcceptedVO accepted = exportJobService.retry(jobId);

        // 条件重置：回到 PENDING 等待重新投递；Attempt 失败证据一条不删；计数在抢占时才递增
        assertThat(accepted.status()).isEqualTo("PENDING");
        assertThat(attemptRows(jobId)).isEqualTo(1);
        assertThat(attemptColumn(jobId, "status")).isEqualTo("FAILED");
        assertThat((Integer) jobColumn(jobId, "attempt_count")).isEqualTo(1);
        // 同事务新 Outbox：重试任务不会被静默遗忘
        assertThat(outboxCount(jobId)).isEqualTo(outboxBefore + 1);

        // 重试后走真实抢占管道：新 Attempt 创建，attempt_count 递增
        assertThat(exportJobService.claimPendingJob(jobId)).isTrue();
        assertThat(attemptRows(jobId)).isEqualTo(2);
        assertThat((Integer) jobColumn(jobId, "attempt_count")).isEqualTo(2);
    }

    @Test
    void refusesRetryBeyondMaxAttemptsOrNonFailedState() {
        long exhaustedJob = insertJobWithState("FAILED", 3, null);
        assertThatThrownBy(() -> exportJobService.retry(exhaustedJob))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode().code())
                .isEqualTo("EXPORT_JOB_NOT_RETRYABLE");

        // 非 FAILED 不可重试：PENDING 在等、RUNNING 可能活着、SUCCEEDED/EXPIRED 应重建任务
        long succeededJob = insertJobWithState("SUCCEEDED", 1, null);
        assertThatThrownBy(() -> exportJobService.retry(succeededJob))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode().code())
                .isEqualTo("EXPORT_JOB_NOT_RETRYABLE");
    }

    // ==================== 过期清理：删除成功才 EXPIRED + 路径防腐 ====================

    @Test
    void cleanupExpiresOnlyAfterFileDeletedAndKeepsInvalidPathSucceeded() throws Exception {
        long goodJob = insertJobWithState("SUCCEEDED", 1, LocalDateTime.now().minusHours(1));
        PublishedFile published = publishFileFor(goodJob, 1);
        jdbcTemplate.update("UPDATE export_jobs SET file_path = ? WHERE id = ?", published.relativePath(), goodJob);
        long evilJob = insertJobWithState("SUCCEEDED", 1, LocalDateTime.now().minusHours(1));
        jdbcTemplate.update("UPDATE export_jobs SET file_path = '../evil.xlsx' WHERE id = ?", evilJob);
        long futureJob = insertJobWithState("SUCCEEDED", 1, LocalDateTime.now().plusHours(24));
        publishFileFor(futureJob, 1);

        maintenanceService.cleanupExpiredExports();

        // 删除即事实：文件删掉才推进 EXPIRED；路径非法保持 SUCCEEDED；未到期不动
        assertThat(jobColumn(goodJob, "status")).isEqualTo("EXPIRED");
        assertThat(Files.exists(published.absolutePath())).isFalse();
        assertThat(jobColumn(evilJob, "status")).isEqualTo("SUCCEEDED");
        assertThat(jobColumn(futureJob, "status")).isEqualTo("SUCCEEDED");
    }

    // ==================== 孤儿对账：宽限期 + 活跃租约 + 引用检查 ====================

    @Test
    void reconciliationKeepsActiveLeaseTmpAndDeletesUnownedTmp() throws Exception {
        long activeJob = insertRunningJob(LocalDateTime.now().plusMinutes(10));
        insertRunningAttempt(activeJob, 1);
        Path activeTmp = exportFileService.temporaryPath(activeJob, 1);
        Files.write(activeTmp, new byte[]{1});
        long deadJob = insertRunningJob(LocalDateTime.now().minusMinutes(10));
        Path orphanTmp = exportFileService.temporaryPath(deadJob, 1);
        Files.write(orphanTmp, new byte[]{1});
        ageFiles(Duration.ofHours(2), activeTmp, orphanTmp);

        maintenanceService.reconcileStaleFiles();

        // 活跃租约在岗的 .tmp 保留（长批次 Worker 可能正写）；无租约遗留的删除
        assertThat(Files.exists(activeTmp)).isTrue();
        assertThat(Files.exists(orphanTmp)).isFalse();
    }

    @Test
    void reconciliationDeletesOnlyUnregisteredFinalFilesAfterGracePeriod() throws Exception {
        long succeededJob = insertJobWithState("SUCCEEDED", 1, LocalDateTime.now().plusHours(24));
        PublishedFile registered = publishFileFor(succeededJob, 1);
        jdbcTemplate.update("UPDATE export_jobs SET file_path = ? WHERE id = ?", registered.relativePath(), succeededJob);
        long failedJob = insertJobWithState("FAILED", 1, null);
        insertRunningAttempt(failedJob, 1);
        jdbcTemplate.update("UPDATE export_job_attempts SET status = 'FAILED' WHERE job_id = ?", failedJob);
        PublishedFile evidence = publishFileFor(failedJob, 1);
        jdbcTemplate.update("UPDATE export_job_attempts SET file_path = ? WHERE job_id = ?",
                evidence.relativePath(), failedJob);
        long nobodyJob = insertJobWithState("FAILED", 1, null);
        PublishedFile orphan = publishFileFor(nobodyJob, 1);
        ageFiles(Duration.ofHours(2), registered.absolutePath(), evidence.absolutePath(), orphan.absolutePath());

        maintenanceService.reconcileStaleFiles();

        // 已登记下载文件与失败 Attempt 证据均保留；无 Job/Attempt 引用且无活跃租约的才是孤儿
        assertThat(Files.exists(registered.absolutePath())).isTrue();
        assertThat(Files.exists(evidence.absolutePath())).isTrue();
        assertThat(Files.exists(orphan.absolutePath())).isFalse();
    }

    // ==================== 数据准备与查询辅助 ====================

    /** 直插 RUNNING 任务（租约时间入参，attempt_count=1 模拟真实抢占递增后的事实）；绕过抢占直达状态。 */
    private long insertRunningJob(LocalDateTime leaseExpiresAt) {
        String unique = uniqueKey();
        jdbcTemplate.update("""
                INSERT INTO export_jobs (job_no, status, max_order_id_at_create, idempotency_key, request_hash,
                    filter_count, filter_snapshot, selected_order_ids, selected_columns, requested_file_name,
                    attempt_count, last_heartbeat_at, lease_expires_at, created_at, updated_at)
                VALUES (?, 'RUNNING', 0, ?, 'h', 0, '{}', '[]', '["order_no"]', 'demo.xlsx',
                    1, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "EXP-TEST-" + unique, "idem-" + unique, Timestamp.valueOf(leaseExpiresAt));
        return jdbcTemplate.queryForObject("SELECT id FROM export_jobs WHERE idempotency_key = ?", Long.class,
                "idem-" + unique);
    }

    /** 直插指定状态与尝试计数的任务（exceededAt 为 NULL 表示不过期）。 */
    private long insertJobWithState(String status, int attemptCount, LocalDateTime expiredAt) {
        String unique = uniqueKey();
        jdbcTemplate.update("""
                INSERT INTO export_jobs (job_no, status, max_order_id_at_create, idempotency_key, request_hash,
                    filter_count, filter_snapshot, selected_order_ids, selected_columns, requested_file_name,
                    attempt_count, expired_at, created_at, updated_at)
                VALUES (?, ?, 0, ?, 'h', 0, '{}', '[]', '["order_no"]', 'demo.xlsx',
                    ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "EXP-TEST-" + unique, status, "idem-" + unique, attemptCount,
                expiredAt == null ? null : Timestamp.valueOf(expiredAt));
        return jdbcTemplate.queryForObject("SELECT id FROM export_jobs WHERE idempotency_key = ?", Long.class,
                "idem-" + unique);
    }

    private void insertRunningAttempt(long jobId, int attemptNo) {
        jdbcTemplate.update("""
                INSERT INTO export_job_attempts (job_id, attempt_no, status, started_at, created_at)
                VALUES (?, ?, 'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, jobId, attemptNo);
    }

    /** 经受控服务写入并原子发布真实文件，返回发布结果（调用方按需登记 file_path）。 */
    private PublishedFile publishFileFor(long jobId, int attemptNo) throws IOException {
        Path temporary = exportFileService.temporaryPath(jobId, attemptNo);
        Files.write(temporary, ("file-" + jobId).getBytes());
        return exportFileService.publish(temporary, attemptNo);
    }

    /** 把文件修改时间拨到过去（越过宽限期）；文件真实创建时间都是刚生成的，须显式变老才进入候选。 */
    private static void ageFiles(Duration age, Path... files) throws IOException {
        FileTime old = FileTime.from(Instant.now().minus(age));
        for (Path file : files) {
            Files.setLastModifiedTime(file, old);
        }
    }

    private Object jobColumn(long jobId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM export_jobs WHERE id = ?", Object.class, jobId);
    }

    private Object attemptColumn(long jobId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM export_job_attempts WHERE job_id = ?", Object.class, jobId);
    }

    private int attemptRows(long jobId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM export_job_attempts WHERE job_id = ?", Integer.class, jobId);
        return count == null ? 0 : count;
    }

    private long outboxCount(long jobId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, jobId);
        return count == null ? 0 : count;
    }

    private static String uniqueKey() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
