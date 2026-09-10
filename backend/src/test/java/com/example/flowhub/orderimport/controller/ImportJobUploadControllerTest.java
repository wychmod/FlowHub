package com.example.flowhub.orderimport.controller;

import com.example.flowhub.orderimport.command.ImportColumn;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传受理 HTTP 契约（真实 Tomcat 链路）：multipart 超限返回结构化 400 而非连接重置，
 * 业务层 10MB 校验优先于 multipart 闸，正常文件 202 受理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportJobUploadControllerTest {

    /** 测试专用 importRoot：先于上下文装配静态初始化。 */
    private static final Path FILE_DIR = Paths.get(System.getProperty("java.io.tmpdir"),
            "flowhub-import-upload-test-" + System.nanoTime());

    @DynamicPropertySource
    static void fileProps(DynamicPropertyRegistry registry) {
        registry.add("import.files.dir", () -> FILE_DIR.toString());
    }

    @AfterAll
    static void cleanFileDir() throws IOException {
        if (Files.exists(FILE_DIR)) {
            try (Stream<Path> walk = Files.walk(FILE_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM import_job_attempts");
        jdbcTemplate.update("DELETE FROM import_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    // ==================== multipart 超限（真实容器层拒绝） ====================

    @Test
    void oversizeBeyondMultipartLimitReturnsStructured400() {
        // 11MB+1 超过 max-file-size（11MB），仍在 max-request-size（12MB）内，精确触发文件超限
        byte[] oversized = new byte[11 * 1024 * 1024 + 1];
        oversized[0] = 'P';
        oversized[1] = 'K';

        var response = postUpload(oversized, "orders.xlsx");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response.getBody(), "$.code")).isEqualTo("VALIDATION_ERROR");
        assertThat((String) json(response.getBody(), "$.message")).contains("10MB");
    }

    // ==================== 业务层 10MB 校验（multipart 闸放行后兜住） ====================

    @Test
    void oversizeWithinMultipartLimitHitsBusinessGuard() {
        // 10MB+1 超过 import.file.max-size（10MB）但低于 multipart 闸：应返回业务码而非通用校验错误
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        oversized[0] = 'P';
        oversized[1] = 'K';

        var response = postUpload(oversized, "orders.xlsx");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response.getBody(), "$.code")).isEqualTo("IMPORT_FILE_TOO_LARGE");
    }

    // ==================== 结构级校验（multipart 通路完整到达业务链） ====================

    @Test
    void templateMismatchReturnsStructured400() throws IOException {
        byte[] bytes = workbook("订单数据", "错误列名");

        var response = postUpload(bytes, "orders.xlsx");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response.getBody(), "$.code")).isEqualTo("IMPORT_TEMPLATE_MISMATCH");
    }

    // ==================== 正常受理 202 ====================

    @Test
    void validUploadAcceptsWith202() throws IOException {
        String[] titles = ImportColumn.all().stream().map(ImportColumn::title).toArray(String[]::new);
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("订单数据");
            var header = sheet.createRow(0);
            for (int c = 0; c < titles.length; c++) {
                header.createCell(c).setCellValue(titles[c]);
            }
            var row = sheet.createRow(1);
            for (int c = 0; c < titles.length; c++) {
                row.createCell(c).setCellValue("v" + c);
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                bytes = out.toByteArray();
            }
        }

        var response = postUpload(bytes, "orders.xlsx");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(json(response.getBody(), "$.data.status")).isEqualTo("PENDING");
        assertThat(json(response.getBody(), "$.data.total_rows")).isEqualTo(1);
        assertThat((String) json(response.getBody(), "$.data.job_no")).startsWith("IMP");
        // 落盘原件已登记：受理事务提交后文件存在
        long jobId = ((Number) json(response.getBody(), "$.data.job_id")).longValue();
        String filePath = jdbcTemplate.queryForObject(
                "SELECT file_path FROM import_jobs WHERE id = ?", String.class, jobId);
        assertThat(Files.exists(FILE_DIR.resolve(filePath))).isTrue();
    }

    // ==================== 请求辅助 ====================

    /**
     * 以 multipart/form-data 上传指定字节，返回原始 HTTP 响应（正文为 JSON Envelope）。
     * 手工拼 multipart body（byte[] 精确长度 → 请求带 Content-Length）：与浏览器 fetch FormData
     * 行为对齐，使 OversizeRequestBodyFilter 的 Content-Length 预拒在测试中可被触发。
     */
    private org.springframework.http.ResponseEntity<String> postUpload(byte[] bytes, String filename) {
        String boundary = "flowhub-test-" + Long.toHexString(System.nanoTime());
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] tail = ("\r\n--" + boundary + "--\r\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] body = new byte[head.length + bytes.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(bytes, 0, body, head.length, bytes.length);
        System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary));
        return restTemplate.postForEntity("/api/v1/import-jobs", new HttpEntity<>(body, headers), String.class);
    }

    /** 生成仅有指定表头（无数据行）的 XLSX 字节。 */
    private byte[] workbook(String sheetName, String... headerTitles) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet(sheetName);
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

    private Object json(String body, String path) {
        return com.jayway.jsonpath.JsonPath.read(body, path);
    }
}
