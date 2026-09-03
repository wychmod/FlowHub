import type { Currency, OrderStatus, SalesChannel } from './api';
import type { SubmittedSort } from './filters';

/** 枚举下拉选项：value 为后端枚举名，label 为中文展示。 */
export interface EnumOption<V extends string> {
  value: V;
  label: string;
}

/** 按枚举值取中文 label（表格单元格渲染用），未命中回退原值。 */
export function enumLabel<V extends string>(options: readonly EnumOption<V>[], value: V): string {
  return options.find((option) => option.value === value)?.label ?? value;
}

/** 订单状态选项（PRD 9.1）。 */
export const ORDER_STATUS_OPTIONS: EnumOption<OrderStatus>[] = [
  { value: 'PENDING', label: '待支付' },
  { value: 'PAID', label: '已支付' },
  { value: 'SHIPPED', label: '已发货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELED', label: '已取消' },
];

/** 销售渠道选项（PRD 9.2）。 */
export const SALES_CHANNEL_OPTIONS: EnumOption<SalesChannel>[] = [
  { value: 'WEB', label: 'Web 商城' },
  { value: 'APP', label: '移动 App' },
  { value: 'STORE', label: '线下门店' },
  { value: 'PARTNER', label: '合作渠道' },
];

/** 币种选项。 */
export const CURRENCY_OPTIONS: EnumOption<Currency>[] = [
  { value: 'CNY', label: '人民币' },
  { value: 'USD', label: '美元' },
  { value: 'EUR', label: '欧元' },
  { value: 'HKD', label: '港币' },
];

/** 订单状态 Tag 颜色。 */
export const STATUS_COLOR: Record<OrderStatus, string> = {
  PENDING: 'orange',
  PAID: 'blue',
  SHIPPED: 'cyan',
  COMPLETED: 'green',
  CANCELED: 'red',
};

/** 默认排序：下单时间降序（与后端缺省行为一致，「取消排序」也回到此值）。 */
export const DEFAULT_SORT: SubmittedSort = { field: 'created_at', direction: 'desc' };

/** 默认每页条数。 */
export const DEFAULT_PAGE_SIZE = 10;

/** 每页条数可选项。 */
export const PAGE_SIZE_OPTIONS = [10, 20, 30, 50] as const;
