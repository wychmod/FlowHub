package com.example.exportflow.export.controller;

import com.example.exportflow.export.service.ExportFileService;
import com.example.exportflow.export.service.PublishedFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下载接口契约验证（第 18 章下载规则）：只按 Job 查——SUCCEEDED 且未过期才返回文件流，
 * 状态/过期/缺失/路径污染分别返回结构化错误；受控 exportRoot 指向测试专用临时目录。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportJobDownloadControllerTest {

    /** 测试专用 exportRoot：静态初始化先于 @DynamicPropertySource 上下文装配。 */
    private static final Path FILE_DIR;

    static {
        try {
            FILE_DIR = Files.createTempDirectory("export-download-test");
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
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExportFileService exportFileService;

    @BeforeEach
    void cleanJobs() {
        jdbcTemplate.update("DELETE FROM export_job_attempts");
        jdbcTemplate.update("DELETE FROM export_jobs");
    }

    // ==================== 正常下载 ====================

    @Test
    void succeededJobStreamsFileWithDisposition() throws Exception {
        long jobId = insertJob("SUCCEEDED", "八月订单", plusHours(24));
        registerPublishedFile(jobId, "excel-bytes");

        mockMvc.perform(get("/api/v1/export-jobs/{job_id}/download", jobId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                // RFC 5987 filename* 优先：非 ASCII 展示名按 UTF-8 百分号编码
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("filename*=UTF-8''"
                                + java.net.URLEncoder.encode("八月订单.xlsx", StandardCharsets.UTF_8))))
                .andExpect(content().bytes("excel-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    // ==================== 拒绝路径 ====================

    @Test
    void runningJobRejectedWith409() throws Exception {
        long jobId = insertJob("RUNNING", "八月订单", plusHours(24));

        mockMvc.perform(get("/api/v1/export-jobs/{job_id}/download", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_JOB_NOT_DOWNLOADABLE"));
    }

    @Test
    void failedJobRejectedWith409() throws Exception {
        long jobId = insertJob("FAILED", "八月订单", null);

        mockMvc.perform(get("/api/v1/export-jobs/{job_id}/download", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_JOB_NOT_DOWNLOADABLE"));
    }

    @Test
    void expiredJobRejectedWith410() throws Exception {
        long jobId = insertJob("SUCCEEDED", "八月订单", plusHours(-1));

        mockMvc.perform(get("/api/v1/export-jobs/{job_id}/download", jobId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("EXPORT_FILE_EXPIRED"));
    }

    @Test
    void unknownJobReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/export-jobs/{job_id}/download", 99999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPORT_JOB_NOT_FOUND"));
    }

    @Test
    void missingFileOnDiskReturns404() throws Exception {
        // 登记了合法相对路径但磁盘文件已被清理：不重新生成，返回明确错误
        long jobId = insertJob("SUCCEEDED", "八月订单", plusHours(24));
        jdbcTemplate.update("UPDATE export_jobs SET file_path = '2026-01-01/1/attempt-1.xlsx' WHERE id = ?", jobId);

        mockMvc.perform(get("/api/v1/export-jobs/{job_id}/download", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPORT_FILE_MISSING"));
    }

    @Test
    void pollutedPathRejectedWithStructuredError() throws Exception {
        // DB 记录被污染（.. 逃逸）：不读 root 外文件，返回结构化错误而非 500
        long jobId = insertJob("SUCCEEDED", "八月订单", plusHours(24));
        jdbcTemplate.update("UPDATE export_jobs SET file_path = '../secret.xlsx' WHERE id = ?", jobId);

        mockMvc.perform(get("/api/v1/export-jobs/{job_id}/download", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPORT_PATH_INVALID"));
    }

    // ==================== 数据准备 ====================

    /** 直插指定状态的任务；expiredAt 为 null 表示列存 NULL（视为未过期）。 */
    private long insertJob(String status, String fileName, LocalDateTime expiredAt) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO export_jobs (job_no, status, max_order_id_at_create, idempotency_key, request_hash,
                    filter_count, filter_snapshot, selected_order_ids, selected_columns, requested_file_name,
                    finished_at, expired_at, created_at, updated_at)
                VALUES (?, ?, 0, ?, 'h', 0, '{}', '[]', '["order_no"]', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "EXP-TEST-" + unique, status, "idem-" + unique, fileName,
                Timestamp.valueOf(LocalDateTime.now()),
                expiredAt == null ? null : Timestamp.valueOf(expiredAt));
        return jdbcTemplate.queryForObject("SELECT id FROM export_jobs WHERE idempotency_key = ?", Long.class,
                "idem-" + unique);
    }

    /** 经受控服务发布真实文件（临时文件 → 原子移动 → xlsx），并把相对路径/大小登记回任务。 */
    private void registerPublishedFile(long jobId, String content) throws IOException {
        Path temporary = exportFileService.temporaryPath(jobId, 1);
        Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
        PublishedFile published = exportFileService.publish(temporary, 1);
        jdbcTemplate.update("UPDATE export_jobs SET file_path = ?, file_size_bytes = ? WHERE id = ?",
                published.relativePath(), published.sizeBytes(), jobId);
    }

    private static LocalDateTime plusHours(long hours) {
        return LocalDateTime.now().plusHours(hours);
    }
}
