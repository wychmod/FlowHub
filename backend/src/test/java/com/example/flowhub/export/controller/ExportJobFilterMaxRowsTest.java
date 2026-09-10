package com.example.flowhub.export.controller;

import com.example.flowhub.order.mapper.TestOrderDataSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 筛选命中数配置上限的独立上下文验证（export.filter-max-rows 调低后超过即拒绝）。
 */
@SpringBootTest(properties = "export.filter-max-rows=10")
@AutoConfigureMockMvc
class ExportJobFilterMaxRowsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedDeterministicOrders() {
        TestOrderDataSeeder.seed(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM export_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void rejectFilterRowsBeyondConfiguredLimit() throws Exception {
        // PAID 在种子数据中为 30 行，超过调低后的上限 10
        mockMvc.perform(post("/api/v1/export-jobs")
                        .header("Idempotency-Key", "key-over-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selection":{"mode":"FILTER",
                                  "filter":{"order_status":["PAID"]}},"columns":["order_no"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPORT_FILTER_TOO_MANY_ROWS"));
    }
}
