package com.example.exportflow.export.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导出任务列表接口分页契约：默认值兜底、page/page_size 越界校验与 status 过滤（契约见 be-td.md 4.6）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportJobListControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanExportJobs() {
        // 该上下文与 ExportJobControllerTest 共享同一 H2，清表保证空表断言与执行顺序解耦
        jdbcTemplate.update("DELETE FROM export_jobs");
    }

    @Test
    void listJobsAppliesDefaultPagination() throws Exception {
        // page/page_size 未传时由紧凑构造器兜底 1/10 并在响应中回显
        mockMvc.perform(get("/api/v1/export-jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(10))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void rejectPageBelowMinimumWithFieldError() throws Exception {
        mockMvc.perform(get("/api/v1/export-jobs").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.field_errors['page']").isNotEmpty());
    }

    @Test
    void rejectPageSizeAboveMaximumWithFieldError() throws Exception {
        mockMvc.perform(get("/api/v1/export-jobs").param("page_size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.field_errors['pageSize']").isNotEmpty());
    }

    @Test
    void rejectUnknownStatusWithValidation() throws Exception {
        mockMvc.perform(get("/api/v1/export-jobs").param("status", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void listJobsAcceptsStatusFilterParam() throws Exception {
        // 空表 + 合法 status：服务层白名单接受，返回空分页
        mockMvc.perform(get("/api/v1/export-jobs").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }
}
