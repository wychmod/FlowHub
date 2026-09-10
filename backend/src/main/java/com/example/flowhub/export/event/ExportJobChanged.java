package com.example.flowhub.export.event;

/**
 * 导出任务变化应用事件（单体进程内传纸条，非分布式消息）：仅携带 jobId，监听方重读事实源后再广播。
 */
public record ExportJobChanged(long jobId) {
}
