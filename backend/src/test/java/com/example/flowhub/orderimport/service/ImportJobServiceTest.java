package com.example.flowhub.orderimport.service;

import com.example.flowhub.common.web.error.BusinessException;
import com.example.flowhub.orderimport.command.ImportColumn;
import com.example.flowhub.orderimport.error.ImportErrorCode;
import com.example.flowhub.orderimport.vo.ImportJobAcceptedVO;
import com.example.flowhub.orderimport.vo.ImportJobItemVO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 导入任务服务：受理期文件级/结构级校验的同步拒绝、列表派生字段、人工重试状态重置 + 新 Outbox。
 */
@SpringBootTest
class ImportJobServiceTest {

    @Autowired
    private ImportJobService importJobService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM import_job_attempts");
        jdbcTemplate.update("DELETE FROM import_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    // ==================== 受理期文件级校验（同步 400） ====================

    @Test
    void rejectsNonXlsxExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "orders.txt",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, "PK".getBytes());

        assertThatThrownBy(() -> importJobService.createJob(file))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode().code()).isEqualTo(ImportErrorCode.IMPORT_FORMAT_NOT_SUPPORTED.code()));
    }

    @Test
    void rejectsOversizeFile() {
        byte[] oversized = new byte[10485760 + 1]; // 超过 import.file.max-size 默认 10MB
        oversized[0] = 'P';
        oversized[1] = 'K';
        MockMultipartFile file = new MockMultipartFile("file", "orders.xlsx",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, oversized);

        assertThatThrownBy(() -> importJobService.createJob(file))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode().code()).isEqualTo(ImportErrorCode.IMPORT_FILE_TOO_LARGE.code()));
    }

    // ==================== 受理期结构级校验（同步 400） ====================

    @Test
    void rejectsTemplateHeaderMismatch() throws IOException {
        byte[] bytes = workbookWithHeader("订单号", "错误列名");
        MockMultipartFile file = new MockMultipartFile("file", "orders.xlsx",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes);

        assertThatThrownBy(() -> importJobService.createJob(file))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode().code()).isEqualTo(ImportErrorCode.IMPORT_TEMPLATE_MISMATCH.code()));
    }

    @Test
    void rejectsEmptyFile() throws IOException {
        byte[] bytes = workbookWithHeader(allTitles());
        MockMultipartFile file = new MockMultipartFile("file", "orders.xlsx",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes);

        assertThatThrownBy(() -> importJobService.createJob(file))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode().code()).isEqualTo(ImportErrorCode.IMPORT_EMPTY_FILE.code()));
    }

    // ==================== 列表派生字段 ====================

    @Test
    void listJobsDerivesPartialFieldsAndErrorSummary() {
        long jobId = insertPartialJob();

        ImportJobItemVO item = importJobService.listJobs(1, 10, null).items().stream()
                .filter(i -> i.jobId() == jobId).findFirst().orElseThrow();

        assertThat(item.status()).isEqualTo("PARTIAL");
        assertThat(item.progressPercent()).isEqualTo(100);
        assertThat(item.errorReportAvailable()).isTrue();
        assertThat(item.errorSummary()).isNotEmpty();
        assertThat(item.errorSummary().get(0).reason()).isEqualTo("订单状态非法");
        assertThat(item.errorSummary().get(0).count()).isEqualTo(2);
    }

    // ==================== 人工重试 ====================

    @Test
    void retryResetsToPendingAndPublishesNewOutboxEvent() {
        long jobId = insertFailedJob();

        ImportJobAcceptedVO accepted = importJobService.retry(jobId);

        assertThat(accepted.status()).isEqualTo("PENDING");
        assertThat(queryString("SELECT status FROM import_jobs WHERE id = " + jobId)).isEqualTo("PENDING");
        assertThat(queryLong("SELECT attempt_count FROM import_jobs WHERE id = " + jobId)).isEqualTo(1L);
        assertThat(queryLong("SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'IMPORT_JOB' AND aggregate_id = " + jobId)).isEqualTo(1L);
    }

    @Test
    void retryRefusedWhenNotFailed() {
        long jobId = insertPartialJob();

        assertThatThrownBy(() -> importJobService.retry(jobId))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode().code()).isEqualTo(ImportErrorCode.IMPORT_JOB_NOT_RETRYABLE.code()));
    }

    // ==================== 数据准备辅助 ====================

    private long insertPartialJob() {
        String jobNo = uniqueJobNo();
        jdbcTemplate.update("""
                INSERT INTO import_jobs (job_no, status, file_name, file_path, total_rows, succeeded_rows,
                    skipped_rows, error_report_path, error_summary, expired_at, created_at)
                VALUES (?, 'PARTIAL', 'x.xlsx', ?, 5, 3, 2, ?, ?, '2999-12-31 00:00:00', CURRENT_TIMESTAMP)
                """, jobNo, "2026-09-01/" + jobNo + "/upload.xlsx",
                "2026-09-01/" + jobNo + "/errors-attempt-1.xlsx", "[{\"reason\":\"订单状态非法\",\"count\":2}]");
        return idByJobNo(jobNo);
    }

    private long insertFailedJob() {
        String jobNo = uniqueJobNo();
        jdbcTemplate.update("""
                INSERT INTO import_jobs (job_no, status, file_name, file_path, total_rows, attempt_count,
                    error_code, error_message, created_at)
                VALUES (?, 'FAILED', 'x.xlsx', ?, 5, 1, 'E', 'm', CURRENT_TIMESTAMP)
                """, jobNo, "2026-09-01/" + jobNo + "/upload.xlsx");
        return idByJobNo(jobNo);
    }

    private long idByJobNo(String jobNo) {
        return jdbcTemplate.queryForObject("SELECT id FROM import_jobs WHERE job_no = ?", Long.class, jobNo);
    }

    private String uniqueJobNo() {
        return "IMP" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String[] allTitles() {
        return ImportColumn.all().stream().map(ImportColumn::title).toArray(String[]::new);
    }

    /** 生成仅有指定表头（无数据行）的 XLSX 字节。 */
    private byte[] workbookWithHeader(String... headerTitles) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("订单数据");
            var header = sheet.createRow(0);
            for (int c = 0; c < headerTitles.length; c++) {
                header.createCell(c).setCellValue(headerTitles[c]);
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                return out.toByteArray();
            }
        }
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}