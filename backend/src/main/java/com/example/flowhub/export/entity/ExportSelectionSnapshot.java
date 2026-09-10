package com.example.flowhub.export.entity;

/**
 * 创建时范围快照：同一筛选条件下一次统计命中行数与最大订单 ID（高水位）。
 */
public record ExportSelectionSnapshot(long filterCount, long maxOrderIdAtCreate) {
}
