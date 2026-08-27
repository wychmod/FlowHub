import { requestJson } from '../../api/http';

/** 订单状态（字符串字面量联合类型），取值对齐 be-td.md 4.3：PENDING/PAID/SHIPPED/COMPLETED/CANCELED。 */
export type OrderStatus = 'PENDING' | 'PAID' | 'SHIPPED' | 'COMPLETED' | 'CANCELED';

/** 销售渠道，be-td.md 4.3：WEB/APP/STORE/PARTNER。 */
export type SalesChannel = 'WEB' | 'APP' | 'STORE' | 'PARTNER';

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
 * 订单查询参数，对齐当前后端 OrderRequest。
 * 现阶段后端只实现 page 与 page_size，因此前端也只暴露这两个分页条件。
 */
export interface OrderQuery {
  /** 页码，从 1 开始，默认 1。 */
  page?: number;
  /** 每页行数；页面默认 10，后端允许 1-100。 */
  pageSize?: number;
}

/**
 * 查询订单列表。
 * 将页面分页状态映射为后端 query 参数（page/page_size），返回解包后的分页数据。
 */
export async function listOrders(params: OrderQuery = {}): Promise<OrderPage> {
  const query = new URLSearchParams();
  if (params.page != null) query.set('page', String(params.page));
  if (params.pageSize != null) query.set('page_size', String(params.pageSize));

  const qs = query.toString();
  return requestJson<OrderPage>(qs ? `/api/v1/orders?${qs}` : '/api/v1/orders');
}
