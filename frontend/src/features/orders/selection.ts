/** 已选订单数量上限（勾选 ID 上限 1000 个）。 */
export const MAX_SELECTED_ORDERS = 1000;

/**
 * 勾选集合迁移：antd onChange 给出变更后的完整 key 列表。
 * 超上限时整体拒绝（保留原集合），blocked=true 由调用方提示「最多选择 1000 条」。
 */
export function applySelectionChange(
  current: readonly number[],
  next: readonly number[],
): { ids: number[]; blocked: boolean } {
  if (next.length > MAX_SELECTED_ORDERS) {
    return { ids: [...current], blocked: true };
  }
  return { ids: [...next], blocked: false };
}
