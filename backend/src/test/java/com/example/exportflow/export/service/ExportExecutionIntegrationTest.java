package com.example.exportflow.export.service;

import com.example.exportflow.export.excel.ExcelExportWriter;
import com.example.exportflow.export.mapper.ExportOrderMapper;
import com.example.exportflow.order.query.OrderCriteria;
import com.example.exportflow.order.query.SortDirection;
import com.example.exportflow.order.query.SortField;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 执行体集成验证：Keyset 读取管道、SXSSF 写盘、发布协议成功终态与
 * 补偿收敛——高水位阻断、批次边界、快照重建、排除 ID、空值防御、发布登记、DB 失败补偿。
 * <p>真实 Mapper + H2 执行真 SQL；直接调用执行服务（消费端链路由 ExportJobConsumerTest 覆盖）。
 */
@SpringBootTest
class ExportExecutionIntegrationTest {

    /** 测试专用 exportRoot：隔离于默认目录，避免测试产物落入 backend/export-files 与跨运行残留。 */
    private static final Path FILE_DIR;

    static {
        try {
            FILE_DIR = Files.createTempDirectory("export-execution-test");
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


    private static final String INSERT_ORDER_SQL = """
            INSERT INTO orders (id, order_no, customer_name, status, order_status, sales_channel,
                                customer_phone, currency, total_amount, shipping_province, created_at)
            VALUES (?, ?, '测试客户', ?, ?, ?, '13800000000', 'CNY', 100.00, '浙江省',
                    TIMESTAMP '2026-01-01 10:00:00')
            """;

    @Autowired
    private ExportExecutionService exportExecutionService;

    @SpyBean
    private ExportJobService exportJobService;

    @SpyBean
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
        assertThat(jobStatus(jobId)).isEqualTo("SUCCEEDED");
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
    void emptySnapshotSucceedsWithHeaderOnlyFile() {
        long jobId = insertRunningJob(0L, "{}");

        exportExecutionService.execute(jobId);

        // 空批即刻结束：0 行任务同样走完整发布协议（表头-only 文件发布 + SUCCEEDED 登记）
        assertThat(processedRows(jobId)).isZero();
        assertThat(jobStatus(jobId)).isEqualTo("SUCCEEDED");
        assertThat(Files.exists(exportFileService.resolvePersisted(publishedFilePath(jobId)))).isTrue();
    }

    // ==================== 发布协议：成功登记与失败补偿 ====================

    @Test
    void filePublishedAndRegisteredOnSuccess() {
        insertOrders(1, 3, "EF-", "PAID", "WEB");
        long jobId = insertRunningJob(3L, "{}");

        exportExecutionService.execute(jobId);

        // 读取 + 写盘 + 发布全链路：行数一致，产物以 attempt-N.xlsx 登记相对路径与字节大小
        assertThat(processedRows(jobId)).isEqualTo(3L);
        assertThat(jobStatus(jobId)).isEqualTo("SUCCEEDED");
        assertThat(publishedFilePath(jobId)).endsWith("/" + jobId + "/attempt-0.xlsx");
        Path published = exportFileService.resolvePersisted(publishedFilePath(jobId));
        assertThat(Files.exists(published)).isTrue();
        assertThat(fileSizeBytes(jobId)).isPositive();
    }

    @Test
    void dbSucceedFailureCompensatesByDeletingPublishedFile() throws Exception {
        insertOrders(1, 2, "EF-", "PAID", "WEB");
        long jobId = insertRunningJob(2L, "{}");
        AtomicReference<Path> publishedPath = new AtomicReference<>();
        doAnswer(inv -> {
            PublishedFile published = (PublishedFile) inv.callRealMethod();
            publishedPath.set(published.absolutePath());
            return published;
        }).when(exportFileService).publish(any(Path.class), anyInt());
        // 模拟第 3 步 MySQL 成功事务失败：文件已发布但状态提交不上
        doThrow(new RuntimeException("db down")).when(exportJobService)
                .markSucceeded(anyLong(), anyString(), anyLong());

        exportExecutionService.execute(jobId);

        // 补偿收敛：Job 走 FAILED，已发布未登记的正式文件被删除，不留孤儿
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(jobErrorCode(jobId)).isEqualTo(ExportExecutionService.ERROR_CODE_FILE_GENERATION);
        assertThat(Files.exists(publishedPath.get())).isFalse();
    }

    @Test
    void unsupportedAtomicMoveFailsWithoutPublishing() throws Exception {
        insertOrders(1, 1, "EF-", "PAID", "WEB");
        long jobId = insertRunningJob(1L, "{}");
        Path temporary = exportFileService.temporaryPath(jobId, 0);
        doThrow(new AtomicMoveNotSupportedException(temporary.toString(), null, "atomic move unsupported"))
                .when(exportFileService).publish(any(Path.class), anyInt());

        exportExecutionService.execute(jobId);

        // 不静默降级：任务失败、半成品清理、不产出任何 xlsx
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(Files.exists(temporary)).isFalse();
        assertThat(Files.exists(temporary.resolveSibling("attempt-0.xlsx"))).isFalse();
    }

    @Test
    void markSucceededOnTerminalJobThrowsWithoutStateRewrite() {
        long jobId = insertRunningJob(0L, "{}");
        jdbcTemplate.update("UPDATE export_jobs SET status = 'FAILED' WHERE id = ?", jobId);

        assertThatIllegalStateException().isThrownBy(() ->
                exportJobService.markSucceeded(jobId, "2026-09-09/" + jobId + "/attempt-1.xlsx", 10L));

        // 单向条件守卫：终态不可改写，产物路径也未登记
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(publishedFilePath(jobId)).isNull();
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

    /** 直插一条 RUNNING 任务（消费链路已由消费端测试覆盖，此处绕过抢占直达执行体）。 */
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

    private String publishedFilePath(long jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT file_path FROM export_jobs WHERE id = ?", String.class, jobId);
    }

    private Long fileSizeBytes(long jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT file_size_bytes FROM export_jobs WHERE id = ?", Long.class, jobId);
    }
}
