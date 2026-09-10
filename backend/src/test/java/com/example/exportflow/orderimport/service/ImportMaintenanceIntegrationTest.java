package com.example.exportflow.orderimport.service;

import com.example.exportflow.orderimport.mapper.ImportJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入维护服务：启动恢复（只收敛租约失效的 RUNNING）、过期清理（删除成功才 EXPIRED）、孤儿对账。
 * <p>真实 Mapper + H2 + 受控 importRoot（测试 yml 指向 tmp）；调度 cron 已置 "-" 静默，直接方法调用验证。
 */
@SpringBootTest
class ImportMaintenanceIntegrationTest {

    @Autowired
    private ImportMaintenanceService importMaintenanceService;

    @Autowired
    private ImportFileService importFileService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM import_job_attempts");
        jdbcTemplate.update("DELETE FROM import_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    // ==================== 启动恢复：只收敛租约失效 ====================

    @Test
    void recoverOnlyConvergesExpiredLeaseRunning() {
        long expired = insertJob("RUNNING", "2000-01-01 00:00:00");
        long valid = insertJob("RUNNING", "2999-12-31 00:00:00");

        importMaintenanceService.recoverRunningJobs();

        // 租约到期的 RUNNING → FAILED(SERVICE_RESTARTED)；仍活跃的不误伤
        assertThat(status(expired)).isEqualTo("FAILED");
        assertThat(errorCode(expired)).isEqualTo(ImportMaintenanceService.ERROR_CODE_SERVICE_RESTARTED);
        assertThat(status(valid)).isEqualTo("RUNNING");
    }

    // ==================== 过期清理：删除成功才 EXPIRED ====================

    @Test
    void cleanupExpiresSucceededPastRetentionAndDeletesFile() throws IOException {
        // 先落一个真实上传文件（受控根目录内），再建一条已过保留期的 SUCCEEDED 任务引用它
        String jobNo = uniqueJobNo();
        String relPath = importFileService.persistUpload(jobNo, uploadFile(jobNo));
        long jobId = insertFinishedJob("SUCCEEDED", jobNo, relPath);

        importMaintenanceService.cleanupExpiredImports();

        assertThat(status(jobId)).isEqualTo("EXPIRED");
        assertThat(Files.exists(importFileService.resolvePersisted(relPath))).isFalse();
    }

    // ==================== 孤儿对账：死产物删除 ====================

    @Test
    void reconcileRemovesUnreferencedOrphanButKeepsReferencedFile() throws IOException {
        // 死产物：落盘但无任何 Job 引用，且早于宽限期 → 应删除
        String orphanJobNo = uniqueJobNo();
        String orphanPath = importFileService.persistUpload(orphanJobNo, uploadFile(orphanJobNo));
        agePastGrace(importFileService.resolvePersisted(orphanPath));

        // 引用产物：落盘并被一条 SUCCEEDED 任务登记 → 保留
        String refJobNo = uniqueJobNo();
        String refPath = importFileService.persistUpload(refJobNo, uploadFile(refJobNo));
        insertFinishedJob("SUCCEEDED", refJobNo, refPath);

        importMaintenanceService.reconcileStaleFiles();

        assertThat(Files.exists(importFileService.resolvePersisted(orphanPath))).isFalse();
        assertThat(Files.exists(importFileService.resolvePersisted(refPath))).isTrue();
    }

    // ==================== 数据准备辅助 ====================

    /** 直插一条任务（受理初态缺失列以默认/NULL 落库），返回主键。 */
    private long insertJob(String status, String leaseExpiresAt) {
        String jobNo = uniqueJobNo();
        jdbcTemplate.update("""
                INSERT INTO import_jobs (job_no, status, file_name, file_path, total_rows, lease_expires_at, created_at)
                VALUES (?, ?, 'x.xlsx', ?, 0, ?, CURRENT_TIMESTAMP)
                """, jobNo, status, "2026-09-01/" + jobNo + "/upload.xlsx", leaseExpiresAt);
        return idByJobNo(jobNo);
    }

    /** 直插一条已过保留期的终态任务（SUCCEEDED/PARTIAL），用于清理扫描。 */
    private long insertFinishedJob(String status, String jobNo, String filePath) {
        jdbcTemplate.update("""
                INSERT INTO import_jobs (job_no, status, file_name, file_path, total_rows, expired_at, created_at)
                VALUES (?, ?, 'import.xlsx', ?, 5, '2000-01-01 00:00:00', CURRENT_TIMESTAMP)
                """, jobNo, status, filePath);
        return idByJobNo(jobNo);
    }

    private long idByJobNo(String jobNo) {
        return jdbcTemplate.queryForObject("SELECT id FROM import_jobs WHERE job_no = ?", Long.class, jobNo);
    }

    private String uniqueJobNo() {
        return "IMP" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 构造一个最小 MultipartFile 载体（文件内容无关紧要，仅验证路径与生命周期）。 */
    private org.springframework.web.multipart.MultipartFile uploadFile(String jobNo) {
        return new org.springframework.mock.web.MockMultipartFile("file", "import.xlsx",
                "application/octet-stream", ("dummy-" + jobNo).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 把文件修改时间拨到宽限期（1h）之前，使其进入对账候选。 */
    private void agePastGrace(Path path) throws IOException {
        Files.setLastModifiedTime(path,
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(2 * 3600)));
    }

    private String status(long jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM import_jobs WHERE id = ?", String.class, jobId);
    }

    private String errorCode(long jobId) {
        return jdbcTemplate.queryForObject("SELECT error_code FROM import_jobs WHERE id = ?", String.class, jobId);
    }
}