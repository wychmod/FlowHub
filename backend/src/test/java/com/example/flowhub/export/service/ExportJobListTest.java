package com.example.flowhub.export.service;

import com.example.flowhub.common.web.error.BusinessException;
import com.example.flowhub.export.dto.ExportJobPageResp;
import com.example.flowhub.export.vo.ExportJobItemVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 导出任务列表接口集成验证（P-4 真实化）：分页查询、创建时间倒序、派生字段
 * （progress_percent/downloadable）按状态与时间正确计算、空表返回空列表、线上 JSON 字段名契约。
 * <p>真实 Mapper + H2 执行真 SQL，直插 export_jobs 数据绕过创建链路。
 */
@SpringBootTest
class ExportJobListTest {

    @Autowired
    private ExportJobService exportJobService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM export_job_attempts");
        jdbcTemplate.update("DELETE FROM export_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    // ==================== 空表与基础分页 ====================

    @Test
    void emptyTableReturnsEmptyListAndZeroTotal() {
        ExportJobPageResp resp = exportJobService.listJobs(1, 10, null);

        assertThat(resp.items()).isEmpty();
        assertThat(resp.total()).isZero();
        assertThat(resp.page()).isEqualTo(1);
        assertThat(resp.pageSize()).isEqualTo(10);
    }

    @Test
    void pagesJobsByCreatedAtDescending() {
        insertJob("RUNNING", "A.xlsx", null);
        insertJob("SUCCEEDED", "B.xlsx", LocalDateTime.now().plusHours(1));
        insertJob("FAILED", "C.xlsx", null);

        ExportJobPageResp resp = exportJobService.listJobs(1, 10, null);

        assertThat(resp.total()).isEqualTo(3);
        // 创建时间倒序：后插入的 C 应排最前；id 倒序稳定排序
        assertThat(resp.items()).extracting(ExportJobItemVO::jobNo)
                .containsExactly("C.xlsx", "B.xlsx", "A.xlsx");
        assertThat(resp.items()).hasSize(3);
    }

    @Test
    void paginationLimitsAndOffsetsCorrectly() {
        insertJob("RUNNING", "A.xlsx", null);
        insertJob("RUNNING", "B.xlsx", null);
        insertJob("RUNNING", "C.xlsx", null);

        ExportJobPageResp page1 = exportJobService.listJobs(1, 2, null);
        ExportJobPageResp page2 = exportJobService.listJobs(2, 2, null);

        assertThat(page1.total()).isEqualTo(3);
        assertThat(page1.items()).hasSize(2);
        assertThat(page2.total()).isEqualTo(3);
        assertThat(page2.items()).hasSize(1);
    }

    // ==================== 状态筛选 ====================

    @Test
    void filtersByStatusWithCaseInsensitiveName() {
        insertJob("RUNNING", "A.xlsx", null);
        insertJob("SUCCEEDED", "B.xlsx", LocalDateTime.now().plusHours(1));
        insertJob("FAILED", "C.xlsx", null);

        ExportJobPageResp running = exportJobService.listJobs(1, 10, "running");
        assertThat(running.total()).isEqualTo(1);
        assertThat(running.items()).extracting(ExportJobItemVO::status).containsExactly("RUNNING");

        // 只该状态命中，其余被过滤
        assertThat(exportJobService.listJobs(1, 10, "SUCCEEDED").total()).isEqualTo(1);
        assertThat(exportJobService.listJobs(1, 10, "FAILED").total()).isEqualTo(1);
        // 未传 status = 不过滤
        assertThat(exportJobService.listJobs(1, 10, null).total()).isEqualTo(3);
    }

    @Test
    void rejectsUnknownStatusFilter() {
        assertThatThrownBy(() -> exportJobService.listJobs(1, 10, "FOO"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("status");
    }

    // ==================== 派生字段计算 ====================

    @Test
    void derivesProgressPercentForRunningAndSucceeded() {
        long running = insertJob("RUNNING", "A.xlsx", null);
        jdbcTemplate.update("UPDATE export_jobs SET processed_rows = 6000, filter_count = 12000 WHERE id = ?", running);
        long succeeded = insertJob("SUCCEEDED", "B.xlsx", LocalDateTime.now().plusHours(1));
        jdbcTemplate.update("UPDATE export_jobs SET processed_rows = 12000, filter_count = 12000 WHERE id = ?", succeeded);

        List<ExportJobItemVO> items = exportJobService.listJobs(1, 10, null).items();

        assertThat(items.stream().filter(it -> it.status().equals("RUNNING")).findFirst().orElseThrow().progressPercent())
                .isEqualTo(50); // 6000/12000，RUNNING 封顶 99
        assertThat(items.stream().filter(it -> it.status().equals("SUCCEEDED")).findFirst().orElseThrow().progressPercent())
                .isEqualTo(100); // SUCCEEDED 固定 100
    }

    @Test
    void derivesDownloadableOnlyWhenSucceededAndNotExpired() {
        long succeeded = insertJob("SUCCEEDED", "A.xlsx", LocalDateTime.now().plusHours(1));
        long expired = insertJob("SUCCEEDED", "B.xlsx", LocalDateTime.now().minusHours(1));
        long running = insertJob("RUNNING", "C.xlsx", null);

        List<ExportJobItemVO> items = exportJobService.listJobs(1, 10, null).items();

        assertThat(items.stream().filter(it -> it.jobId().equals(succeeded)).findFirst().orElseThrow().downloadable())
                .isTrue();
        assertThat(items.stream().filter(it -> it.jobId().equals(expired)).findFirst().orElseThrow().downloadable())
                .isFalse();
        assertThat(items.stream().filter(it -> it.jobId().equals(running)).findFirst().orElseThrow().downloadable())
                .isFalse();
    }

    @Test
    void exposesVersionProgressAndErrorFieldsForSseLocalUpdate() {
        long failed = insertJob("FAILED", "A.xlsx", null);
        jdbcTemplate.update("UPDATE export_jobs SET version = 7, error_code = 'FILE_GENERATION_FAILED',"
                + " error_message = 'generation error' WHERE id = ?", failed);

        ExportJobItemVO item = exportJobService.listJobs(1, 10, null).items().stream()
                .filter(it -> it.jobId().equals(failed)).findFirst().orElseThrow();

        assertThat(item.version()).isEqualTo(7);
        assertThat(item.processedRows()).isZero();
        assertThat(item.errorCode()).isEqualTo("FILE_GENERATION_FAILED");
        assertThat(item.errorMessage()).isEqualTo("generation error");
    }

    // ==================== 线上 JSON 契约 ====================

    @Test
    void serializesWireContractFieldNamesForFrontendAlignment() throws Exception {
        insertJob("RUNNING", "A.xlsx", null);
        ExportJobItemVO item = exportJobService.listJobs(1, 10, null).items().get(0);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(item));

        // 字段名对齐 SSE payload 与前端类型：version 必须输出 job_version（版本栅栏依据），file_name 供列表展示列
        assertThat(json.has("job_version")).isTrue();
        assertThat(json.has("version")).isFalse();
        assertThat(json.path("file_name").asText()).isEqualTo("A.xlsx");
    }

    // ==================== 数据准备 ====================

    /** 直插指定状态任务（jobNo 用厂名区分排序）。 */
    private long insertJob(String status, String jobNo, LocalDateTime expiredAt) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO export_jobs (job_no, status, max_order_id_at_create, idempotency_key, request_hash,
                    filter_count, filter_snapshot, selected_order_ids, selected_columns, requested_file_name,
                    expired_at, created_at, updated_at)
                VALUES (?, ?, 0, ?, 'h', 0, '{}', '[]', '["order_no"]', ?,
                    ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, jobNo, status, "idem-" + unique, jobNo,
                expiredAt == null ? null : Timestamp.valueOf(expiredAt));
        return jdbcTemplate.queryForObject("SELECT id FROM export_jobs WHERE idempotency_key = ?", Long.class,
                "idem-" + unique);
    }
}