package com.example.flowhub.export.event;

import com.example.flowhub.export.entity.ExportJobEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * SSE 任务事件 payload（契约见 docs/export-sse-design.md 3.2 / be-td.md 4.10，字段统一 snake_case）。
 * <p>
 * 字段存在性即协议：进度/成功事件不含 error 字段（NON_NULL 省略，前端保留缓存值），
 * 失败终态显式携带 error_code/error_message。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportJobEventPayload(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("job_version") Long jobVersion,
        @JsonProperty("status") String status,
        @JsonProperty("processed_rows") Long processedRows,
        @JsonProperty("total_rows") Long totalRows,
        @JsonProperty("progress_percent") Integer progressPercent,
        @JsonProperty("downloadable") Boolean downloadable,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("occurred_at") LocalDateTime occurredAt) {

    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int RUNNING_MAX_PERCENT = 99;

    /** 按重读的 Job 事实构造负载：状态决定 downloadable/percent/error 字段形态。 */
    public static ExportJobEventPayload from(ExportJobEntity job) {
        boolean succeeded = STATUS_SUCCEEDED.equals(job.status());
        boolean failed = STATUS_FAILED.equals(job.status());
        long processed = job.processedRows() == null ? 0 : job.processedRows();
        long total = job.filterCount() == null ? 0 : job.filterCount();
        return new ExportJobEventPayload(
                String.valueOf(job.id()),
                job.version(),
                job.status(),
                job.processedRows(),
                job.filterCount(),
                progressPercent(job.status(), processed, total),
                succeeded,
                failed ? job.errorCode() : null,
                failed ? job.errorMessage() : null,
                LocalDateTime.now());
    }

    /** 进度百分比：SUCCEEDED 才到 100，其余封顶 99（用户语义规则：100 = 文件可下载）；Redis 投影复用。 */
    public static int progressPercent(String status, long processedRows, long totalRows) {
        if (STATUS_SUCCEEDED.equals(status)) {
            return 100;
        }
        if (totalRows <= 0) {
            return 0;
        }
        return (int) Math.min(RUNNING_MAX_PERCENT, processedRows * 100 / totalRows);
    }
}
