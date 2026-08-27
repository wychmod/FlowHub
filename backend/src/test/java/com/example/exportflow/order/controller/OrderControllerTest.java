package com.example.exportflow.order.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 订单接口最小验证：统一 Envelope、trace_id、分页与参数校验。 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
                .andExpect(jsonPath("$.data.items[0].order_no").value("PERF-000001"));
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
}
