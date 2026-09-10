package com.example.flowhub.orderimport.service;

import com.example.flowhub.orderimport.command.ImportColumn;
import com.example.flowhub.orderimport.vo.ImportJobAcceptedVO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入执行体端到端收敛：SAX 读 → 行级校验 → 文件内查重 → DB 冲突预查 → 批量入库 → 进度 → 终态。
 * <p>
 * 真实 Mapper + H2 执行真 SQL，文件经 ImportFileService 落盘再 SAX 流式读回；
 * 验证 SUCCEEDED（全成功）/PARTIAL（跳过行 + 错误报告 + 错误摘要）/文件内重复/库内冲突不产生重复订单。
 */
@SpringBootTest
class ImportExecutionIntegrationTest {

    @Autowired
    private ImportJobService importJobService;

    @Autowired
    private ImportExecutionService importExecutionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM import_job_attempts");
        jdbcTemplate.update("DELETE FROM import_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    // ==================== 全成功：SUCCEEDED ====================

    @Test
    void allValidRowsImportsAndSucceeds() throws IOException {
        long jobId = acceptAndRun(List.<String[]>of(
                row("IMP-E2E-0001", "PAID", "WEB", "张三", "13800000001", "98.50", "CNY", "北京", "2026-09-01 10:00:00"),
                row("IMP-E2E-0002", "SHIPPED", "APP", "李四", "", "1200", "USD", "上海", "2026-09-01 11:00:00")
        ));

        assertThat(jobStatus(jobId)).isEqualTo("SUCCEEDED");
        assertThat(orderCount("IMP-E2E-0001")).isOne();
        assertThat(orderCount("IMP-E2E-0002")).isOne();
        // order_status 与 status 双写一致
        assertThat(orderColumn("IMP-E2E-0001", "order_status")).isEqualTo("PAID");
        assertThat(orderColumn("IMP-E2E-0001", "status")).isEqualTo("PAID");
        assertThat(numberColumn(jobId, "succeeded_rows")).isEqualTo(2);
        assertThat(numberColumn(jobId, "skipped_rows")).isEqualTo(0);
        assertThat(errorReportPath(jobId)).isNull();
    }

    // ==================== 部分成功：PARTIAL + 错误报告 ====================

    @Test
    void invalidRowSkippedAndPartialWithErrorReport() throws IOException {
        long jobId = acceptAndRun(List.<String[]>of(
                row("IMP-E2E-0010", "PAID", "WEB", "张三", "", "98.50", "CNY", "北京", "2026-09-01 10:00:00"),
                // 订单状态非法 → 该行跳过，有效行照常导入（决策 2）
                row("IMP-E2E-0011", "BOGUS", "WEB", "王五", "", "10", "CNY", "广东", "2026-09-01 10:00:00")
        ));

        assertThat(jobStatus(jobId)).isEqualTo("PARTIAL");
        assertThat(orderCount("IMP-E2E-0010")).isOne();
        assertThat(orderCount("IMP-E2E-0011")).isZero();
        assertThat(numberColumn(jobId, "succeeded_rows")).isEqualTo(1);
        assertThat(numberColumn(jobId, "skipped_rows")).isEqualTo(1);
        // 错误报告已生成并可下载；错误摘要含订单状态非法原因
        assertThat(errorReportPath(jobId)).isNotBlank();
        ImportJobService.DownloadableImportReport report = importJobService.getDownloadableErrorReport(jobId);
        assertThat(report.absolutePath()).exists();
        assertThat(stringColumn(jobId, "error_summary")).contains("订单状态非法");
    }

    // ==================== 文件内重复：后行跳过 ====================

    @Test
    void duplicateOrderNoWithinFileSkipsSecond() throws IOException {
        long jobId = acceptAndRun(List.<String[]>of(
                row("IMP-E2E-0020", "PAID", "WEB", "张三", "", "98.50", "CNY", "北京", "2026-09-01 10:00:00"),
                row("IMP-E2E-0020", "PAID", "WEB", "张三", "", "98.50", "CNY", "北京", "2026-09-01 10:00:00")
        ));

        assertThat(jobStatus(jobId)).isEqualTo("PARTIAL");
        assertThat(orderCount("IMP-E2E-0020")).isOne();
        assertThat(numberColumn(jobId, "succeeded_rows")).isEqualTo(1);
        assertThat(numberColumn(jobId, "skipped_rows")).isEqualTo(1);
        assertThat(stringColumn(jobId, "error_summary")).contains("订单号在文件内重复");
    }

    // ==================== 库内冲突：预查跳过，不产生重复订单 ====================

    @Test
    void orderAlreadyInDbSkippedWithoutDuplicateRow() throws IOException {
        String existing = "IMP-E2E-CONFLICT";
        insertPreExistingOrder(existing);
        long jobId = acceptAndRun(List.<String[]>of(
                row(existing, "PAID", "WEB", "张三", "", "98.50", "CNY", "北京", "2026-09-01 10:00:00")
        ));

        assertThat(jobStatus(jobId)).isEqualTo("PARTIAL");
        assertThat(orderCount(existing)).isOne();
        assertThat(numberColumn(jobId, "succeeded_rows")).isEqualTo(0);
        assertThat(numberColumn(jobId, "skipped_rows")).isEqualTo(1);
        assertThat(stringColumn(jobId, "error_summary")).contains("订单号与库内已有单据重复");
    }

    // ==================== 受理 + 抢占 + 执行组装 ====================

    /** 经真实创建链路受理（写文件 + 建任务），条件抢占后执行，返回任务主键。 */
    private long acceptAndRun(List<String[]> dataRows) throws IOException {
        byte[] bytes = workbookBytes(dataRows);
        MultipartFile file = new MockMultipartFile("file", "import.xlsx",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes);
        ImportJobAcceptedVO accepted = importJobService.createJob(file);
        assertThat(importJobService.claimPendingJob(accepted.jobId())).isTrue();
        importExecutionService.execute(accepted.jobId());
        return accepted.jobId();
    }

    /** 拼一行 9 列数据（列序与 ImportColumn 表头契约一致）。 */
    private static String[] row(String orderNo, String status, String channel, String name, String phone,
                                String amount, String currency, String province, String createdAt) {
        return new String[]{orderNo, status, channel, name, phone, amount, currency, province, createdAt};
    }

    /** 生成含表头 + 数据行的 XLSX 字节（全部按文本写入，SAX DataFormatter 读回文本口径一致）。 */
    private byte[] workbookBytes(List<String[]> dataRows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("订单数据");
            var header = sheet.createRow(0);
            for (int c = 0; c < ImportColumn.all().size(); c++) {
                header.createCell(c).setCellValue(ImportColumn.all().get(c).title());
            }
            int rowIdx = 1;
            for (String[] cells : dataRows) {
                var r = sheet.createRow(rowIdx++);
                for (int c = 0; c < cells.length; c++) {
                    r.createCell(c).setCellValue(cells[c]);
                }
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                return out.toByteArray();
            }
        }
    }

    /** 直接插入一条已存在订单（冲突预查命中）——校验 inserts 不再重复。 */
    private void insertPreExistingOrder(String orderNo) {
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, order_status, sales_channel, customer_name, customer_phone,
                    total_amount, currency, shipping_province, created_at, status)
                VALUES (?, 'PAID', 'WEB', '已存在', '', 100.00, 'CNY', '北京', CURRENT_TIMESTAMP, 'PAID')
                """, orderNo);
    }

    private String jobStatus(long jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM import_jobs WHERE id = ?", String.class, jobId);
    }

    private String errorReportPath(long jobId) {
        return jdbcTemplate.queryForObject("SELECT error_report_path FROM import_jobs WHERE id = ?", String.class, jobId);
    }

    private String stringColumn(long jobId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM import_jobs WHERE id = ?", String.class, jobId);
    }

    private int numberColumn(long jobId, String column) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM import_jobs WHERE id = ?", Integer.class, jobId);
        return value == null ? 0 : value;
    }

    private int orderCount(String orderNo) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE order_no = ?", Integer.class, orderNo);
        return count == null ? 0 : count;
    }

    private String orderColumn(String orderNo, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM orders WHERE order_no = ?", String.class, orderNo);
    }
}