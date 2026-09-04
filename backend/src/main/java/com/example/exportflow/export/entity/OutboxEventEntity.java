package com.example.exportflow.export.entity;

import java.time.LocalDateTime;

/**
 * 事务发件箱事件实体，映射 outbox_events 表。
 * <p>
 * published_at 不在本实体内：未发布即 NULL，发布由分发器后续更新。
 */
public record OutboxEventEntity(
        Long id,                    // 自增主键（INSERT 不回填，按业务唯一键取回）
        String aggregateType,       // 聚合类型，当前恒为 EXPORT_JOB
        Long aggregateId,           // 聚合根 ID，即 export_jobs.job_id
        String eventType,           // 事件类型，当前恒为 EXPORT_JOB_CREATED
        String payload,             // 事件载荷 JSON（snake_case 契约：job_id/job_no/request_snapshot/columns/file_name/trace_id）
        String traceId,             // 创建请求的 trace_id，消费侧延续链路
        LocalDateTime createdAt) {  // 事件创建时间，分发器按它轮询分发
}
