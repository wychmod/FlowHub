# 导出进度 SSE 前端消费：设计与开发计划

> **文档定位**：本文是**前端开发计划**——交付导出任务页的 SSE 事件消费、降级轮询与连接状态管理（fe-td.md 第 6 章的落地）。SSE 契约对齐 `docs/be-td.md`（4.10 事件接口、第 10 章）与 `docs/prd.md`（7.5.2）；**所有尚未实现的后端依赖一律列为前置条件（第四节），不属于本计划的交付物**，其实现设计见 be-td.md 与参考项目。
>
> **实现蓝本**：参考项目 `D:\idea\project-export-flow` 的前端实现 `frontend/src/features/exports/useExportEvents.ts`（188 行）及其测试 `useExportEvents.test.tsx`；后端蓝本仅供前置条件验收对照（`export/controller/ExportSseService.java`）。

---

## 一、设计思想：前端为什么这么做

导出任务从同步 HTTP 重构为异步 Job 后，`202 Accepted` 只代表「命令已被接受」，不代表「结果已就绪」。页面必须知道任务何时排队（PENDING）、处理到多少行（RUNNING）、何时完成（SUCCEEDED/FAILED）。前端用四个互补机制应对「网络会抖、页面会刷、事件会乱序」的现实环境：

| # | 机制 | 一句话解释 | 关键约束 |
| --- | --- | --- | --- |
| 1 | **职责分离（推拉结合）** | SSE 只负责「尽快告诉你有变化」（轻量通知），HTTP 查询负责「完整事实」（权威校准） | SSE payload 保持极简，前端不能因为收到 `job.succeeded` 就放弃后续查询，也不能因为 EventSource 报错就把任务显示成失败 |
| 2 | **版本栅栏（`job_version`）** | 只接受比缓存更高版本的事件，版本号低的一律丢弃 | 重连、多连接交替、页面后台唤醒都可能让旧事件后到；**不靠状态字符串排序**（人工重试会让 FAILED 回到新的 PENDING），只认版本号 |
| 3 | **乐观局部更新 + 失效收敛** | 收到有效事件先就地更新缓存行（毫秒级反馈），随后 `invalidateQueries` 让服务端重新校准分页/排序/筛选 | 缓存中没有的 Job **不猜插入位置**，只失效查询；`error_code/error_message` 要区分「字段缺失」（保留旧值）与「显式 null」（清空旧错误） |
| 4 | **容错降级 + 生命周期闭环** | 连接状态机 `connecting → sse →（连续 3 次失败）polling →（离线）offline`，退避 1/2/5/10 秒重连；页面隐藏关 SSE 转低频轮询，恢复时先 refetch 再重连 | **连接故障 ≠ 任务失败**，Badge 只描述通知通道状态；EventSource/重连 Timer/online-offline 监听/visibilitychange 监听四类资源必须随 Effect 卸载清理，`sourceRef.current !== source` 拒绝过期回调写状态 |

前端必须理解、由后端前置条件保证的服务端约束：

- **事件在事务提交后广播**（`@TransactionalEventListener(AFTER_COMMIT)`）：业务事务回滚就不会有虚假事件——前端因此可以把事件当作「已发生的事实」来乐观更新。
- **监听器重读数据库后再广播**：payload 与库内状态一致——前端局部更新后才敢以事件字段直接覆盖缓存。
- **事件名描述通知类别，不替代状态字段**：`EXPIRED` 复用 `job.failed` 事件名，前端必须以 payload 的 `status` 渲染「已过期」，不能把事件名当业务状态。
- **不实现 Last-Event-ID 回放**：漏掉的状态统一由 HTTP 查询补齐——这是「重连成功 / 页面恢复 / 终态」三处必须 refetch 的原因。

---

## 二、现状扫描

### 2.1 已就绪（可直接复用）

| 环节 | 现状 | 位置 |
| --- | --- | --- |
| API 防腐层 | `requestJson` 解包/错误转换（`ApiError` 携带 code/status/traceId） | `api/http.ts` |
| 下载协议工具 | `parseBlobError`/`filenameFromDisposition`/`saveBlob` | `api/download.ts` |
| 创建/下载 API | `createExportJob`（幂等键 + selection 判别联合）、`downloadExportJob` 已按契约先行实现 | `api/exportApi.ts` |
| 导出入口 | 订单页筛选/勾选 + `ExportModal`，创建请求随时可联调 | `features/orders/` |
| react-query 约定 | `placeholderData: keepPreviousData`、查询失败保留上次数据的模式已在订单页验证 | `features/orders/OrderListPage.tsx` |

### 2.2 前端缺口（本计划交付物）

| 缺口 | 说明 |
| --- | --- |
| SSE 类型与 API | 无 `ExportJobEvent`/`ExportEventConnectionState` 类型、无 `eventsUrl`、列表返回类型缺进度字段 |
| `useExportEvents` Hook | 连接状态机、版本栅栏、局部更新、失效收敛、生命周期清理全部缺失 |
| `ExportJobsPage` 本体 | 空状态占位，无列表/进度列/连接 Badge/降级轮询 |

### 2.3 后端缺口（→ 第四节前置条件 P-1 ~ P-5）

创建接口、状态机与执行器、SSE 端点、任务列表接口真实化、下载/重试接口——全部未实现，本文不安排其开发任务，统一作为前置条件管理。

---

## 三、SSE 契约（前端消费依据，对齐 be-td.md 4.10）

### 3.1 端点与连接

- `GET /api/v1/export-jobs/events`，`text/event-stream`；前端用 `EventSource` 建立连接，断线重连与降级由**前端**负责。
- 经 Vite 代理访问（`/api` → 8080），与现有 `requestJson` 同一基座。

### 3.2 事件类型与 payload

| 事件名 | 触发时机 | payload 要点 |
| --- | --- | --- |
| `job.progress` | PENDING/RUNNING 的创建与批次推进 | 状态、进度行数、版本号 |
| `job.succeeded` | 执行成功、文件就绪 | 进度 100%、`downloadable: true` |
| `job.failed` | 执行失败**或文件过期（EXPIRED 复用）** | 错误码、错误信息（无错误上下文时显式 `null`） |
| `heartbeat` | 每 15 秒定时 | 仅 `occurred_at`，前端只更新活跃时间不改缓存 |

payload 统一 snake_case：

```json
{
  "job_id": "52",
  "job_version": 7,
  "status": "RUNNING",
  "processed_rows": 6000,
  "total_rows": 12500,
  "progress_percent": 48,
  "downloadable": false,
  "error_code": null,
  "error_message": null,
  "occurred_at": "2026-09-03T08:30:05Z"
}
```

**字段存在性即协议**：`error_code`/`error_message` 在 JSON 中是否出现有语义差异——失败任务重试回 PENDING 时必须**显式传 `null`**（前端据此清空旧错误），进度事件可省略（前端保留缓存值）。前端一律用 `Object.prototype.hasOwnProperty.call(event, 'error_code')` 判别，禁止把「缺失」与「null」混为一谈。

### 3.3 版本与顺序

- `job_version` 由后端在每次状态/进度变更时单调递增（后端口径：复用 `export_jobs.version`）；事件 id 格式 `jobId:version`，仅作排查，**前端不依赖 id 做历史回放**。
- 事件按批次（默认 1000 行/批）发布，前端靠版本栅栏丢弃堆积的旧事件。

---

## 四、前置条件（后端依赖清单）

> 以下均为**后端交付物，未实现**。前端开发不阻塞（契约先行 + FakeEventSource 单测）；「阻塞范围」列说明该前置就绪前哪些前端能力只能做到「代码就绪」，无法端到端验收。

| # | 前置条件 | 状态 | 契约/实现依据 | 阻塞的前端工作 | 未就绪时的前端对策 |
| --- | --- | --- | --- | --- | --- |
| P-1 | 任务创建接口 `POST /api/v1/export-jobs`（202 + 幂等键 + 落库 PENDING） | 未实现 | be-td.md 4.5；前端 `createExportJob` 已按契约先行 | 端到端演示（无法产生真实任务） | 创建入口维持统一错误提示兜底（与订单页现状一致）；联调前可用 SQL 直插 `export_jobs` 造数据 |
| P-2 | 状态机 + 执行器（PENDING→RUNNING→终态、批次推进 `processed_rows`、`job_version` 单调递增） | 未实现 | be-td.md 6、10.2；蓝本参考项目 `ExportExecutionStateService`/`ExportProgressService` | 真实事件流与进度演示；单测不受阻 | FakeEventSource 驱动全部状态规则测试 |
| P-3 | SSE 端点 `GET /api/v1/export-jobs/events`（`SseEmitter` 连接表 + 事务提交后广播 + 15 秒心跳 + 4 类事件） | 未实现 | 本文档第三节 + be-td.md 4.10/10.3；蓝本 `ExportSseService.java`（实现注意点：AFTER_COMMIT 监听器在测试事务回滚时不触发，后端集成测试需 `@Commit` 或独立事务路径） | SSE 真实连接、降级链路真实验证 | 后端未就绪时 EventSource 得到 404，Hook 自动走退避→轮询，恰好验证降级路径本身 |
| P-4 | 任务列表接口真实化（`GET /api/v1/export-jobs` 返回 be-td.md 4.6 完整字段：`job_version/processed_rows/total_rows/progress_percent/downloadable/error_code/error_message` 等） | 未实现（当前空列表占位） | be-td.md 4.6 | 列表渲染与 HTTP 校准联调 | 类型按 4.6 契约先行定义；空态展示 |
| P-5 | 下载接口 + Excel 生成、重试接口 | 未实现 | be-td.md 4.8/4.9 | 重试与下载按钮激活（**本计划范围外**，仅预留交互位） | `downloadExportJob` 已契约先行，失败走统一错误提示 |

**软前置（对前端透明，不阻塞任何前端工作）**：RabbitMQ + Outbox 分发、Redis 进度投影、多实例 SSE 广播（Redis Pub/Sub）、Last-Event-ID 事件回放——这些是后端内部演进，SSE 契约与前端代码不变（这正是「连接模式只是可替换实现」的意义）。

**与既有迭代规划的关系**：P-1 与 README「后续迭代指引」第 1/2 条（导出创建、任务闭环）同源，由后端迭代交付；P-3 的后端部分亦属后端闭环范围。本计划完成时若前置未就绪，交付状态为「前端代码就绪 + 单测全绿」，联调验收顺延。

---

## 五、前端设计

### 5.1 文件规划（`features/exports/`）

```
features/exports/
├── ExportJobsPage.tsx        # 页面编排：useQuery 列表 + useExportEvents + Badge
├── components/ConnectionBadge.tsx  # 连接状态徽标（只描述通道，不夸大故障）
├── useExportEvents.ts        # SSE 连接状态机 + 事件消费（本计划核心）
├── types.ts                  # ExportJobEvent / ExportEventConnectionState / 完整列表行类型
└── useExportEvents.test.tsx  # FakeEventSource 状态规则测试
```

`api/exportApi.ts` 同步扩展：列表行字段对齐 be-td.md 4.6、新增 `eventsUrl`。

### 5.2 连接状态机（`useExportEvents(enabled)`）

```
connecting ── onopen ──→ sse
    │ onerror ×1/×2（退避 1s/2s 重试，期间保持 connecting）
    │ onerror ×3+（退避 5s/10s…，模式转 polling，探测重连不停止）
    ├─ window offline ──→ offline（关连接、清 Timer；online 恢复：先 refetch 再 connect）
    └─ document hidden ──→ polling（关 SSE，低频轮询；重新可见：先 refetch 再 connect）
```

- 退避序列 `RECONNECT_DELAYS = [1_000, 2_000, 5_000, 10_000]`，超出取末位。
- `refetchInterval` 三条件轮询：SSE 正常（`mode==='sse'`）不轮询、无 PENDING/RUNNING 任务不轮询、页面隐藏 15 秒/可见 3 秒。
- `onopen` 若处于恢复态（`failureCount > 0`）先 `invalidateQueries(['exportJobs'])` 补齐断线期间错过的状态，再进入 `sse` 模式。
- 连接前先清 Timer、关旧 source；新建前检查 `mountedRef && enabled && !document.hidden && navigator.onLine`。
- **Badge 只描述通道**：`实时推送已连接 / 正在连接… / 轮询中（SSE 不可用） / 网络已离线`，禁止把连接异常渲染成「导出失败」。

### 5.3 事件消费规则（`applyEvent`）

1. **最小形状防御**：`isExportJobEvent` 校验 `job_id/job_version/status/processed_rows/total_rows/progress_percent` 类型，不识别的尽力通知直接忽略（HTTP 仍是权威）。
2. **版本栅栏**：收集所有 `['exportJobs']` 缓存中同 `job_id` 的版本，`event.job_version <= max(knownVersions)` 立即丢弃。
3. **局部更新**：只更新事件明确携带的字段（status/进度三件套/downloadable/error_*），不触碰创建时间、文件大小、任务号等事件没有的字段；状态筛选缓存（如「运行中」页）收到不匹配终态事件时移除该行并修正 `total/total_pages`。
4. **失效收敛**：每次有效事件处理后 `invalidateQueries({ queryKey: ['exportJobs'] })`，由服务端重新计算分页/排序/筛选归属；缓存中不存在的新 Job **不猜插入位置**。
5. **生命周期清理**：Effect 卸载时清理四类资源（EventSource、重连 Timer、online/offline 监听、visibilitychange 监听）；`mountedRef` + `sourceRef.current !== source` 双保险拒绝过期回调写状态。

### 5.4 页面接入（`ExportJobsPage`）

- `useQuery({ queryKey: ['exportJobs', { page, page_size, status }], queryFn, placeholderData: keepPreviousData, refetchInterval })`——同一 Query Key 前缀下按页码/状态筛选各自缓存，供 `applyEvent` 遍历更新。
- 表格沿用订单页防抖动约定（固定列宽 + `tableLayout: fixed` + 定高内滚）；状态 Tag/进度列按 `constants.ts` 口径扩展。
- 范围外（不做）：重试/下载按钮激活（等 P-5）、路由化、URL 同步。

---

## 六、开发计划（前端）

> 顺序按依赖排列；**阶段 A/B 完全不依赖后端**（契约先行 + 测试驱动），阶段 C 的联调验收依赖 P-1 ~ P-4 就绪。

| 阶段 | 任务 | 内容 | 依赖 | 完成标志 |
| --- | --- | --- | --- | --- |
| A | F1 类型与 API 扩展 | `types.ts`（`ExportJobEvent`/`ExportEventConnectionState`/完整列表行）、`exportApi` 字段扩展 + `eventsUrl` | 无 | 类型检查通过，`npm run build` 绿 |
| B | F2 `useExportEvents` | 按 5.2/5.3 完整实现状态机与消费规则 | F1 | Hook 单测全绿 |
| B | F3 `useExportEvents.test.tsx` | FakeEventSource 覆盖第七节全部用例 | F2 | `npm test` 全绿 |
| C | F4 `ExportJobsPage` 本体 | 列表 + 状态 Tag + 进度列 + `ConnectionBadge` + 三条件 `refetchInterval`；替换空状态占位 | F1–F3；列表渲染联调依赖 P-4 | 空态可渲染、Badge 状态机可见 |
| C | F5 端到端联调验收 | 真实链路验证 | **P-1 ~ P-4 全部就绪** | AC-05、AC-14 通过 |

**里程碑**：
- **M1（前端代码就绪）**：A + B + C 的页面骨架完成，`npm test && npm run build` 全绿——不依赖任何后端前置。
- **M2（联调验收）**：P-1 ~ P-4 就绪后执行 F5——进度单调推进（AC-05）、阻断 SSE 连接后切轮询并最终拿到正确终态（AC-14）。

---

## 七、测试要点（FakeEventSource，测状态规则而非浏览器实现）

| 用例 | 断言 |
| --- | --- |
| 乱序事件不倒退 | 先发 v2 `SUCCEEDED` 再发 v1 `RUNNING`，缓存仍为 SUCCEEDED/版本 2/可下载 |
| 连续失败降级节奏 | 三次失败后 `mode=polling`、`consecutiveFailures=3`；推进 1s/2s/5s/10s/10s 计时器，断言旧 source 均关闭、新 source 仅在退避结束后创建 |
| 状态筛选缓存移除 | 「运行中」筛选缓存收到 `FAILED` 事件：该行移除、`total` 修正、查询失效 |
| 错误字段存在性语义 | 事件缺失 `error_code` 保留缓存旧值；显式 `null` 清空旧错误 |
| 卸载安全 | 卸载后旧重连 Timer 触发不创建连接、旧回调不写状态（`mountedRef`/`sourceRef` 双检查） |
| 恢复后校准 | offline→online、hidden→visible、恢复连接三种场景均先 `invalidateQueries` 再继续 |

后端前置条件（P-2/P-3）自身的测试不属于本计划，见 be-td.md 第 10 章与参考项目后端测试。

---

## 八、风险与边界

| 风险/约束 | 应对 |
| --- | --- |
| SSE 响应被统一 Envelope 包装 | 已验证：`ApiResponseAdvice` 排除 SSE/流式响应，前端收到的就是原始事件流 |
| Vite 开发代理缓冲事件流 | `text/event-stream` 经 http-proxy 通常直通，联调期需实测（curl 直连 8080 与经 5174 各验一次）；异常时对该路径关闭代理缓冲或前端直连 |
| 前端先于后端完成，页面长期空态 | 与订单页创建入口同一惯例：契约先行 + 统一错误提示兜底；Hook 在 404 下自然走降级路径，Badge 如实显示 |
| 事件风暴（大任务高频推送） | 后端按批次发布 + 前端版本栅栏丢弃堆积事件，最坏情况退化为轮询校准 |
| EventSource/Timer/监听器泄漏 | 5.3 第 5 条生命周期规则 + F3 专用用例保护 |
| 无鉴权 | 维持内网/本地演示约定（be-td.md 14）；EventSource 无法自定义请求头，将来加鉴权需 Cookie 或查询参数方案 |
