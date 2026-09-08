package com.example.exportflow.export.service;

import com.example.exportflow.export.excel.ExcelExportWriter;
import com.example.exportflow.export.mapper.ExportOrderMapper;
import com.example.exportflow.order.query.OrderCriteria;
import com.example.exportflow.order.query.SortDirection;
import com.example.exportflow.order.query.SortField;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 执行体 Keyset 读取管道集成验证（第 15 章）：高水位阻断新增订单、批次边界游标推进、
 * 快照重建完整生效（含状态/渠道/订单号，对应教程审计点）、排除 ID 与空值防御、快照 round-trip。
 * <p>真实 Mapper + H2 执行真 SQL；直接调用执行服务（消费端链路由 ExportJobConsumerTest 覆盖）。
 */
@SpringBootTest
class ExportExecutionIntegrationTest {

    private static final String INSERT_ORDER_SQL = """
            INSERT INTO orders (id, order_no, customer_name, status, order_status, sales_channel,
                                customer_phone, currency, total_amount, shipping_province, created_at)
            VALUES (?, ?, '测试客户', ?, ?, ?, '13800000000', 'CNY', 100.00, '浙江省',
                    TIMESTAMP '2026-01-01 10:00:00')
            """;

    @Autowired
    private ExportExecutionService exportExecutionService;

    @Autowired
    private ExportFileService exportFileService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private ExportOrderMapper exportOrderMapper;

    @SpyBean
    private ExcelExportWriter excelExportWriter;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM export_job_attempts");
        jdbcTemplate.update("DELETE FROM export_jobs");
        jdbcTemplate.update("DELETE FROM orders");
    }

    // ==================== 高水位：创建后新增订单不混入 ====================

    @Test
    void orderCreatedAfterJobExcludedByHighWaterMark() {
        insertOrders(1, 2, "EF-", "PAID", "WEB");
        // 模拟任务创建后落库的新订单：ID 超过创建时高水位，即使条件可命中也不得读取
        insertOrders(999, 1, "EF-", "PAID", "WEB");
        long jobId = insertRunningJob(2L, "{}");

        exportExecutionService.execute(jobId);

        // 空条件全靠 id <= max_order_id_at_create 挡住 999：读到 2 行而非 3 行
        assertThat(processedRows(jobId)).isEqualTo(2);
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
    }

    // ==================== 批次边界：游标推进不重不漏 ====================

    @Test
    void batchBoundaryAdvancesCursorAcrossTwoBatches() {
        insertOrders(1, 1001, "EF-", "PAID", "WEB");
        long jobId = insertRunningJob(1001L, "{}");

        exportExecutionService.execute(jobId);

        assertThat(processedRows(jobId)).isEqualTo(1001);
        // 两批推进：第二批从第一批实际最后一条 id 之后续读（Keyset 游标，而非机械偏移）
        ArgumentCaptor<Long> lastIds = ArgumentCaptor.forClass(Long.class);
        verify(exportOrderMapper, times(2)).findBatch(lastIds.capture(), anyLong(), anyInt(), any());
        assertThat(lastIds.getAllValues()).containsExactly(0L, 1000L);
    }

    // ==================== 快照重建：筛选字段完整生效（教程审计点） ====================

    @Test
    void filterSnapshotFullyRebuiltForBatchQuery() {
        insertOrders(1, 1, "ORD-00", "PAID", "WEB");      // 命中：前缀 + 状态 + 渠道均符合
        insertOrders(2, 1, "ORD-00", "PAID", "APP");      // 渠道不符
        insertOrders(3, 1, "ORD-00", "PENDING", "WEB");   // 状态不符
        insertOrders(4, 1, "ZZZ-", "PAID", "WEB");        // 订单号前缀不符
        long jobId = insertRunningJob(9999L, """
                {"statuses":["PAID"],"salesChannels":["WEB"],"orderNo":"ORD-00"}
                """);

        exportExecutionService.execute(jobId);

        // 创建统计与执行批查同一份筛选语义：仅第 1 行（ORD-001 + PAID + WEB）进入结果
        assertThat(processedRows(jobId)).isEqualTo(1);
    }

    // ==================== 排除 ID：NOT IN 随快照生效 ====================

    @Test
    void excludedOrderIdsSkippedDuringBatchRead() {
        insertOrders(1, 20, "EF-", "PAID", "WEB");
        long jobId = insertRunningJob(20L, "{\"excludedIds\":[10]}");

        exportExecutionService.execute(jobId);

        assertThat(processedRows(jobId)).isEqualTo(19);
    }

    // ==================== 空值防御：空条件 + 空结果 ====================

    @Test
    void emptySnapshotWithNoRowsConvergesWithoutProgress() {
        long jobId = insertRunningJob(0L, "{}");

        exportExecutionService.execute(jobId);

        // 空批即刻结束：不炸、无进度、按「尚未实现」收敛 FAILED（直插任务无 Attempt 记录，断言 Job 侧）
        assertThat(processedRows(jobId)).isZero();
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(jobErrorCode(jobId)).isEqualTo(ExportExecutionService.ERROR_CODE_FILE_GENERATION);
    }

    // ==================== 文件生成（第 17 章 writeBatch 扩展点） ====================

    @Test
    void fileRowsMatchProcessedRowsAndTempCleanedOnFailureConvergence() {
        insertOrders(1, 3, "EF-", "PAID", "WEB");
        long jobId = insertRunningJob(3L, "{}");

        exportExecutionService.execute(jobId);

        // 读取 + 写盘全部成功：processedRows 与写入行数一致；失败收敛删除半成品（成功发布随第 18 章）
        assertThat(processedRows(jobId)).isEqualTo(3L);
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(Files.exists(exportFileService.temporaryPath(jobId, 0))).isFalse();
    }

    @Test
    void writeBatchFailureDoesNotAdvanceProgressAndCleansFile() throws Exception {
        insertOrders(1, 5, "EF-", "PAID", "WEB");
        long jobId = insertRunningJob(5L, "{}");
        // 会话由 open() 每次创建：以 mock 会话注入写盘失败（Mockito 5 支持模拟 final 嵌套类）
        ExcelExportWriter.WorkbookSession failingSession = mock(ExcelExportWriter.WorkbookSession.class);
        doThrow(new RuntimeException("disk full")).when(failingSession).writeBatch(any());
        doReturn(failingSession).when(excelExportWriter).open(any(Path.class), any());
        Path temporary = exportFileService.temporaryPath(jobId, 0);

        exportExecutionService.execute(jobId);

        // 失败屏障：writeBatch 抛出时游标/进度不得虚假推进，半成品临时文件被清理
        assertThat(processedRows(jobId)).isZero();
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(Files.exists(temporary)).isFalse();
    }

    // ==================== 防御：任务不存在 ====================

    @Test
    void missingJobConvergesSilentlyWithoutSideEffects() {
        assertThatCode(() -> exportExecutionService.execute(99999L)).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM export_jobs", Long.class)).isZero();
    }

    // ==================== 快照 round-trip：序列化无损还原 ====================

    @Test
    void filterSnapshotRoundTripPreservesAllCriteriaFields() throws Exception {
        OrderCriteria criteria = new OrderCriteria(
                List.of(3L, 1L), List.of(66L, 91L),
                List.of("PAID", "SHIPPED"), List.of("WEB", "APP"), List.of("CNY"),
                "张伟", "EF2026", "13800000000",
                new BigDecimal("10.50"), new BigDecimal("999.99"),
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0),
                SortField.CREATED_AT, SortDirection.DESC);

        String json = objectMapper.writeValueAsString(criteria);
        OrderCriteria roundTripped = objectMapper.readValue(json, OrderCriteria.class);

        // 落库 JSON 无损还原为等价取数条件：执行端重建不存在漏字段环节
        assertThat(roundTripped).isEqualTo(criteria);
    }

    // ==================== 数据准备与查询辅助 ====================

    /** 从 startId 起插入 count 行同条件订单（显式 id 控制自增与高水位比较，订单号 = 前缀 + id）。 */
    private void insertOrders(long startId, int count, String orderNoPrefix, String status, String channel) {
        List<Object[]> args = new java.util.ArrayList<>();
        for (long i = startId; i < startId + count; i++) {
            args.add(new Object[]{i, orderNoPrefix + i, status, status, channel});
        }
        jdbcTemplate.batchUpdate(INSERT_ORDER_SQL, args);
    }

    /** 直插一条 RUNNING 任务（消费链路已由第 14 章测试覆盖，此处绕过抢占直达执行体）。 */
    private long insertRunningJob(long maxOrderId, String filterSnapshot) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO export_jobs (job_no, status, max_order_id_at_create, idempotency_key, request_hash,
                    filter_count, filter_snapshot, selected_order_ids, selected_columns, requested_file_name,
                    created_at, updated_at)
                VALUES (?, 'RUNNING', ?, ?, ?, 0, ?, '[]', '["order_no"]', 'demo.xlsx',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "EXP-TEST-" + unique, maxOrderId, "idem-" + unique, "h".repeat(64), filterSnapshot);
        return jdbcTemplate.queryForObject("SELECT id FROM export_jobs WHERE idempotency_key = ?", Long.class,
                "idem-" + unique);
    }

    private Long processedRows(long jobId) {
        return jdbcTemplate.queryForObject("SELECT processed_rows FROM export_jobs WHERE id = ?", Long.class, jobId);
    }

    private String jobStatus(long jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM export_jobs WHERE id = ?", String.class, jobId);
    }

    private String jobErrorCode(long jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT error_code FROM export_jobs WHERE id = ?", String.class, jobId);
    }
}
