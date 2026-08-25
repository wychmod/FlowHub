import { requestJson } from '../../api/http';

/** 订单状态（字符串字面量联合类型），取值对齐 be-td.md 4.3：PENDING/PAID/SHIPPED/COMPLETED/CANCELED。 */
export type OrderStatus = 'PENDING' | 'PAID' | 'SHIPPED' | 'COMPLETED' | 'CANCELED';

/** 销售渠道，be-td.md 4.3：WEB/APP/STORE/PARTNER。 */
export type SalesChannel = 'WEB' | 'APP' | 'STORE' | 'PARTNER';

/** 允许排序的字段，be-td.md 4.3：created_at / total_amount。 */
export type OrderSortBy = 'created_at' | 'total_amount';

/** 排序方向，be-td.md 4.3：asc / desc。 */
export type OrderSortOrder = 'asc' | 'desc';

/** 订单列表行，字段与后端 OrderItemVO（snake_case）一致，见 be-td.md 4.3。 */
export interface OrderItem {
  id: number;
  order_no: string;
  order_status: OrderStatus;
  sales_channel: SalesChannel;
  customer_name: string | null;
  total_amount: number;
  currency: string;
  created_at: string;
}

/** 订单分页响应，字段与后端 OrderPageResp 一致。 */
export interface OrderPage {
  items: OrderItem[];
  page: number;
  page_size: number;
  total: number;
  total_pages: number;
}

/**
 * 订单查询参数，对齐 be-td.md 4.3 的 Query 参数。
 * 全部为可选；未传的字段不会拼进 query（后端忽略未映射参数，不报错）。
 */
export interface OrderQuery {
  /** 页码，从 1 开始，默认 1。 */
  page?: number;
  /** 每页行数，默认 20（后端限制 1-100）。 */
  pageSize?: number;
  /** 订单号，精确匹配，去空格，最长 32 字符。 */
  orderNo?: string;
  /** 订单状态筛选。 */
  orderStatus?: OrderStatus;
  /** 销售渠道筛选。 */
  salesChannel?: SalesChannel;
  /** 创建时间起点，ISO-8601。 */
  createdFrom?: string;
  /** 创建时间终点，ISO-8601，不得早于 createdFrom。 */
  createdTo?: string;
  /** 最小金额，>= 0。 */
  minAmount?: number;
  /** 最大金额，>= minAmount。 */
  maxAmount?: number;
  /** 排序字段。 */
  sortBy?: OrderSortBy;
  /** 排序方向。 */
  sortOrder?: OrderSortOrder;
}

/**
 * 查询订单列表（be-td.md 4.3）。
 * 将参数映射为后端 query 参数（snake_case），仅拼接非空字段；返回解包后的分页数据。
 */
export async function listOrders(params: OrderQuery = {}): Promise<OrderPage> {
  const query = new URLSearchParams();
  if (params.page != null) query.set('page', String(params.page));
  if (params.pageSize != null) query.set('page_size', String(params.pageSize));
  if (params.orderNo?.trim()) query.set('order_no', params.orderNo.trim());
  if (params.orderStatus) query.set('order_status', params.orderStatus);
  if (params.salesChannel) query.set('sales_channel', params.salesChannel);
  if (params.createdFrom) query.set('created_from', params.createdFrom);
  if (params.createdTo) query.set('created_to', params.createdTo);
  if (params.minAmount != null) query.set('min_amount', String(params.minAmount));
  if (params.maxAmount != null) query.set('max_amount', String(params.maxAmount));
  if (params.sortBy) query.set('sort_by', params.sortBy);
  if (params.sortOrder) query.set('sort_order', params.sortOrder);

  const qs = query.toString();
  return requestJson<OrderPage>(qs ? `/api/v1/orders?${qs}` : '/api/v1/orders');
}