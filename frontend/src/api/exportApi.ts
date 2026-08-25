import { requestJson } from './http';

/** 导出任务状态（字符串字面量联合类型，即 TS 中的枚举常量集合），取值与后端 ExportJobStatus 对齐。 */
export type ExportJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';

/** 导出任务列表行（骨架版最小字段，完整字段见 be-td.md 4.6）。 */
export interface ExportJobItem {
  job_id: number;
  job_no: string;
  status: ExportJobStatus;
  created_at: string;
}

/** 导出任务分页响应。 */
export interface ExportJobPage {
  items: ExportJobItem[];
  total: number;
  page: number;
  page_size: number;
}

/** 分页查询导出任务列表（初始骨架版返回空列表占位）。 */
export async function listExportJobs(params: { page: number; pageSize: number }): Promise<ExportJobPage> {
  const query = new URLSearchParams({
    page: String(params.page),
    page_size: String(params.pageSize),
  });
  return requestJson<ExportJobPage>(`/api/v1/export-jobs?${query.toString()}`);
}
