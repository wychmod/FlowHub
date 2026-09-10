package com.example.flowhub.orderimport.event;

import com.example.flowhub.orderimport.entity.ImportJobEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * SSE 导入任务事件 payload（契约见 docs/order-import-design.md §5.6，字段统一 snake_case）。
 * <p>
 * 字段存在性即协议：进度/成功/部分事件不含 error 字段（NON_NULL 省略），失败终态显式携带
 * error_code/error_message；succeeded_rows/skipped_rows 由列表与事件共同呈现，前端可乐观更新。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportJobEventPayload(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("job_version") Long jobVersion,
        @JsonProperty("status") String status,
        @JsonProperty("processed_rows") Long processedRows,
        @JsonProperty("total_rows") Long totalRows,
        @JsonProperty("succeeded_rows") Long succeededRows,
        @JsonProperty("skipped_rows") Long skippedRows,
        @JsonProperty("progress_percent") Integer progressPercent,
        @JsonProperty("error_report_available") Boolean errorReportAvailable,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("occurred_at") LocalDateTime occurredAt) {

    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_FAILED = "FAILED";
    private static final int RUNNING_MAX_PERCENT = 99;

    /** 按重读的 Job 事实构造负载：状态决定 percent/error_report_available/error 字段形态。 */
    public static ImportJobEventPayload from(ImportJobEntity job) {
        boolean failed = STATUS_FAILED.equals(job.status());
        long processed = nullable(job.processedRows());
        long total = nullable(job.totalRows());
        return new ImportJobEventPayload(
                String.valueOf(job.id()),
                job.version(),
                job.status(),
                processed,
                total,
                nullable(job.succeededRows()),
                nullable(job.skippedRows()),
                progressPercent(job.status(), processed, total),
                errorReportAvailable(job),
                failed ? job.errorCode() : null,
                failed ? job.errorMessage() : null,
                LocalDateTime.now());
    }

    /** Integer → long 空值防御（count 列可空）。 */
    private static long nullable(Integer value) {
        return value == null ? 0 : value.longValue();
    }

    /** {@code error_report_available}：仅 PARTIAL 且登记了错误报告路径时对外可下载。 */
    public static boolean errorReportAvailable(ImportJobEntity job) {
        return STATUS_PARTIAL.equals(job.status())
                && job.errorReportPath() != null && !job.errorReportPath().isBlank();
    }

    /** 进度百分比：SUCCEEDED/PARTIAL 才走 100，其余封顶 99（用户语义规则：100 = 任务结束）。 */
    public static int progressPercent(String status, long processedRows, long totalRows) {
        if (STATUS_SUCCEEDED.equals(status) || STATUS_PARTIAL.equals(status)) {
            return 100;
        }
        if (totalRows <= 0) {
            return 0;
        }
        return (int) Math.min(RUNNING_MAX_PERCENT, processedRows * 100 / totalRows);
    }
}