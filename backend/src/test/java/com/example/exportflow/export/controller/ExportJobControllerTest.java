package com.example.exportflow.export.controller;

import com.example.exportflow.export.entity.OutboxEventEntity;
import com.example.exportflow.export.mapper.OutboxEventMapper;
import com.example.exportflow.order.mapper.TestOrderDataSeeder;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 创建导出任务接口契约验证：统一 Envelope、202 受理、field_errors、业务错误码、幂等语义
 * 与 Job/Outbox 同事务写入（契约见 docs/export-http-boundary-plan.md 第 5-10 节）。
 * <p>链路走真实 MyBatis + H2（TestOrderDataSeeder 预置 57 行确定性订单），
 * PAID 为 n=15..44 共 30 行，可断言精确命中数。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportJobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private OutboxEventMapper outboxEventMapper;

    @BeforeEach
    void seedDeterministicOrders() {
        TestOrderDataSeeder.seed(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM export_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    // ==================== 合法创建 ====================

    @Test
    void createSelectedIdsJobReturns202Envelope() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-selected-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[3,1,2,2]},
                                 "columns":["total_amount","order_no"]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(jsonPath("$.trace_id").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                // 重复 ID 2 去重后 3 行；列按白名单序重排不校验顺序
                .andExpect(jsonPath("$.data.total_rows").value(3))
                .andExpect(jsonPath("$.data.job_no").isNotEmpty())
                .andExpect(jsonPath("$.data.job_id").isNumber());

        // 同事务落库：任务 PENDING、指纹 64 位、一致性边界为勾选命中范围的最大 ID（3）
        assertThat(queryForLong("SELECT COUNT(*) FROM export_jobs")).isEqualTo(1L);
        assertThat(queryForString("SELECT status FROM export_jobs")).isEqualTo("PENDING");
        assertThat(queryForLong("SELECT max_order_id_at_create FROM export_jobs")).isEqualTo(3L);
        assertThat((String) queryForString("SELECT request_hash FROM export_jobs")).hasSize(64);
        // 勾选快照保存去重排序后的 ID，勾选模式下 filter_snapshot 承载含 ids 的取数条件
        assertThat(queryForString("SELECT selected_order_ids FROM export_jobs")).isEqualTo("[1,2,3]");
        assertThat(queryForString("SELECT selected_columns FROM export_jobs")).isEqualTo("[\"order_no\",\"total_amount\"]");
        assertThat(queryForLong("SELECT COUNT(*) FROM outbox_events")).isEqualTo(1L);
    }

    @Test
    void createFilterJobWritesOutboxPayload() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-filter-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"FILTER",
                                  "filter":{"order_status":["PAID"],"sort_by":"created_at","sort_order":"desc"}},
                                 "columns":["order_no","order_status"],"file_name":"paid-orders"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.total_rows").value(30))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();

        // Outbox payload 必含 job_id/job_no/request_snapshot/columns/file_name/trace_id
        String payload = queryForString("SELECT payload FROM outbox_events");
        assertThat(payload).contains("\"job_no\":\"EXP").contains("\"columns\":[\"order_no\",\"order_status\"]")
                .contains("\"file_name\":\"paid-orders\"").contains("\"trace_id\":\"")
                .contains("\"request_snapshot\":");
        // filter_snapshot 保存规范化取数条件（OrderCriteria），FILTER 模式 ids 为空
        assertThat(queryForString("SELECT filter_snapshot FROM export_jobs")).contains("\"statuses\":[\"PAID\"]");
        assertThat(queryForString("SELECT selected_order_ids FROM export_jobs")).isEqualTo("[]");

        // trace_id 同时写入响应 Envelope 与 Outbox 记录（10.3.2）
        String traceId = JsonPath.read(result.getResponse().getContentAsString(), "$.trace_id");
        Integer jobId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.job_id");
        assertThat(queryForString("SELECT trace_id FROM outbox_events WHERE aggregate_id = " + jobId))
                .isEqualTo(traceId);
    }

    @Test
    void partialMissingSelectedIdsExportedAsExisting() throws Exception {
        // 部分不存在只按存在的 ID 导出（命中 1 行）
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-partial-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[1,999999]},
                                 "columns":["order_no"]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.total_rows").value(1));
    }

    @Test
    void excludedOrderIdsNarrowFilterCount() throws Exception {
        // PENDING 共 14 行（n=1..14），排除 id=1 后 13 行
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-excluded-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"FILTER",
                                  "filter":{"order_status":["PENDING"]},"excluded_order_ids":[1]},
                                 "columns":["order_no"]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.total_rows").value(13));
    }

    @Test
    void fileNameSanitizedOrGenerated() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-file-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[1]},
                                 "columns":["order_no"],"file_name":" a/b:c*d "}
                                """))
                .andExpect(status().isAccepted());
        // 路径分隔符等非法字符被清理、首尾空白被裁剪
        assertThat(queryForString("SELECT requested_file_name FROM export_jobs")).isEqualTo("abcd");

        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-file-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[2]},"columns":["order_no"]}
                                """))
                .andExpect(status().isAccepted());
        // 未传文件名时由服务端按时间生成
        assertThat(queryForString("SELECT requested_file_name FROM export_jobs WHERE idempotency_key = 'key-file-2'"))
                .startsWith("export-");
    }

    // ==================== DTO 结构错误（VALIDATION_ERROR + field_errors） ====================

    @Test
    void rejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[1]},"columns":["order_no"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.trace_id").isNotEmpty());
    }

    @Test
    void rejectMissingSelectionWithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-no-selection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[\"order_no\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.field_errors['selection']").isNotEmpty());
    }

    @Test
    void rejectEmptyColumnsWithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-empty-columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[1]},"columns":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.field_errors['columns']").isNotEmpty());
    }

    @Test
    void rejectTooManyColumnsWithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-many-columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[1]},
                                 "columns":["order_no","order_status","sales_channel","customer_name",
                                            "customer_phone","total_amount","currency","shipping_province",
                                            "created_at","currency"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.field_errors['columns']").isNotEmpty());
    }

    @Test
    void rejectInvalidModeWithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-bad-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"EVERYTHING","order_ids":[1]},"columns":["order_no"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.field_errors['selection.mode']").isNotEmpty());
    }

    @Test
    void rejectSelectedIdsWithFilterFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-mixed-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[1],
                                  "filter":{"order_status":["PAID"]}},
                                 "columns":["order_no"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.field_errors").isMap());
    }

    @Test
    void rejectFilterWithOrderIdsFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-mixed-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"FILTER","order_ids":[1],
                                  "filter":{"order_status":["PAID"]}},
                                 "columns":["order_no"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.field_errors").isMap());
    }

    @Test
    void rejectNestedOverlongFieldWithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-long-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"FILTER",
                                  "filter":{"customer_name":"%s"}},"columns":["order_no"]}
                                """.formatted("名".repeat(129))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.field_errors['selection.filter.customerName']").isNotEmpty());
    }

    @Test
    void rejectMalformedJsonBody() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-bad-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selection\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ==================== 业务错误码 ====================

    @Test
    void rejectUnknownColumnWithBusinessCode() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-unknown-column")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[1]},
                                 "columns":["order_no","secret_column"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPORT_COLUMNS_INVALID"));
    }

    @Test
    void rejectAllMissingSelectedIds() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-none-exists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[999999,888888]},
                                 "columns":["order_no"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPORT_SELECTION_EMPTY"));
    }

    @Test
    void rejectZeroRowsFilter() throws Exception {
        // CANCELED 在种子数据中为 0 行（bucket = n < 90 恒不满足）
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-zero-rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"FILTER",
                                  "filter":{"order_status":["CANCELED"]}},"columns":["order_no"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPORT_FILTER_ZERO_ROWS"));
    }

    @Test
    void rejectInvalidEnumInFilter() throws Exception {
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-bad-enum")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"FILTER",
                                  "filter":{"order_status":["PAID","FOO"]}},"columns":["order_no"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ==================== 幂等语义 ====================

    @Test
    void idempotentReplayReturnsSameJobWithoutDuplication() throws Exception {
        String requestJson = """
                {"selection":{"mode":"SELECTED_IDS","order_ids":[1,2,3]},"columns":["order_no"]}
                """;
        MvcResult first = performCreate("key-replay", requestJson);
        MvcResult second = performCreate("key-replay", requestJson);

        assertThat(JsonPath.<Integer>read(first.getResponse().getContentAsString(), "$.data.job_id"))
                .isEqualTo(JsonPath.<Integer>read(second.getResponse().getContentAsString(), "$.data.job_id"));
        // 复用不新增任务与 Outbox 记录
        assertThat(queryForLong("SELECT COUNT(*) FROM export_jobs")).isEqualTo(1L);
        assertThat(queryForLong("SELECT COUNT(*) FROM outbox_events")).isEqualTo(1L);
    }

    @Test
    void idempotentConflictReturns409() throws Exception {
        performCreate("key-conflict", """
                {"selection":{"mode":"SELECTED_IDS","order_ids":[1,2,3]},"columns":["order_no"]}
                """);
        // 相同幂等键但请求内容不同（列不同 → 指纹不同）
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[1,2,3]},"columns":["order_no","currency"]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(queryForLong("SELECT COUNT(*) FROM export_jobs")).isEqualTo(1L);
    }

    @Test
    void outboxFailureRollsBackJobInsert() throws Exception {
        doThrow(new RuntimeException("outbox down")).when(outboxEventMapper).insert(any(OutboxEventEntity.class));
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"SELECTED_IDS","order_ids":[1,2]},"columns":["order_no"]}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        // 事务回滚：不留下 PENDING 孤儿任务
        Integer orphanCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM export_jobs WHERE idempotency_key = 'key-rollback'", Integer.class);
        assertThat(orphanCount).isZero();
    }

    /** 捕获创建响应（供幂等场景比较任务 ID）。 */
    private MvcResult performCreate(String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
    }

    private Long queryForLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private String queryForString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
