package com.example.flowhub.orderimport.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 上传受理成功输出（HTTP 202 的 data）。
 */
public record ImportJobAcceptedVO(
        @JsonProperty("job_id")
        Long jobId,
        @JsonProperty("job_no")
        String jobNo,
        String status,
        @JsonProperty("total_rows")
        Integer totalRows,
        @JsonProperty("file_name")
        String fileName,
        @JsonProperty("created_at")
        java.time.LocalDateTime createdAt) {
}