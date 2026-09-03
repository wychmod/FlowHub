import type { Dayjs } from 'dayjs';
import { requestJson } from '../../api/http';

/** 订单状态（字符串字面量联合类型），取值对齐 be-td.md 4.3：PENDING/PAID/SHIPPED/COMPLETED/CANCELED。 */
export type OrderStatus = 'PENDING' | 'PAID' | 'SHIPPED' | 'COMPLETED' | 'CANCELED';

/** 销售渠道，be-td.md 4.3：WEB/APP/STORE/PARTNER。 */
export type SalesChannel = 'WEB' | 'APP' | 'STORE' | 'PARTNER';

/** 币种，对齐 seed 数据取值：CNY/USD/EUR/HKD。 */
export type Currency = 'CNY' | 'USD' | 'EUR' | 'HKD';

/** 排序字段白名单（对齐后端 SortField 枚举，白名单外的值后端一律 400）。 */
export type OrderSortField = 'created_at' | 'total_amount' | 'order_no' | 'id';

/** 排序方向。 */
export type OrderSortDirection = 'asc' | 'desc';

/** 订单列表行，字段与后端 OrderItemVO（snake_case）一致，见 be-td.md 4.3；可空列为 string | null。 */
export interface OrderItem {
  id: number;
  order_no: string;
  order_status: OrderStatus;
  sales_channel: SalesChannel;
  customer_name: string | null;
  customer_phone: string | null;
  /** 收货省份，仅展示不参与筛选。 */
  shipping_province: string | null;
  total_amount: number;
  currency: Currency;
  created_at: string;
}

/** 订单分页响应，字段与后端 OrderPageResp 一致；sort_by/sort_order 为实际生效排序的回显（默认值展开，如 "created_at"/"desc"）。 */
export interface OrderPage {
  items: OrderItem[];
  page: number;
  page_size: number;
  total: number;
  total_pages: number;
  sort_by: string;
  sort_order: string;
}

/** 排序参数：只传字段名时由后端按该字段的默认方向处理（如 order_no 默认 asc）。 */
export interface OrderSort {
  field: OrderSortField;
  direction?: OrderSortDirection;
}

/**
 * 订单查询参数，对齐后端 OrderRequest 的完整查询契约（docs/order-query-design.md 第二节）。
 * 所有条件可选，AND 语义；多值筛选以数组表达，序列化时逗号拼接。
 */
export interface OrderQuery {
  /** 页码，从 1 开始，默认 1。 */
  page?: number;
  /** 每页行数；页面默认 10，后端允许 1-100。 */
  pageSize?: number;
  /** 状态多值筛选（传输为逗号分隔）。 */
  orderStatus?: readonly OrderStatus[];
  /** 渠道多值筛选。 */
  salesChannel?: readonly SalesChannel[];
  /** 币种多值筛选。 */
  currency?: readonly Currency[];
  /** 客户姓名模糊匹配（前后通配）。 */
  customerName?: string;
  /** 订单号前缀匹配。 */
  orderNo?: string;
  /** 客户手机号精确等值（11 位数字）。 */
  customerPhone?: string;
  /** 金额区间下界（含）。 */
  totalAmountMin?: number;
  /** 金额区间上界（含）。 */
  totalAmountMax?: number;
  /**
   * 下单时间下界（含）。
   * 时间契约：本地格式 yyyy-MM-DDTHH:mm:ss（无时区后缀）；传 dayjs 对象时按本地格式序列化，
   * 禁止 toISOString()（其 UTC Z 后缀会被后端 400）。
   */
  createdAtBegin?: string | Dayjs;
  /** 下单时间上界（不含，左闭右开）。 */
  createdAtEnd?: string | Dayjs;
  /** 排序字段（sort_order 缺省时由后端按该字段的默认方向处理）。 */
  sort?: OrderSort;
}

/**
 * 查询订单列表。
 * 将查询参数映射为后端 snake_case query 参数（多值逗号拼接、时间本地格式化），返回解包后的分页数据。
 */
export async function listOrders(params: OrderQuery = {}): Promise<OrderPage> {
  const query = new URLSearchParams();
  if (params.page != null) query.set('page', String(params.page));
  if (params.pageSize != null) query.set('page_size', String(params.pageSize));
  appendMultiValue(query, 'order_status', params.orderStatus);
  appendMultiValue(query, 'sales_channel', params.salesChannel);
  appendMultiValue(query, 'currency', params.currency);
  appendString(query, 'customer_name', params.customerName);
  appendString(query, 'order_no', params.orderNo);
  appendString(query, 'customer_phone', params.customerPhone);
  if (params.totalAmountMin != null) query.set('total_amount_min', String(params.totalAmountMin));
  if (params.totalAmountMax != null) query.set('total_amount_max', String(params.totalAmountMax));
  appendTime(query, 'created_at_begin', params.createdAtBegin);
  appendTime(query, 'created_at_end', params.createdAtEnd);
  if (params.sort) {
    // 只传 sort_by 时由后端按字段默认方向展开；显式方向以独立 sort_order 参数传输
    query.set('sort_by', params.sort.field);
    if (params.sort.direction) {
      query.set('sort_order', params.sort.direction);
    }
  }

  const qs = query.toString();
  return requestJson<OrderPage>(qs ? `/api/v1/orders?${qs}` : '/api/v1/orders');
}

/**
 * 本地时间格式（无时区后缀）：查询参数与导出快照共用的唯一时间格式事实源。
 * 后端时间契约：禁止 toISOString()（其 UTC Z 后缀会被后端 400）。
 */
export function formatLocalDateTime(value: Dayjs): string {
  return value.format('YYYY-MM-DDTHH:mm:ss');
}

/** 多值筛选序列化：空数组视为未传（对齐后端「空集合 = 无条件」契约）。 */
function appendMultiValue(query: URLSearchParams, key: string, values?: readonly string[]): void {
  if (values == null || values.length === 0) return;
  query.set(key, values.join(','));
}

/** 字符串筛选序列化：null / 空白串视为未传。 */
function appendString(query: URLSearchParams, key: string, value?: string): void {
  if (value == null) return;
  const trimmed = value.trim();
  if (trimmed === '') return;
  query.set(key, trimmed);
}

/** 时间参数序列化：dayjs 对象按本地格式（无时区）输出，字符串原样透传。 */
function appendTime(query: URLSearchParams, key: string, value?: string | Dayjs): void {
  if (value == null) return;
  query.set(key, typeof value === 'string' ? value : formatLocalDateTime(value));
}
