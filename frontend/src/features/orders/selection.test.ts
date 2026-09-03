import { describe, expect, it } from 'vitest';
import { applySelectionChange, MAX_SELECTED_ORDERS } from './selection';

describe('applySelectionChange', () => {
  it('上限内接受新集合并保序', () => {
    const result = applySelectionChange([1, 2], [3, 1, 2]);
    expect(result).toEqual({ ids: [3, 1, 2], blocked: false });
  });

  it('空数组（页内取消全部勾选）是合法变更', () => {
    const result = applySelectionChange([1, 2], []);
    expect(result).toEqual({ ids: [], blocked: false });
  });

  it('恰好达到上限 1000 时接受', () => {
    const full = Array.from({ length: MAX_SELECTED_ORDERS }, (_, i) => i + 1);
    const result = applySelectionChange([], full);
    expect(result.blocked).toBe(false);
    expect(result.ids).toHaveLength(MAX_SELECTED_ORDERS);
  });

  it('超过上限（1001）时整体拒绝并原样保留当前集合', () => {
    const current = [7, 8, 9];
    const next = Array.from({ length: MAX_SELECTED_ORDERS + 1 }, (_, i) => i + 1);
    const result = applySelectionChange(current, next);
    expect(result).toEqual({ ids: [7, 8, 9], blocked: true });
  });
});
