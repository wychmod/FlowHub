/** useExportEvents 连接状态机：SSE 通道的实时/降级/离线状态，只描述通道故障，不夸大任务失败。 */
export interface ExportEventConnectionState {
  mode: 'connecting' | 'sse' | 'polling' | 'offline';
  /** 连续连接失败次数（关键阈值 3，达到后切轮询降级）。 */
  consecutiveFailures: number;
  /** 最近一次有效事件（含心跳）发生时间戳，供 Badge tooltip 展示。 */
  lastEventAt?: number;
}