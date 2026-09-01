package com.example.exportflow.export.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/** 导出任务列表行（骨架版仅保留最小字段，完整字段见 be-td.md 4.6）。 */
public record ExportJobItemVO(
        @JsonProperty("job_id")
        Long jobId,

        @JsonProperty("job_no")
        String jobNo,

        String status,

        @JsonProperty("created_at")
        LocalDateTime createdAt) {
}
