import { describe, expect, it } from 'vitest';
import { PAGE_META, pageLabel } from './layoutMeta';

describe('PAGE_META', () => {
  it('覆盖全部 PageKey，无遗漏与多余', () => {
    expect(Object.keys(PAGE_META).sort()).toEqual(['exports', 'orders']);
  });

  it('每个页面的 label 均非空', () => {
    for (const meta of Object.values(PAGE_META)) {
      expect(meta.label.trim().length).toBeGreaterThan(0);
    }
  });
});

describe('pageLabel', () => {
  it('命中已配置页面返回 label', () => {
    expect(pageLabel('orders')).toBe('订单列表');
    expect(pageLabel('exports')).toBe('导出任务');
  });

  it('未命中 key 回退 key 本身', () => {
    expect(pageLabel('unknown' as never)).toBe('unknown');
  });
});
