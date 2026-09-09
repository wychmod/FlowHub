package com.example.exportflow.export.entity;

import java.time.LocalDateTime;

/**
 * 导出任务实体，映射 export_jobs 表创建态与执行反馈列。
 * <p>
 * INSERT 语句仅写创建态列（version/processed_rows/error_* 走数据库默认值或 NULL），
 * 执行反馈列由 selectById 等查询读出，供执行器与 SSE 重读事实源使用。
 */
public record ExportJobEntity(
        Long id,
        String jobNo,
        String status,

        Long maxOrderIdAtCreate,      // 创建时命中范围最大订单 ID：执行期 Keyset 上界
        String idempotencyKey,        // 幂等键（唯一约束兜底并发创建）
        String requestHash,           // 规范化请求 SHA-256 指纹（幂等判重）
        Long filterCount,             // 创建时命中预计行数（进度分母）
        String filterSnapshot,        // OrderCriteria 序列化 JSON（执行端直存直取重建取数条件）
        String selectedOrderIds,      // 勾选模式 ID 集合 JSON（FILTER 模式为空数组）
        String selectedColumns,       // 导出列白名单序 JSON（Excel 表头）
        String requestedFileName,     // 清洗后的显示文件名

        // ==== 执行反馈（执行期推进；INSERT 省略，走数据库默认值或 NULL）====
        Long version,                 // 状态变更序号：SSE 事件 id = jobId:version（前端版本栅栏）
        Long processedRows,           // 已成功推进的行数（条件守卫单调递增）
        Integer attemptCount,         // 已开始的真实执行次数（抢占时递增，重试上限判断依据）
        String errorCode,             // 失败错误码（如 FILE_GENERATION_FAILED）
        String errorMessage,          // 失败原因（截断至列宽 500）
        String filePath,              // 成功产物相对路径（发布协议登记，下载/清理经受控解析）
        Long fileSizeBytes,           // 成功产物字节数（展示与审计证据）
        LocalDateTime expiredAt,      // 允许下载截止时间（保留期后清理并推进 EXPIRED）
        LocalDateTime finishedAt,     // 完成时间（FAILED/SUCCEEDED 终态落库，列表展示）

        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
