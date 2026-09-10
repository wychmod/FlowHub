/**
 * SSE 通道连接状态机（导出/导入共用）：只描述通道故障（实时/降级/离线），不夸大任务失败。
 * 导出与导入是两条独立 EventSource，但状态语义一致，故共享此类型与 ConnectionBadge。
 */
export interface EventConnectionState {
  mode: 'connecting' | 'sse' | 'polling' | 'offline';
  /** 连续连接失败次数（阈值 3，达到后切轮询降级）。 */
  consecutiveFailures: number;
  /** 最近一次有效事件（含心跳）时间戳，供 Badge tooltip 展示。 */
  lastEventAt?: number;
}

/** 导出任务连接态（历史命名，保留别名兼容既有引用）。 */
export type ExportEventConnectionState = EventConnectionState;
