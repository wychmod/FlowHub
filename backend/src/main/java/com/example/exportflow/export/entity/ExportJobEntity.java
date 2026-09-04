package com.example.exportflow.export.entity;

import java.time.LocalDateTime;

/**
 * 导出任务实体，映射 export_jobs 表创建态列。
 * <p>
 * 执行态列（processed_rows/started_at 等）由后续执行器迭代补充；version/执行态列在
 * INSERT 语句中省略，走数据库默认值。
 */
public record ExportJobEntity(
        Long id,
        String jobNo,
        String status,
        Long maxOrderIdAtCreate,
        String idempotencyKey,
        String requestHash,
        Long filterCount,
        String filterSnapshot,
        String selectedOrderIds,
        String selectedColumns,
        String requestedFileName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
