package com.example.flowhub.order.controller;

import com.example.flowhub.order.mapper.TestOrderDataSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单接口契约验证：统一 Envelope、trace_id、分页、条件筛选、排序回显与参数校验
 * （契约见 docs/order-query-design.md 第二节、第六节）。
 * <p>链路走真实 MyBatis + H2（TestOrderDataSeeder 预置 57 行确定性数据，口径对齐 seed 脚本），
 * 因此可断言精确的命中数与首行值。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedDeterministicOrders() {
        TestOrderDataSeeder.seed(jdbcTemplate);
    }

    @Test
    void listOrdersReturnsUnifiedEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("page", "1").param("page_size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(jsonPath("$.trace_id").isNotEmpty())
                .andExpect(jsonPath("$.data.items", hasSize(5)))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(5))
                .andExpect(jsonPath("$.data.total").value(57))
                // 默认排序 created_at,desc：Mock 数据 id 越大下单时间越晚，首行为第 57 单
                .andExpect(jsonPath("$.data.sort_by").value("created_at"))
                .andExpect(jsonPath("$.data.sort_order").value("desc"))
                .andExpect(jsonPath("$.data.items[0].order_no").value("EF2026-00000057"))
                // n=57 的确定性省份：(57*13) % 16 = 5 -> 四川省
                .andExpect(jsonPath("$.data.items[0].shipping_province").value("四川省"));
    }

    @Test
    void filterByStatusMultiValueAndSortEcho() throws Exception {
        // PENDING（n=1..14，14 行）+ SHIPPED（n=45..57，13 行）= 27 行
        mockMvc.perform(get("/api/v1/orders")
                        .param("order_status", "PENDING,SHIPPED")
                        .param("sort_by", "order_no")
                        .param("sort_order", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(27))
                .andExpect(jsonPath("$.data.sort_by").value("order_no"))
                .andExpect(jsonPath("$.data.sort_order").value("asc"))
                .andExpect(jsonPath("$.data.items[0].order_status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].order_no").value("EF2026-00000001"));
    }

    @Test
    void filterByPhoneExactMatch() throws Exception {
        // n=1 的确定性手机号：号段 139 + 8 位散列 00007919
        mockMvc.perform(get("/api/v1/orders").param("customer_phone", "13900007919"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].order_no").value("EF2026-00000001"))
                .andExpect(jsonPath("$.data.items[0].customer_phone").value("13900007919"));
    }

    @Test
    void filterByOrderNoPrefix() throws Exception {
        // 前缀 EF2026-0000005 命中 n=50..57 共 8 行（8 位零填充订单号的前 7 位）
        mockMvc.perform(get("/api/v1/orders").param("order_no", "EF2026-0000005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(8));
    }

    @Test
    void filterByAmountRangeSortedAsc() throws Exception {
        // [100, 200] 区间命中 n=2（123.03）与 n=3（184.54）
        mockMvc.perform(get("/api/v1/orders")
                        .param("total_amount_min", "100")
                        .param("total_amount_max", "200")
                        .param("sort_by", "total_amount")
                        .param("sort_order", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].order_no").value("EF2026-00000002"))
                .andExpect(jsonPath("$.data.items[0].total_amount").value(123.03));
    }

    @Test
    void filterByCreatedRangeHalfOpen() throws Exception {
        // 左闭右开 [2025-09-01, 2025-09-02)：n*9973 秒 < 86400 的 n=1..8 共 8 行
        mockMvc.perform(get("/api/v1/orders")
                        .param("created_at_begin", "2025-09-01T00:00:00")
                        .param("created_at_end", "2025-09-02T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.sort_by").value("created_at"))
                .andExpect(jsonPath("$.data.sort_order").value("desc"));
    }

    @Test
    void blankAndEmptyParamsTreatedAsAbsent() throws Exception {
        // 空串 / 空白串 / 空多值统一视为未传：等价无条件查询
        mockMvc.perform(get("/api/v1/orders")
                        .param("order_status", "")
                        .param("customer_name", "   ")
                        .param("sort_by", " ")
                        .param("sort_order", " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(57))
                .andExpect(jsonPath("$.data.sort_by").value("created_at"))
                .andExpect(jsonPath("$.data.sort_order").value("desc"));
    }

    @Test
    void acceptPaddedAndMixedCaseSortParams() throws Exception {
        // 首尾空白与大小写混合在容忍范围内（归一化由 Service 承担），不得被 @Pattern 误伤
        mockMvc.perform(get("/api/v1/orders")
                        .param("sort_by", " TOTAL_AMOUNT ")
                        .param("sort_order", " aSc "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sort_by").value("total_amount"))
                .andExpect(jsonPath("$.data.sort_order").value("asc"));
    }

    @Test
    void multiEnumIsCaseInsensitiveAndTrimmed() throws Exception {
        // 枚举解析统一 trim + 大写后比对白名单（对齐 MySQL ci 排序规则的宽松度）
        // PAID（n=15..44，30 行）+ SHIPPED（n=45..57，13 行）= 43 行
        mockMvc.perform(get("/api/v1/orders").param("order_status", " paid , SHIPPED "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(43));
    }

    @Test
    void pageBeyondRangeReturnsEmptyItems() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("page", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(57))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.total_pages").value(3));
    }

    @Test
    void rejectInvalidPageSize() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("page_size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.trace_id").isNotEmpty());
    }

    @Test
    void rejectNonNumericPage() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectInvalidEnumValue() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("order_status", "PAID,FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectInvalidPhone() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("customer_phone", "12345"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectNonNumericAmount() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("total_amount_min", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectReversedAmountRange() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("total_amount_min", "500")
                        .param("total_amount_max", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectReversedTimeRange() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("created_at_begin", "2026-02-01T00:00:00")
                        .param("created_at_end", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectInvalidTimeFormat() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("created_at_begin", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectTimeWithTimezoneSuffix() throws Exception {
        // toISOString() 的 UTC Z 后缀不符合时间契约（无时区本地格式），必须 400
        mockMvc.perform(get("/api/v1/orders").param("created_at_begin", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectSortFieldOutsideWhitelist() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("sort_by", "customer_name"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectInvalidSortDirection() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("sort_by", "id")
                        .param("sort_order", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectSortOrderWithoutSortBy() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("sort_order", "asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
