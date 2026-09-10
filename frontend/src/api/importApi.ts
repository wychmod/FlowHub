/**
 * 订单导入 API 防腐层（docs/import-page-fe/plan.md）。
 * 页面只消费业务数据与 ApiError；Envelope 解包、multipart 上传、下载分流统一收敛在本层。
 * 契约事实源：后端 docs/order-import-design.md §5（/api/v1/import-jobs 六端点）。
 */
import { ApiError, apiBaseUrl, asEnvelope, envelopeToError, requestJson } from './http';
import { filenameFromDisposition, parseBlobError, saveBlob } from './download';

/** 导入任务状态（比导出多 PARTIAL 部分成功），与后端 ImportJobStatus 对齐。 */
export type ImportJobStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'PARTIAL'
  | 'FAILED'
  | 'EXPIRED';

/** 错误分类 Top N（error_summary JSON 载体，§5.3）。 */
export interface ImportErrorSummaryItem {
  reason: string;
  count: number;
}

/**
 * 导入任务列表行（§5.3 ImportJobItemVO），字段与 SSE payload 对齐，
 * 使 useImportEvents 的 applyEvent 能就地更新行的可更新字段。
 */
export interface ImportJobItem {
  job_id: number;
  job_no: string;
  status: ImportJobStatus;
  /** 状态版本（SSE 事件 id = jobId:version 的 version），版本栅栏依据。 */
  job_version: number;
  total_rows: number;
  processed_rows: number;
  succeeded_rows: number;
  skipped_rows: number;
  /** 派生进度：RUNNING 封顶 99，SUCCEEDED/PARTIAL 才 100（后端计算，前端直读）。 */
  progress_percent: number;
  /** 是否可下载错误报告（PARTIAL 且登记了报告路径才 true）。 */
  error_report_available: boolean;
  /** 错误分类 Top N；仅 PARTIAL 非空。 */
  error_summary?: ImportErrorSummaryItem[] | null;
  /** 上传原件展示名（含 .xlsx）。 */
  file_name?: string;
  file_size_bytes?: number | null;
  /** 失败错误码；仅 FAILED。 */
  error_code?: string | null;
  /** 失败原因；仅 FAILED。 */
  error_message?: string | null;
  created_at: string;
  finished_at?: string | null;
  expired_at?: string | null;
}

/** 导入任务分页响应。 */
export interface ImportJobPage {
  items: ImportJobItem[];
  total: number;
  page: number;
  page_size: number;
}

/** 上传受理 / 重试成功响应（202 的 data，§5.2 ImportJobAcceptedVO）。 */
export interface ImportJobAccepted {
  job_id: number;
  job_no: string;
  status: ImportJobStatus;
  total_rows: number;
  file_name: string;
  created_at: string;
}

/**
 * SSE 导入事件 payload（§5.6，字段存在性即协议，NON_NULL）。
 * progress/partial 携带计数与 progress_percent，partial 附 error_report_available，
 * failed 显式携带 error_code/error_message。
 */
export interface ImportJobEvent {
  job_id: string;
  job_version: number;
  status: ImportJobStatus;
  processed_rows: number;
  total_rows: number;
  succeeded_rows?: number;
  skipped_rows?: number;
  progress_percent: number;
  error_report_available?: boolean;
  error_code?: string | null;
  error_message?: string | null;
  occurred_at: string;
}

/** 导入任务 SSE 事件流端点（GET，EventSource 消费）。 */
export const importEventsUrl = '/api/v1/import-jobs/events';

/** Excel 文件 MIME 类型（下载 Accept 头，与后端 Content-Type 对齐）。 */
const XLSX_MIME_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/** 上传大小上限（10MB），与后端 import.file.max-size 与前端预检一致。 */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

/** 分页查询导入任务列表（可选 status 过滤，非法值后端 400 VALIDATION_ERROR）。 */
export function listImportJobs(params: {
  page: number;
  pageSize: number;
  status?: ImportJobStatus;
}): Promise<ImportJobPage> {
  const query = new URLSearchParams({
    page: String(params.page),
    page_size: String(params.pageSize),
  });
  if (params.status) query.set('status', params.status);
  return requestJson<ImportJobPage>(`/api/v1/import-jobs?${query.toString()}`);
}

/**
 * 上传受理（§5.2）：multipart 独立通道，不走 requestJson——手写 fetch 发 FormData、
 * 不手动设 Content-Type（浏览器带 boundary）；响应仍按 Envelope 解包、错误转 ApiError。
 * 字段名固定 file；成功 202 返回 ImportJobAccepted，失败为文件级/结构级 400 Envelope。
 */
export async function uploadOrderImport(file: File): Promise<ImportJobAccepted> {
  const form = new FormData();
  form.append('file', file);
  const response = await fetch(apiBaseUrl('/api/v1/import-jobs'), {
    method: 'POST',
    headers: { Accept: 'application/json' },
    body: form,
  });
  const body = await readJsonOrNull(response);
  if (!response.ok) {
    throw envelopeToError(
      asEnvelope(body),
      response.status,
      `导入受理失败（HTTP ${response.status}）`,
    );
  }
  const envelope = asEnvelope(body);
  if (!envelope) {
    throw new ApiError('Invalid API envelope', undefined, undefined, response.status);
  }
  return envelope.data as ImportJobAccepted;
}

/** 下载导入模板（§5.1）：成功二进制 / 失败 Envelope 分流（复用 download.ts）。 */
export async function downloadImportTemplate(): Promise<void> {
  const response = await fetch(apiBaseUrl('/api/v1/import-jobs/template'), {
    headers: { Accept: XLSX_MIME_TYPE },
  });
  if (!response.ok) {
    throw await parseBlobError(response);
  }
  const blob = await response.blob();
  saveBlob(
    blob,
    filenameFromDisposition(response.headers.get('Content-Disposition')) ?? '订单导入模板.xlsx',
  );
}

/** 下载错误报告（§5.4，仅 PARTIAL）：成功二进制 / 失败 Envelope 分流。 */
export async function downloadImportErrorReport(job: ImportJobItem): Promise<void> {
  const response = await fetch(
    apiBaseUrl(`/api/v1/import-jobs/${encodeURIComponent(job.job_id)}/error-report`),
    { headers: { Accept: XLSX_MIME_TYPE } },
  );
  if (!response.ok) {
    throw await parseBlobError(response);
  }
  const blob = await response.blob();
  saveBlob(
    blob,
    filenameFromDisposition(response.headers.get('Content-Disposition'))
      ?? `导入错误报告-${job.job_no}.xlsx`,
  );
}

/** 人工重试失败任务（§5.5，仅 FAILED）：202 受理回 PENDING。 */
export function retryImportJob(jobId: number): Promise<ImportJobAccepted> {
  return requestJson<ImportJobAccepted>(`/api/v1/import-jobs/${jobId}/retry`, { method: 'POST' });
}

// —— 内部工具 ——

/** 读取 JSON 响应体；非 JSON（如网关 HTML）返回 null，交由调用方按 Envelope 校验。 */
async function readJsonOrNull(response: Response): Promise<unknown | null> {
  try {
    return await response.json();
  } catch {
    return null;
  }
}
