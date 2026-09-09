import type { Dayjs } from 'dayjs';
import type { CreateExportJobPayload, ExportFilterSnapshot } from '../../api/exportApi';
import { formatLocalDateTime } from './api';
import type {
  Currency,
  OrderSortDirection,
  OrderSortField,
  OrderStatus,
  SalesChannel,
} from './api';

/** 筛选草稿：antd Form 持有的表单值形态（时间保持 Dayjs，序列化延迟到出口）。 */
export interface OrderFilterFormValues {
  orderNo?: string;
  customerName?: string;
  customerPhone?: string;
  orderStatus?: OrderStatus[];
  salesChannel?: SalesChannel[];
  currency?: Currency[];
  totalAmountMin?: number | null;
  totalAmountMax?: number | null;
  /** RangePicker 值：[begin, end] 或 null。 */
  createdAtRange?: [Dayjs | null, Dayjs | null] | null;
}

/** 已提交筛选条件：点「查询」后生效，驱动列表请求与筛选导出范围；不含分页与排序。 */
export interface SubmittedFilter {
  orderStatus?: OrderStatus[];
  salesChannel?: SalesChannel[];
  currency?: Currency[];
  orderNo?: string;
  customerName?: string;
  customerPhone?: string;
  totalAmountMin?: number;
  totalAmountMax?: number;
  createdAtBegin?: Dayjs;
  createdAtEnd?: Dayjs;
}

/** 已提交排序：永远有值（初始与「取消排序」都回到默认 created_at desc）。 */
export interface SubmittedSort {
  field: OrderSortField;
  direction: OrderSortDirection;
}

/** 字符串 trim，空白串折叠为 undefined（对齐后端「空 = 未传」契约）。 */
function trimToUndefined(value?: string): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

/**
 * 草稿 → 已提交条件：字符串 trim、空串/空数组折叠为 undefined，
 * 时间区间按端拆分（单端合法），金额 null 折叠。
 */
export function formValuesToFilter(values: OrderFilterFormValues): SubmittedFilter {
  const filter: SubmittedFilter = {};
  const orderNo = trimToUndefined(values.orderNo);
  if (orderNo != null) filter.orderNo = orderNo;
  const customerName = trimToUndefined(values.customerName);
  if (customerName != null) filter.customerName = customerName;
  const customerPhone = trimToUndefined(values.customerPhone);
  if (customerPhone != null) filter.customerPhone = customerPhone;
  if (values.orderStatus != null && values.orderStatus.length > 0) {
    filter.orderStatus = values.orderStatus;
  }
  if (values.salesChannel != null && values.salesChannel.length > 0) {
    filter.salesChannel = values.salesChannel;
  }
  if (values.currency != null && values.currency.length > 0) {
    filter.currency = values.currency;
  }
  if (values.totalAmountMin != null) filter.totalAmountMin = values.totalAmountMin;
  if (values.totalAmountMax != null) filter.totalAmountMax = values.totalAmountMax;
  const [createdAtBegin, createdAtEnd] = values.createdAtRange ?? [null, null];
  if (createdAtBegin != null) filter.createdAtBegin = createdAtBegin;
  if (createdAtEnd != null) filter.createdAtEnd = createdAtEnd;
  return filter;
}

/**
 * 已提交条件 + 排序 → 筛选导出快照：驼峰转 snake_case、多值为数组、
 * 时间经 formatLocalDateTime 输出本地格式字符串；未设置字段不出现，排序两字段恒包含。
 */
export function buildFilterSnapshot(filter: SubmittedFilter, sort: SubmittedSort): ExportFilterSnapshot {
  const snapshot: ExportFilterSnapshot = {
    sort_by: sort.field,
    sort_order: sort.direction,
  };
  if (filter.orderStatus != null && filter.orderStatus.length > 0) snapshot.order_status = filter.orderStatus;
  if (filter.salesChannel != null && filter.salesChannel.length > 0) {
    snapshot.sales_channel = filter.salesChannel;
  }
  if (filter.currency != null && filter.currency.length > 0) snapshot.currency = filter.currency;
  const orderNo = trimToUndefined(filter.orderNo);
  if (orderNo != null) snapshot.order_no = orderNo;
  const customerName = trimToUndefined(filter.customerName);
  if (customerName != null) snapshot.customer_name = customerName;
  const customerPhone = trimToUndefined(filter.customerPhone);
  if (customerPhone != null) snapshot.customer_phone = customerPhone;
  if (filter.totalAmountMin != null) snapshot.total_amount_min = filter.totalAmountMin;
  if (filter.totalAmountMax != null) snapshot.total_amount_max = filter.totalAmountMax;
  if (filter.createdAtBegin != null) snapshot.created_at_begin = formatLocalDateTime(filter.createdAtBegin);
  if (filter.createdAtEnd != null) snapshot.created_at_end = formatLocalDateTime(filter.createdAtEnd);
  return snapshot;
}

/**
 * FILTER 模式 selection 构造：可附带反选排除 ID（后端 selection.excluded_order_ids 契约，最多 1000）。
 * 空列表折叠为未传（对齐「空 = 不出现」契约），数组拷贝隔离外部引用。
 */
export function buildFilterSelection(
  filter: SubmittedFilter,
  sort: SubmittedSort,
  excludedIds?: readonly number[],
): CreateExportJobPayload['selection'] {
  const selection: {
    mode: 'FILTER';
    filter: ExportFilterSnapshot;
    excluded_order_ids?: number[];
  } = { mode: 'FILTER', filter: buildFilterSnapshot(filter, sort) };
  if (excludedIds != null && excludedIds.length > 0) {
    selection.excluded_order_ids = [...excludedIds];
  }
  return selection;
}
