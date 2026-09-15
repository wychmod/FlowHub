package com.example.flowhub.export.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 创建导出任务成功的输出（HTTP 202 的 data）。
 */
public record ExportJobAcceptedVO(
        @JsonProperty("job_id")
        Long jobId,
        @JsonProperty("job_no")
        String jobNo,
        String status,
        @JsonProperty("total_rows")
        Long totalRows) {
}
