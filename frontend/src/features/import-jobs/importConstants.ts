import type { ImportJobStatus } from '../../api/importApi';

/**
 * 导入任务状态 Tag 元信息（含 PARTIAL「部分成功」）。
 * 关键语义：PARTIAL 是成功终态、非失败，用 gold 警示色区分，且不提供重试。
 */
export const IMPORT_STATUS_META: Record<ImportJobStatus, { label: string; color: string }> = {
  PENDING: { label: '排队中', color: 'orange' },
  RUNNING: { label: '执行中', color: 'processing' },
  SUCCEEDED: { label: '全部成功', color: 'green' },
  PARTIAL: { label: '部分成功', color: 'gold' },
  FAILED: { label: '失败', color: 'red' },
  EXPIRED: { label: '已过期', color: 'default' },
};

/** 进行中状态集合（轮询降级判定用）：PENDING/RUNNING 仍会变，终态不轮询。 */
export const IMPORT_ACTIVE_STATUSES: readonly ImportJobStatus[] = ['PENDING', 'RUNNING'];
