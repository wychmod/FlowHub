package com.example.exportflow.export.service;

import com.example.exportflow.common.web.error.BusinessException;
import com.example.exportflow.export.command.CreateExportJobCommand;
import com.example.exportflow.export.dto.CreateExportJobRequest;
import com.example.exportflow.export.dto.ExportSelectionRequest;
import com.example.exportflow.export.error.ExportErrorCode;
import com.example.exportflow.export.vo.ExportJobAcceptedVO;
import com.example.exportflow.order.mapper.TestOrderDataSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发创建幂等验证（数据库唯一约束裁决）：同 Key 的两个请求同时到达时，
 * 只有一个 INSERT 能成功，败者降级——同请求复用原任务、不同请求返回 409 冲突，
 * Job 与 Outbox 均只留一条（见 export-http-boundary-plan.md 6.3 的并发兜底路径）。
 */
@SpringBootTest
class ExportJobIdempotencyConcurrencyTest {

    @Autowired
    private ExportJobService exportJobService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedAndClean() {
        TestOrderDataSeeder.seed(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM export_jobs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void concurrentSameKeySameRequestCreatesExactlyOneJob() throws Exception {
        CreateExportJobCommand command = selectedIdsCommand("order_no", "total_amount");

        List<Object> outcomes = submitConcurrently(command, command, "key-concurrent-same");

        // 两方都解析到同一任务（败者复用胜者），无冲突异常
        assertThat(outcomes).allSatisfy(o -> assertThat(o)
                .as("并发同请求不应产生冲突异常，实际：%s", o)
                .isInstanceOf(ExportJobAcceptedVO.class));
        assertThat(((ExportJobAcceptedVO) outcomes.get(0)).jobId())
                .isEqualTo(((ExportJobAcceptedVO) outcomes.get(1)).jobId());
        // 数据库唯一约束裁决：任务与 Outbox 均只一条
        assertThat(jobCount()).isEqualTo(1L);
        assertThat(outboxCount()).isEqualTo(1L);
    }

    @Test
    void concurrentSameKeyDifferentPayloadYieldsSingleWinnerAndOneConflict() throws Exception {
        CreateExportJobCommand first = selectedIdsCommand("order_no", "total_amount");
        CreateExportJobCommand second = selectedIdsCommand("order_no", "currency");

        List<Object> outcomes = submitConcurrently(first, second, "key-concurrent-diff");

        long successes = outcomes.stream().filter(ExportJobAcceptedVO.class::isInstance).count();
        long conflicts = outcomes.stream()
                .filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .filter(ex -> ex.getErrorCode() == ExportErrorCode.IDEMPOTENCY_CONFLICT)
                .count();
        // 恰好一个创建成功、一个 409 冲突；Outbox 只随胜者写一条
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(jobCount()).isEqualTo(1L);
        assertThat(outboxCount()).isEqualTo(1L);
    }

    /** 两线程以栅栏对齐后同 Key 并发创建，收集结果（成功受理 VO 或异常）。 */
    private List<Object> submitConcurrently(CreateExportJobCommand first, CreateExportJobCommand second,
                                            String idempotencyKey) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<Object>> futures = List.of(first, second).stream()
                    .map(command -> pool.submit((Callable<Object>) () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            return exportJobService.createJob(command, idempotencyKey);
                        } catch (Exception ex) {
                            return ex;
                        }
                    }))
                    .toList();
            return List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 勾选模式创建命令（规范化经 toCommand），命中种子数据 PENDING 的 id=1..3。 */
    private static CreateExportJobCommand selectedIdsCommand(String... columns) {
        return new CreateExportJobRequest(
                new ExportSelectionRequest("SELECTED_IDS", List.of(1L, 2L, 3L), null, null),
                List.of(columns),
                null).toCommand();
    }

    private Long jobCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM export_jobs", Long.class);
    }

    private Long outboxCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class);
    }
}