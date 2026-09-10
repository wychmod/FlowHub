package com.example.flowhub.orderimport.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导入任务列表行（docs/order-import-design.md §5.3 完整字段）。
 * <p>
 * 字段与 SSE 事件 payload（ImportJobEventPayload）对齐，使前端收到事件后能就地更新缓存行；
 * progress_percent / error_report_available / error_summary 为派生字段，由 Service 层按状态与数据库计算。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportJobItemVO(
        @JsonProperty("job_id")
        Long jobId,

        @JsonProperty("job_no")
        String jobNo,

        String status,

        @JsonProperty("job_version")
        Long version,

        @JsonProperty("total_rows")
        Integer totalRows,

        @JsonProperty("processed_rows")
        Integer processedRows,

        @JsonProperty("succeeded_rows")
        Integer succeededRows,

        @JsonProperty("skipped_rows")
        Integer skippedRows,

        @JsonProperty("progress_percent")
        Integer progressPercent,

        @JsonProperty("error_report_available")
        Boolean errorReportAvailable,

        @JsonProperty("error_summary")
        List<ErrorSummaryItem> errorSummary,

        @JsonProperty("file_name")
        String fileName,

        @JsonProperty("file_size_bytes")
        Long fileSizeBytes,

        @JsonProperty("error_code")
        String errorCode,

        @JsonProperty("error_message")
        String errorMessage,

        @JsonProperty("created_at")
        LocalDateTime createdAt,

        @JsonProperty("finished_at")
        LocalDateTime finishedAt,

        @JsonProperty("expired_at")
        LocalDateTime expiredAt) {

    /** 错误分类摘要项（[{reason, count}]，取 Top N）。 */
    public record ErrorSummaryItem(@JsonProperty("reason") String reason, @JsonProperty("count") int count) {
    }
}