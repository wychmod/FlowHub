/**
 * 导入任务连接态：与导出共用 EventConnectionState（两条独立 SSE 通道，状态语义相同）。
 * 保留领域别名，使 import-jobs 内部与页面 prop 引用清晰。
 */
import type { EventConnectionState } from '../exports/types';

export type ImportEventConnectionState = EventConnectionState;
export type { EventConnectionState };
