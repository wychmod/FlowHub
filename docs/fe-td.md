# FlowHub 前端技术设计文档（Frontend TD）

| 文档属性 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 文档状态 | Draft |
| 项目代号 | FlowHub |
| 关联文档 | `prd.md`、`be-td.md` |

## 1. 目标与范围

本文档承接 PRD，给出 MVP 前端的技术实现方案，重点描述 API 调用、状态同步、SSE 与轮询降级等前端专属机制。

## 2. 技术栈

| 层级 | 选型 | 说明 |
| --- | --- | --- |
| 框架 | React + TypeScript | 教学窗口内主流技术栈 |
| 构建工具 | Vite | 与 PRD 发布流程一致 |
| HTTP 客户端 | 原生 `fetch` | 统一封装为 `requestJson` |
| 状态管理 | React Context / hooks | MVP 规模下避免引入重量级方案 |
| UI 组件 | Ant Design / 自定义 | 根据实际项目决定 |

## 3. 目录结构与职责划分

FlowHub 前端按**应用层、API 层和业务 feature** 三层组织，使页面、接口与业务状态按功能聚合，降低跨目录跳转成本。

```text
frontend/src/
├── main.tsx              # 应用入口，挂载 React 根节点
├── App.tsx               # 根组件，配置路由顶层
├── app/
│   └── AppLayout.tsx     # 全局布局（导航、页面框架）
├── api/
│   ├── http.ts           # HTTP 基础封装、错误处理、通用类型
│   └── exportApi.ts      # 导出任务相关 API 方法
└── features/
    ├── orders/           # 订单列表 feature
    │   ├── OrderListPage.tsx   # 订单列表页面组件
    │   ├── api.ts              # 订单相关 API 调用
    │   ├── selection.ts        # 订单勾选、跨页选择状态管理
    │   └── components/         # 订单模块私有组件
    └── exports/          # 导出任务 feature
        ├── ExportJobsPage.tsx  # 导出任务列表页面
        └── useExportEvents.ts  # SSE 事件监听与状态同步 hook
```

职责边界：

- `app/`：只放应用级壳层（布局、路由入口），不放业务逻辑。
- `api/`：存放跨 feature 复用的 HTTP 工具和按领域聚合的 API 方法；单个 feature 私有的 API 可下沉到 `features/<feature>/api.ts`。
- `features/`：按业务领域划分，每个 feature 自治管理页面、API、状态与组件，避免不同模块间随意引用。

## 4. API 统一封装

### 4.1 `requestJson`

所有 JSON REST 接口统一通过 `requestJson` 调用，自动处理请求头、JSON 解析、HTTP 错误和业务错误 Envelope。

```typescript
export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(apiBaseUrl(path), {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  });
  const body = await parseJsonBody<T>(response);

  if (!response.ok) {
    throw envelopeToError(body as ApiEnvelope<ApiErrorData> | null, response.status, `Request failed (${response.status})`);
  }

  if (!body) {
    throw new ApiError('Invalid API envelope', undefined, undefined, response.status);
  }

  return body.data as T;
}
```

### 4.2 配套类型与辅助函数（占位）

```typescript
interface ApiEnvelope<T> {
  code: string;
  message: string | null;
  data: T;
  trace_id: string;
}

interface ApiErrorData {
  code: string;
  message: string | null;
  trace_id: string;
}
```

### 4.3 使用示例

```typescript
// GET 示例
const orders = await requestJson<OrderListResp>('/api/v1/orders?page=1');

// POST 示例
const job = await requestJson<ExportJobResp>('/api/v1/export-jobs', {
  method: 'POST',
  headers: { 'Idempotency-Key': idempotencyKey },
  body: JSON.stringify(payload),
});
```

## 5. 导出任务页 API 清单

导出任务页 `/exports` 依赖以下接口完成列表展示、状态校准与失败重试。接口的详细请求/响应格式定义见后端文档 `be-td.md` 的 `4.1.1 导出任务接口概览` 与对应接口章节。

| 接口 | 页面用途 |
| --- | --- |
| `GET /api/v1/export-jobs` | 初始加载、刷新、轮询校准 |
| `GET /api/v1/export-jobs/{jobId}` | 读取某个任务的最新状态 |
| `POST /api/v1/export-jobs/{jobId}/retry` | 对失败任务发起新的执行 |

- 列表接口默认按创建时间倒序，前端通过 `status` 参数实现状态筛选。
- 详情接口用于 SSE 断线重连、终态校准以及手动刷新单行数据。
- 重试接口仅对 `FAILED` 状态且未超过最大重试次数的任务可用；成功后任务回到 `PENDING`，列表行应同步更新。

## 6. SSE 事件消费

导出任务页通过 `GET /api/v1/export-jobs/events` 建立 SSE 连接。事件格式与字段定义见后端文档 `be-td.md` 的 `GET /api/v1/export-jobs/events` 章节。

前端消费行为：

- 通过 `job_version` 判断事件新旧，只接受比本地缓存版本更高的事件，避免网络延迟导致较旧进度覆盖较新进度。
- 连接断开、页面重新可见或收到 `job.succeeded` / `job.failed` 等终态事件时，调用 `GET /api/v1/export-jobs/{jobId}` 校准最终结果。
- `heartbeat` 仅用于维持连接，不需要更新任务状态。

## 7. 文件下载处理

任务成功后，前端通过 `GET /api/v1/export-jobs/{jobId}/download` 下载 Excel。该接口成功时返回二进制文件，失败时才返回 JSON 错误 Envelope，因此前端不能简单假设「非 200 就是文本错误」。

### 7.1 下载调用示例

```typescript
// frontend/src/api/exportApi.ts
async download(job: ExportJobItem) {
  const response = await fetch(
    apiBaseUrl(`/api/v1/export-jobs/${encodeURIComponent(job.job_id)}/download`),
    {
      headers: {
        Accept: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      },
    },
  );

  if (!response.ok) {
    throw await parseBlobError(response);
  }

  const blob = await response.blob();
  saveBlob(
    blob,
    filenameFromDisposition(response.headers.get('Content-Disposition')) ?? job.file_name,
  );
}
```

### 7.2 错误处理 `parseBlobError`

由于失败响应体可能是 JSON Envelope，也可能是网关返回的纯文本/HTML，前端需要先读取 Blob，再尝试解析为 JSON：

```typescript
async function parseBlobError(response: Response): Promise<ApiError> {
  const blob = await response.blob();
  const text = await blob.text();
  try {
    const envelope = JSON.parse(text) as ApiEnvelope<ApiErrorData>;
    return envelopeToError(envelope, response.status);
  } catch {
    return new ApiError('下载失败', text, undefined, response.status);
  }
}
```

### 7.3 文件名解析

优先从 `Content-Disposition` 头的 `filename*` 字段解析 UTF-8 文件名；解析失败时回退到 `filename` 字段；都失败时使用任务列表中的 `file_name`。

```typescript
function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const starMatch = header.match(/filename\*=UTF-8''([^;]+)/i);
  if (starMatch) return decodeURIComponent(starMatch[1]);
  const match = header.match(/filename="([^"]+)"/);
  return match ? match[1] : null;
}
```

### 7.4 触发下载

拿到 Blob 后，创建临时 `<a>` 链接并模拟点击，下载完成后移除该链接：

```typescript
function saveBlob(blob: Blob, filename: string) {
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.URL.revokeObjectURL(url);
}
```

## 8. 后续补充项（占位）

- SSE 连接与轮询降级策略
- 任务列表状态管理
- 错误提示与字段级校验展示
- 勾选订单跨页保留
