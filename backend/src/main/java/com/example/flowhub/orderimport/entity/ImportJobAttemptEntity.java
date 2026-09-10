package com.example.flowhub.orderimport.entity;

import java.time.LocalDateTime;

/** 导入任务执行尝试实体，映射 import_job_attempts 表（执行事实/审计记录）。 */
public record ImportJobAttemptEntity(
        Long id,
        Long jobId,
        Integer attemptNo,
        String status,          // RUNNING/SUCCEEDED/PARTIAL/FAILED
        String errorCode,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt) {
}