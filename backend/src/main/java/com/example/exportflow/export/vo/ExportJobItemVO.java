package com.example.exportflow.export.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * 导出任务列表行（be-td.md 4.6 完整字段）。
 * <p>
 * 字段与 SSE 事件 payload（ExportJobEventPayload）对齐，使前端收到事件后能就地更新缓存行；
 * progress_percent/downloadable 为派生字段，由 Service 层按状态与时间计算；
 * file_name 为列表展示字段（创建时请求的文件名，SSE 事件不携带）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportJobItemVO(
        @JsonProperty("job_id")
        Long jobId,

        @JsonProperty("job_no")
        String jobNo,

        String status,

        @JsonProperty("job_version")
        Long version,

        @JsonProperty("processed_rows")
        Long processedRows,

        @JsonProperty("total_rows")
        Long totalRows,

        @JsonProperty("progress_percent")
        Integer progressPercent,

        Boolean downloadable,

        @JsonProperty("file_size_bytes")
        Long fileSizeBytes,

        @JsonProperty("error_code")
        String errorCode,

        @JsonProperty("error_message")
        String errorMessage,

        @JsonProperty("file_name")
        String fileName,

        @JsonProperty("created_at")
        LocalDateTime createdAt,

        @JsonProperty("finished_at")
        LocalDateTime finishedAt,

        @JsonProperty("expired_at")
        LocalDateTime expiredAt) {
}
