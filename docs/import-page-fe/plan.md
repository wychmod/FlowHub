# 订单导入前端（上传入口 + 导入任务页 + 实时进度）Plan

> 状态：**草案待评审**（2026-09-10；阶段二）。上游：`spec.md`（功能编号 F1–F19、验收 AC1–AC14 以其为准）。
> 技术栈基线：React 18 + TypeScript + antd 6 + @tanstack/react-query 5 + dayjs + Vitest（node/jsdom 环境）。
> 契约事实源：后端 `docs/order-import-design.md` §5（接口）/§8（前端设计）。本文件只描述前端落地结构，字段命名严格对齐后端 Envelope（snake_case）。

## 架构概览

严格复刻导出链路，只在三处发生增量（multipart 上传、PARTIAL 展示、第二个 SSE 流）。分层与 `features/exports/`、`features/orders/` 同构：

```
┌─ App.tsx（页面切换：orders | exports | import-jobs）─────────────────────┐
│  AppLayout（PageKey + PAGE_ICONS + MENU 派生自 PAGE_META，新增 import-jobs）│
└──────────────────────────────────────────────────────────────────────────┘
        │                                              │
   features/orders/                              features/import-jobs/
   OrderListPage                                   ImportJobsPage
     └─ ImportModal（新增：模板下载 + Upload.Dragger） │  ├─ useImportEvents（复刻 useExportEvents）
        · beforeUpload 预检纯函数                    │  ├─ components/ConnectionBadge（复用/共享）
        · onNavigate('import-jobs')                 │  ├─ types.ts（连接态 + 列表项/事件类型）
                                                    │  └─ importConstants.ts（状态 Tag 元信息）
        │  统一走 api/ 防腐层
        ▼
   api/importApi.ts（新增）        api/http.ts（复用）   api/download.ts（复用）
     · requestJson（列表/重试）                          · parseBlobError（模板/错误报告）
     · 独立 multipart 上传通道（手写 fetch FormData）    · saveBlob / filenameFromDisposition
```

要点：

- **页面只说业务语言**：协议细节（Envelope 解包、multipart、下载分流、SSE）全部收敛在 `api/` 层与 `useImportEvents`，页面组件不碰 `fetch` 原文。
- **两条独立实时通道**：`useExportEvents` 与 `useImportEvents` 各持一个 `EventSource`、各管各的 queryKey；同页不同路由互不影响。
- **能共享则共享**：`ConnectionBadge`、`download.ts`、`http.ts`、`ApiError`、连接状态类型直接复用；`useImportEvents` 在可读性优先前提下尽量薄（见「复用与取舍」）。

## 目录与文件清单

```
frontend/src/
├── api/
│   └── importApi.ts               # 新增：列表/上传/模板下载/错误报告下载/重试 + 类型
├── app/
│   ├── AppLayout.tsx              # 改：PageKey 增 'import-jobs'、PAGE_ICONS 增图标
│   └── layoutMeta.ts              # 改：PAGE_META 增 import-jobs:{label:'导入任务'}
├── App.tsx                        # 改：页面切换分支增 import-jobs → <ImportJobsPage/>
└── features/
    ├── orders/
    │   ├── OrderListPage.tsx      # 改：工具栏增「导入订单」按钮 + 挂 ImportModal
    │   └── components/
    │       └── ImportModal.tsx    # 新增：模板下载 + Upload.Dragger + beforeUpload 预检
    └── import-jobs/               # 新增 feature（与 exports/ 同构）
        ├── ImportJobsPage.tsx     # 导入任务列表页（复刻 ExportJobsPage）
        ├── useImportEvents.ts     # SSE 消费 Hook（复刻 useExportEvents）
        ├── types.ts               # ImportEventConnectionState / 列表项 / 事件类型
        ├── importConstants.ts     # 状态 Tag 元信息（含 PARTIAL）/ 可选错误码文案
        ├── uploadGuards.ts        # beforeUpload 预检纯函数（可测）
        └── components/
            └── (ConnectionBadge 复用，见复用策略)
```

## 核心数据结构（`api/importApi.ts`）

字段与后端 `order-import-design.md` §5.2/5.3/5.6 逐一对齐。

```ts
/** 导入任务状态：比导出多 PARTIAL（部分成功），与后端 ImportJobStatus 对齐。 */
export type ImportJobStatus =
  | 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'EXPIRED';

/** 错误分类 Top N（error_summary JSON 载体）。 */
export interface ImportErrorSummaryItem { reason: string; count: number; }

/** 导入任务列表行（§5.3），字段与 SSE payload 对齐，供 useImportEvents 就地乐观更新。 */
export interface ImportJobItem {
  job_id: number;
  job_no: string;
  status: ImportJobStatus;
  job_version: number;
  total_rows: number;
  processed_rows: number;
  succeeded_rows: number;
  skipped_rows: number;
  progress_percent: number;            // RUNNING 封顶 99，SUCCEEDED/PARTIAL 才 100（后端算）
  error_report_available: boolean;     // PARTIAL 且登记报告路径才 true
  error_summary?: ImportErrorSummaryItem[] | null;
  file_name?: string;                  // 上传原件展示名（含 .xlsx）
  file_size_bytes?: number | null;
  error_code?: string | null;          // 仅 FAILED
  error_message?: string | null;       // 仅 FAILED
  created_at: string;
  finished_at?: string | null;
  expired_at?: string | null;
}

export interface ImportJobPage {
  items: ImportJobItem[];
  total: number;
  page: number;
  page_size: number;
}

/** 受理成功（202 data，§5.2 ImportJobAcceptedVO）。 */
export interface ImportJobAccepted {
  job_id: number;
  job_no: string;
  status: ImportJobStatus;
  total_rows: number;
  file_name: string;
  created_at: string;
}

/** SSE 事件 payload（§5.6，字段存在性即协议，NON_NULL）。 */
export interface ImportJobEvent {
  job_id: string;                       // 后端 SSE 用 string，列表用 number，栅栏处统一转字符串比较
  job_version: number;
  status: ImportJobStatus;
  processed_rows: number;
  total_rows: number;
  succeeded_rows?: number;              // progress/partial 携带
  skipped_rows?: number;
  progress_percent: number;
  error_report_available?: boolean;     // partial 携带
  error_code?: string | null;           // failed 显式携带
  error_message?: string | null;
  occurred_at: string;
}
```

## API 方法（`api/importApi.ts`）

```ts
import { apiBaseUrl, requestJson, asEnvelope, envelopeToError, ApiError } from './http';
import { parseBlobError, filenameFromDisposition, saveBlob } from './download';

export const importEventsUrl = '/api/v1/import-jobs/events';
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/** 列表分页（复用 requestJson，可带 status 过滤）。 */
export function listImportJobs(params: { page: number; pageSize: number; status?: ImportJobStatus }): Promise<ImportJobPage> {
  const q = new URLSearchParams({ page: String(params.page), page_size: String(params.pageSize) });
  if (params.status) q.set('status', params.status);
  return requestJson<ImportJobPage>(`/api/v1/import-jobs?${q}`);
}

/** 上传受理：multipart，独立于 requestJson 的通道（N3）。 */
export async function uploadOrderImport(file: File): Promise<ImportJobAccepted> {
  const form = new FormData();
  form.append('file', file);                 // 字段名固定 file（§5.2）
  const response = await fetch(apiBaseUrl('/api/v1/import-jobs'), {
    method: 'POST',
    headers: { Accept: 'application/json' },// 不手动设 Content-Type，交给浏览器带 boundary
    body: form,
  });
  const body = await safeJson(response);
  if (!response.ok) throw envelopeToError(asEnvelope(body), response.status, `导入受理失败（HTTP ${response.status}）`);
  const env = asEnvelope(body);
  if (!env) throw new ApiError('Invalid API envelope', undefined, undefined, response.status);
  return env.data as ImportJobAccepted;
}

/** 下载模板（GET，成功二进制 / 失败 Envelope 分流，复用 download.ts）。 */
export async function downloadImportTemplate(): Promise<void> {
  const response = await fetch(apiBaseUrl('/api/v1/import-jobs/template'), { headers: { Accept: XLSX_MIME } });
  if (!response.ok) throw await parseBlobError(response);
  saveBlob(await response.blob(), filenameFromDisposition(response.headers.get('Content-Disposition')) ?? '订单导入模板.xlsx');
}

/** 下载错误报告（仅 PARTIAL；复用下载协议）。 */
export async function downloadImportErrorReport(job: ImportJobItem): Promise<void> {
  const response = await fetch(apiBaseUrl(`/api/v1/import-jobs/${encodeURIComponent(job.job_id)}/error-report`), { headers: { Accept: XLSX_MIME } });
  if (!response.ok) throw await parseBlobError(response);
  saveBlob(await response.blob(), filenameFromDisposition(response.headers.get('Content-Disposition')) ?? `导入错误报告-${job.job_no}.xlsx`);
}

/** 人工重试（仅 FAILED；202 回 PENDING）。 */
export function retryImportJob(jobId: number): Promise<ImportJobAccepted> {
  return requestJson<ImportJobAccepted>(`/api/v1/import-jobs/${jobId}/retry`, { method: 'POST' });
}
```

> `safeJson`：与 `http.ts` 内 `parseJsonBody` 同语义的小工具（读 JSON 失败返回 null）。若不愿在 `importApi.ts` 重复，可把 `parseJsonBody`/`asEnvelope` 从 `http.ts` 导出复用（见「复用策略」）。

## 上传预检纯函数（`features/import-jobs/uploadGuards.ts`）

把 `beforeUpload` 的可判定逻辑抽成纯函数，node 可测（N5/AC3）。

```ts
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export interface UploadGuardResult { ok: boolean; reason?: 'FORMAT' | 'TOO_LARGE'; message?: string; }

export function checkUploadFile(file: { name: string; size: number }): UploadGuardResult {
  if (!/\.xlsx$/i.test(file.name)) return { ok: false, reason: 'FORMAT', message: '仅支持 .xlsx 格式（请用导出模板填写）' };
  if (file.size > MAX_UPLOAD_BYTES) return { ok: false, reason: 'TOO_LARGE', message: '文件超过 10MB 上限' };
  return { ok: true };
}
```

## SSE Hook（`features/import-jobs/useImportEvents.ts`）

逐段对照 `useExportEvents`，差异点三处：

1. **queryKey**：`['exportJobs']` → `['importJobs']`（`invalidateQueries`/`getQueriesData`/`setQueryData` 全部替换）。
2. **事件类型名**：`job.progress`/`job.succeeded`/`job.failed` → `import.progress`/`import.succeeded`/`import.partial`/`import.failed`（多一个 `partial`），心跳 `heartbeat` 不变。
3. **payload 类型**：`ExportJobEvent` → `ImportJobEvent`；`isImportJobEvent` 核心字段校验含 `succeeded_rows`/`skipped_rows` 可选；`applyEvent` 乐观更新覆盖 `succeeded_rows`/`skipped_rows`/`error_report_available`，错误字段沿用 hasOwnProperty（缺失保留、null 清空）语义。

保持不变（直接照搬）：`RECONNECT_DELAYS=[1s,2s,5s,10s]`、`failureCount>=3` 切 polling、版本栅栏 `event.job_version <= max(known)` 丢弃、`job_id` 字符串化比较、筛选缓存智能移除、恢复/重连/可见性/在线生命周期。连接态类型 `ImportEventConnectionState` 与 `ExportEventConnectionState` 结构相同（mode/consecutiveFailures/lastEventAt）。

## 导入任务页（`ImportJobsPage.tsx`）

复刻 `ExportJobsPage` 的骨架（Card 头 + ConnectionBadge + 总数 + 刷新；Card 体 + actionError Alert + 列表 error Alert + Table + pagination），列定义按下表调整：

| 列 | dataIndex/key | 渲染要点 |
|---|---|---|
| 任务编号 | `job_no` | `<Typography.Text code>` |
| 文件名 | `file_name` | 直读，缺省「—」 |
| 状态 | `status` | `IMPORT_STATUS_META`（下）；FAILED 悬浮错误原因；PARTIAL 特殊色 |
| 进度 | `progress_percent` | `Progress` + `processed_rows/total_rows`；status 决定 success/exception/active |
| 结果统计 | 组合 | `成功 succeeded_rows / 跳过 skipped_rows`；PARTIAL 行附 `error_summary` 悬浮 Top N |
| 创建/完成时间 | `created_at`/`finished_at` | dayjs `YYYY-MM-DD HH:mm:ss`，空值「—」 |
| 操作 | key actions | PARTIAL 且 `error_report_available`→「错误报告」下载；FAILED→「重试」Popconfirm；下载/重试期间 `busyJobId` loading |

`IMPORT_STATUS_META`（`import-jobs/importConstants.ts`）：

```ts
export const IMPORT_STATUS_META: Record<ImportJobStatus, { label: string; color: string }> = {
  PENDING:   { label: '排队中',   color: 'orange' },
  RUNNING:   { label: '执行中',   color: 'processing' },
  SUCCEEDED: { label: '全部成功', color: 'green' },
  PARTIAL:   { label: '部分成功', color: 'gold' },     // 关键：部分成功 ≠ 失败
  FAILED:    { label: '失败',     color: 'red' },
  EXPIRED:   { label: '已过期',   color: 'default' },
};
```

轮询降级 `refetchInterval`：`hasActive = items.some(status in {PENDING,RUNNING})`；`connection.mode==='sse'` 或无进行中任务 → `false`；否则可见 3s / 隐藏 15s。逻辑与导出页一致。

## 上传入口弹窗（`features/orders/components/ImportModal.tsx`）

- antd `Modal` + `Upload.Dragger`（`customRequest` 关闭默认上传，自行用 `uploadOrderImport` 受控调用；或 `beforeUpload` 返回 `false` 手动持有 file 再点「开始导入」提交——取后者，便于展示成功态与「查看任务」）。
- 模板下载：`Typography.Link` onClick → `downloadImportTemplate()`，失败 `message.error(errorMessage)`。
- `beforeUpload` → `checkUploadFile`；不通过 `message.warning` 并阻止入队。
- 提交：`useMutation(uploadOrderImport)`，pending 期按钮 loading；成功展示 `ImportJobAccepted`（文件名 + total_rows）+「查看导入任务」按钮（`onNavigate('import-jobs')` 并关弹窗）；失败保留文件、Alert 展示错误 + trace_id。
- 复用 `OrderListPage` 已有的 `onNavigate` prop（现用于导出成功跳转），扩展可跳 `import-jobs`。

## 导航接入

- `App.tsx`：`page === 'orders' ? <OrderListPage onNavigate={setPage}/> : page === 'exports' ? <ExportJobsPage/> : <ImportJobsPage/>`。
- `AppLayout.tsx`：`export type PageKey = 'orders' | 'exports' | 'import-jobs'`；`PAGE_ICONS['import-jobs'] = <ImportOutlined />`（`@ant-design/icons`）。
- `layoutMeta.ts`：`PAGE_META['import-jobs'] = { label: '导入任务' }`。菜单/页题自动派生，无需改 MENU 逻辑。

## 复用策略（避免复制的关键决策）

| 目标 | 取舍 | 理由 |
|---|---|---|
| `ConnectionBadge` | **复用导出页组件**：把其 prop 类型从 `ExportEventConnectionState` 放宽为结构化 `{ mode; consecutiveFailures; lastEventAt? }`（或提到 `components/` 共享层） | 徽标只描述通道状态，与领域无关；两处共用一套，避免复制漂移 |
| `useImportEvents` | **独立文件，逐段照搬改 3 点**，不强行抽象泛型 Hook | 事件名/字段/queryKey 差异使泛型化收益低于可读性成本；单测也各自独立更清晰 |
| `http.ts` 的 `parseJsonBody` | 视需要导出复用，否则 `importApi.ts` 内联 5 行 | 保持防腐层收敛原则；不重复实现 `asEnvelope`/`envelopeToError` |
| 状态元信息 / 分页常量 | 分页常量（`DEFAULT_PAGE_SIZE`/`PAGE_SIZE_OPTIONS`）从 orders 复用；状态 Tag 元信息导入专属（含 PARTIAL）另立 | 导入状态集不同，不共用导出/订单的 STATUS_META |

## 状态与数据流（一图）

```
上传：beforeUpload(checkUploadFile 预检) → [合法] → mutation uploadOrderImport(FormData)
        ├ 202 → ImportJobAccepted → 成功态 + 「查看导入任务」onNavigate('import-jobs')
        └ 400 IMPORT_* / 其它 → envelopeToError → Alert(message + trace_id)，弹窗不关，可重传

导入页挂载：useImportEvents(true) → EventSource(importEventsUrl)
  事件(import.progress|succeeded|partial|failed) → isImportJobEvent 校验 → applyEvent:
     版本栅栏(jobId:version) → setQueryData 乐观更新缓存行 → invalidateQueries(['importJobs']) 校准
  连接 onerror 累计≥3 → mode=polling → refetchInterval(3s/15s 按可见性) 兜底
  下载：错误报告/模板 → fetch → !ok ? parseBlobError : saveBlob(filenameFromDisposition)
  重试：mutation retryImportJob(FAILED only) → 202 回 PENDING → 交 SSE/轮询收敛
```

## 与后端契约的对应表（自检用）

| 前端调用 | 后端端点（§5） | 成功 | 失败码 |
|---|---|---|---|
| `uploadOrderImport` | `POST /import-jobs` | 202 AcceptedVO | 400 `IMPORT_FILE_TOO_LARGE`/`IMPORT_FORMAT_NOT_SUPPORTED`/`IMPORT_FILE_CORRUPTED`/`IMPORT_TEMPLATE_MISMATCH`/`IMPORT_EMPTY_FILE`/`IMPORT_TOO_MANY_ROWS` |
| `downloadImportTemplate` | `GET /import-jobs/template` | 二进制 xlsx | Envelope |
| `listImportJobs` | `GET /import-jobs` | ImportJobPage | 400 `VALIDATION_ERROR`（status 非法） |
| `downloadImportErrorReport` | `GET /import-jobs/{id}/error-report` | 二进制 xlsx（仅 PARTIAL） | 404 `IMPORT_JOB_NOT_FOUND` / 409 `IMPORT_ERROR_REPORT_NOT_AVAILABLE` |
| `retryImportJob` | `POST /import-jobs/{id}/retry` | 202（仅 FAILED） | 404 `IMPORT_JOB_NOT_FOUND` / 409 `IMPORT_JOB_NOT_RETRYABLE` |
| `useImportEvents` | `GET /import-jobs/events` | 5 类 SSE | — |
