import type { PageKey } from './AppLayout';

/** 页面元信息：Header 动态页题的事实源，AppLayout 菜单文案亦由此派生。 */
export const PAGE_META: Record<PageKey, { label: string }> = {
  orders: { label: '订单列表' },
  exports: { label: '导出任务' },
};

/** 按页面 key 取页题，未命中回退 key 本身（防漏配）。 */
export function pageLabel(key: PageKey): string {
  return PAGE_META[key]?.label ?? key;
}
