package com.example.flowhub.orderimport.entity;

import java.time.LocalDateTime;

/**
 * 订单导入任务实体，映射 import_jobs 表创建态与执行反馈列。
 * <p>
 * INSERT 仅写创建态列（version/执行态列走数据库默认值或 NULL），执行反馈列由 selectById 等查询读出。
 */
public record ImportJobEntity(
        Long id,
        String jobNo,
        String status,

        // ==== 上传元信息 ====
        String fileName,            // 原始上传文件名（含 .xlsx）
        String filePath,            // 上传原件相对路径（受控 importRoot）
        Integer totalRows,          // 数据行总数（受理时结构级扫描确定）
        Integer processedRows,      // 已处理行数（进度推进）
        Integer succeededRows,      // 成功入库行数
        Integer skippedRows,        // 跳过行数（错误行）
        String errorReportPath,     // PARTIAL 时错误报告 xlsx 相对路径
        String errorSummary,        // 错误分类 Top N JSON [{reason,count}]

        // ==== 执行反馈（执行期推进；INSERT 省略，走数据库默认值或 NULL）====
        Long version,               // 状态变更序号：SSE 事件 id = jobId:version（前端版本栅栏）
        Integer attemptCount,       // 已开始的真实执行次数（抢占时递增）
        LocalDateTime startedAt,
        LocalDateTime lastHeartbeatAt,
        LocalDateTime leaseExpiresAt,
        LocalDateTime finishedAt,
        LocalDateTime expiredAt,    // 保留期截止（期满清理 → EXPIRED）
        String errorCode,
        String errorMessage,

        LocalDateTime createdAt) {
}