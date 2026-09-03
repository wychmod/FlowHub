import { apiBaseUrl, requestJson } from './http';
import { filenameFromDisposition, parseBlobError, saveBlob } from './download';
import type {
  Currency,
  OrderSortDirection,
  OrderSortField,
  OrderStatus,
  SalesChannel,
} from '../features/orders/api';

/** 导出任务状态（字符串字面量联合类型，即 TS 中的枚举常量集合），取值与后端 ExportJobStatus 对齐。 */
export type ExportJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';

/** 导出任务列表行（骨架版最小字段，完整字段见 be-td.md 4.6）。 */
export interface ExportJobItem {
  job_id: number;
  job_no: string;
  status: ExportJobStatus;
  created_at: string;
  /** 生成的文件名（含扩展名）；任务未完成时可能缺省，下载时作响应头解析失败后的兜底。 */
  file_name?: string;
}

/** 导出任务分页响应。 */
export interface ExportJobPage {
  items: ExportJobItem[];
  total: number;
  page: number;
  page_size: number;
}

// 以下订单枚举类型复用 features/orders/api.ts 的定义（fe-td.md 规则：跨 feature 领域 API 放 api/ 层，
// 类型仍以订单模块为单一来源）。

/** 导出列 key（后端 9 列白名单，PRD 7.3.2）。 */
export type ExportColumnKey =
  | 'order_no'
  | 'order_status'
  | 'sales_channel'
  | 'customer_name'
  | 'customer_phone'
  | 'total_amount'
  | 'currency'
  | 'shipping_province'
  | 'created_at';

/** 导出列白名单：顺序即默认表头顺序，Excel 表头文案与默认勾选属于契约的一部分（PRD 7.3.2）。 */
export const EXPORT_COLUMN_OPTIONS: {
  key: ExportColumnKey;
  label: string;
  defaultSelected: boolean;
}[] = [
  { key: 'order_no', label: '订单号', defaultSelected: true },
  { key: 'order_status', label: '订单状态', defaultSelected: true },
  { key: 'sales_channel', label: '销售渠道', defaultSelected: true },
  { key: 'customer_name', label: '客户姓名', defaultSelected: false },
  { key: 'customer_phone', label: '客户手机号', defaultSelected: false },
  { key: 'total_amount', label: '订单金额', defaultSelected: true },
  { key: 'currency', label: '币种', defaultSelected: true },
  { key: 'shipping_province', label: '收货省份', defaultSelected: false },
  { key: 'created_at', label: '下单时间', defaultSelected: true },
];

/**
 * 筛选导出快照：字段名与订单查询契约一致（order-query-design.md 第八节第 9 步口径；
 * 多值为数组、时间为本地格式字符串，排序字段恒包含）。
 */
export interface ExportFilterSnapshot {
  order_status?: OrderStatus[];
  sales_channel?: SalesChannel[];
  currency?: Currency[];
  customer_name?: string;
  order_no?: string;
  customer_phone?: string;
  total_amount_min?: number;
  total_amount_max?: number;
  created_at_begin?: string;
  created_at_end?: string;
  sort_by?: OrderSortField;
  sort_order?: OrderSortDirection;
}

/** 创建导出任务请求体（be-td.md 4.5，selection 两种模式的判别联合）。 */
export type CreateExportJobPayload = {
  selection:
    | { mode: 'SELECTED_IDS'; order_ids: number[] }
    | { mode: 'FILTER'; filter: ExportFilterSnapshot };
  /** 导出列，至少 1 列，按白名单顺序输出。 */
  columns: ExportColumnKey[];
  /** 可选文件名（不含路径与扩展名），留空由后端按时间生成。 */
  file_name?: string;
};

/** 创建成功响应（HTTP 202 的 data）。 */
export interface ExportJobCreated {
  job_id: number;
  job_no: string;
  status: ExportJobStatus;
  total_rows: number;
}

/** 分页查询导出任务列表（初始骨架版返回空列表占位）。 */
export async function listExportJobs(params: { page: number; pageSize: number }): Promise<ExportJobPage> {
  const query = new URLSearchParams({
    page: String(params.page),
    page_size: String(params.pageSize),
  });
  return requestJson<ExportJobPage>(`/api/v1/export-jobs?${query.toString()}`);
}

/** Excel 文件的 MIME 类型（下载 Accept 头，与后端 Content-Type 对齐）。 */
const XLSX_MIME_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/**
 * 下载导出文件（fe-td.md 7）：成功读取文件流并触发浏览器保存；
 * 失败响应可能是 JSON 错误包（409/410/404）或网关文本，统一交 parseBlobError 转为 ApiError。
 */
export async function downloadExportJob(job: ExportJobItem): Promise<void> {
  const response = await fetch(
    apiBaseUrl(`/api/v1/export-jobs/${encodeURIComponent(job.job_id)}/download`),
    { headers: { Accept: XLSX_MIME_TYPE } },
  );
  if (!response.ok) {
    throw await parseBlobError(response);
  }
  const blob = await response.blob();
  // 文件名兜底链：响应头解析 → 任务 file_name → 任务编号，保证下载文件名始终可用
  saveBlob(
    blob,
    filenameFromDisposition(response.headers.get('Content-Disposition')) ??
      job.file_name ??
      `export-${job.job_no}.xlsx`,
  );
}

/** 创建导出任务；幂等键由调用方每次明确点击时生成（PRD 7.4.2）。 */
export function createExportJob(
  payload: CreateExportJobPayload,
  idempotencyKey: string,
): Promise<ExportJobCreated> {
  return requestJson<ExportJobCreated>('/api/v1/export-jobs', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(payload),
  });
}
