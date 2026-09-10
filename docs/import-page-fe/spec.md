# 订单导入前端（上传入口 + 导入任务页 + 实时进度）Spec

> 状态：**草案待评审**（2026-09-10；阶段一 / 共四阶段：spec → plan → task → checklist；本迭代仅产出方案文档，不写实现代码）。
> 上游：后端详细设计 `docs/order-import-design.md`（§5 接口契约、§8 前端详细设计、§10 任务 F1–F5 已就绪）、规划 `docs/order-import-plan.md`（决策 1–5 已拍板）、差异 `docs/import-vs-export-diff-notes.md`。
> 参照实现：前端导出链路 `docs/export-sse-design.md`、`features/exports/`、`api/exportApi.ts`、`api/download.ts`。

## 背景

订单 Excel 导入**后端全链路已实现并通过集成测试**（`order-import-design.md` §10 的 B1–B17 全部勾选），六个端点、5 类 SSE 事件、三层校验、PARTIAL 部分成功语义、错误报告、恢复/重试/清理均已落地。**前端尚未开发**：既没有上传入口，也没有导入任务列表页。

设计已明确导入与导出「骨架相同、血肉不同」——异步状态机、Outbox/消费/进度/SSE 管道逐字复用导出，差异集中在**上传通道（multipart）、行级三层校验的同步 400 反馈、PARTIAL 部分成功终态、错误报告下载**。因此本前端方案的核心策略是**复刻导出任务页那一套已验证的模式**，把增量收敛到「上传入口 + PARTIAL 相关展示 + 第二个 SSE 事件流」三处，最大复用 `useExportEvents`、`ExportJobsPage`、`download.ts`、`http.ts`。

## 目标

1. 用户可在订单列表页一键下载导入模板、拖拽上传 `.xlsx` 批量导入订单，秒级拿到文件级/结构级校验失败原因。
2. 上传受理成功后进入独立「导入任务」页，实时（SSE）查看导入进度与结果。
3. 完整表达 PARTIAL 部分成功语义：总行数 / 成功行数 / 跳过行数 / 错误分类 Top N 一目了然，错误报告可下载。
4. 失败任务（FAILED）可人工重试；部分成功（PARTIAL）不可重试，引导用户下载错误报告修正后重传。
5. 全部界面用现有 antd 6 组件搭建，PC 布局，风格、防抖动、错误提示（含 trace_id）与现有导出任务页一致。

## 核心状态与语义原则（本方案的强约束）

1. **导入页与导出页是两条独立的实时通道**：各自一个 `EventSource`、各自的 react-query 缓存键（`['importJobs', …]`）、各自的连接状态徽标；互不干扰（对齐决策「SSE 端点暂定独立」）。
2. **上传是命令、列表是事实**：上传受理只产出「任务已创建」的乐观反馈，行数据、进度、终态一律以服务端列表/SSE 校准为准，前端不伪造导入结果。
3. **通道故障 ≠ 任务失败**：SSE 掉线、离线只降级为轮询/离线徽标，绝不把任务显示成失败。
4. **PARTIAL 是成功终态、不是失败**：配色、图标、文案均按「部分成功」呈现，且不出现重试按钮。
5. **前端预检是体验、后端校验是裁决**：`beforeUpload` 拦 `.xlsx`/≤10MB 只为省一次往返，最终仍以受理接口的 400 为准。

## 功能需求

### 上传入口（订单列表页）

- **F1** 订单列表页工具栏新增「导入订单」按钮，与「导出」入口并列。
- **F2** 点击打开「导入订单」弹窗，含两部分：① 模板下载链接（`GET /import-jobs/template`，走既有下载协议，失败弹结构化错误）；② `Upload.Dragger` 拖拽/点击选择区，明确提示「仅支持由导出模板填写的 .xlsx，≤10MB，表头须为 9 列」。
- **F3** `beforeUpload` 本地预检：非 `.xlsx` 后缀或 size > 10MB 直接拦截、不发请求，并给出就地提示；通过预检后进入上传中态。
- **F4** 受理成功（HTTP 202，拿到 `ImportJobAcceptedVO`）：弹窗给出成功提示，展示文件名与 `total_rows`（识别到的数据行数），并提供「查看导入任务」跳转入口。
- **F5** 受理失败：区分同步校验错误（`IMPORT_TEMPLATE_MISMATCH`/`IMPORT_EMPTY_FILE`/`IMPORT_TOO_MANY_ROWS`/`IMPORT_FILE_*` 等 400）与其它错误，弹窗**不关闭**、保留可重传，展示 `message` 与 `trace_id`；`IMPORT_TEMPLATE_MISMATCH` 若后端附带错位列定位信息则一并展示。

### 导入任务页（独立页）

- **F6** 新增独立「导入任务」页（决策 5，不进导出页 Tab），左侧导航、顶栏页题、内容区布局复刻导出任务页。
- **F7** 列表分页展示，默认创建时间倒序；分页器、`showTotal`、换页/改每页条数行为与导出页一致；复用 `DEFAULT_PAGE_SIZE`/`PAGE_SIZE_OPTIONS`。
- **F8** 状态列 Tag 覆盖全部 6 态：PENDING 排队中 / RUNNING 执行中 / SUCCEEDED 全部成功 / **PARTIAL 部分成功** / FAILED 失败 / EXPIRED 已过期；配色语义区分（SUCCEEDED 绿、PARTIAL 金/警示、FAILED 红、EXPIRED 灰）。
- **F9** 进度列：`Progress` 进度条 + `已处理/总数` 文案；RUNNING 封顶 99，SUCCEEDED/PARTIAL 才 100（由后端 `progress_percent` 决定，前端不自行换算）。
- **F10** 结果统计列：展示 `成功行数 / 跳过行数`（对应 `succeeded_rows`/`skipped_rows`），使 PARTIAL 的「多少没进来」可直接读出。
- **F11** 错误摘要：PARTIAL 行提供 `error_summary`（`[{reason,count}]` Top N）的悬浮/展开查看（如「订单状态非法 ×120」）；FAILED 行悬浮展示 `error_code`+`error_message`。
- **F12** 文件名与创建/完成时间列：展示上传的原始文件名（`file_name`）、创建时间、完成时间（dayjs 格式化，空值显示「—」）。
- **F13** 错误报告下载：仅 PARTIAL 且 `error_report_available=true` 的行展示「错误报告」按钮，走 `GET /import-jobs/{job_id}/error-report`（复用下载协议与 `parseBlobError`），下载 `errors-attempt-N.xlsx`。
- **F14** 人工重试：仅 FAILED 行展示「重试」（`Popconfirm` 二次确认），调 `POST /import-jobs/{job_id}/retry`；202 受理后回 PENDING，交由 SSE/校准收敛。**PARTIAL/SUCCEEDED 不出现重试入口**。

### 实时进度与降级

- **F15** 进入导入任务页建立独立 SSE（`GET /import-jobs/events`），消费 5 类事件 `import.progress`/`import.succeeded`/`import.partial`/`import.failed`/`heartbeat`；复刻 `useExportEvents` 的连接状态机（connecting/sse/polling/offline）、`jobId:version` 版本栅栏、乐观局部更新、失效收敛、挂载/可见性/在线生命周期清理。
- **F16** 连接徽标（复用 `ConnectionBadge` 语义）+ 三条件轮询降级（非 sse 模式 + 有 PENDING/RUNNING 时按可见性 3s/15s，否则关闭），与导出页规则一致。
- **F17** 乐观更新只覆盖事件携带字段；错误字段「缺失→保留旧值、显式 null→清空」的 hasOwnProperty 语义与导出页一致。

### 异常与兜底

- **F18** 列表加载失败：保留上一次数据 + 错误 Alert +「重新加载」入口（`placeholderData: keepPreviousData`），与导出页一致。
- **F19** 所有请求/下载错误统一收敛为 `ApiError`，提示携带 `trace_id`；multipart 上传的错误也要经 `envelopeToError` 归一到同一错误模型。

## 非功能需求

- **N1** 全部使用现有 antd 6 组件与既有 `api/` 工具，不新增 UI 依赖。
- **N2** 复用而非复制：`useImportEvents` 与 `useExportEvents` 的差异仅为「queryKey、事件类型名、payload 增加字段」；优先以参数化/共享降低重复（取舍见 plan）。
- **N3** multipart 通道独立于 `requestJson`：手写 `fetch` 发 `FormData`、**不手动设 `Content-Type`**（浏览器带 boundary）；响应仍按 Envelope 解包，错误转 `ApiError`。
- **N4** 防抖动延续现状：表格 `tableLayout: fixed` + `scroll.y` 内部滚动 + `scrollbar-gutter: stable` + `keepPreviousData`，翻页/刷新零抖动。
- **N5** 可测试性：SSE 事件结构校验、版本栅栏、乐观更新、轮询间隔判定、`beforeUpload` 预检、上传请求构建等纯逻辑抽为可测单元，node/jsdom 环境覆盖；`npm test` 与 `npm run build`（tsc）通过，受仓库前端 hook 约束。
- **N6** 时间展示用 dayjs 本地格式；枚举/百分比/计数一律直读后端派生字段，前端不做业务换算。

## 不做的事

- 上传原件的用户侧管理界面（历史上传列表、删除原件）——按保留期随任务清理，前端不暴露。
- 错误行「增量重传」（下载错误报告改完只传错误行）——后端明确不做（`import-vs-export-diff-notes.md` §11）。
- 导入/导出 SSE 合并为统一事件流——本次保持独立端点。
- 导入任务的详情弹窗/单行详情接口消费——本期用列表行内展开与悬浮足够，`GET /import-jobs/{job_id}`（若有）非必需。
- 鉴权、权限、任务归属；对象存储；多实例分布式协调（同全局已知未实现）。
- 路由化改造（仍以 `App.tsx` 状态切换页，与现状一致）。
- 上传进度的字节级流式反馈（上传本身无进度协议，受理为一次性 202）。

## 验收标准

- **AC1** 订单页出现「导入订单」按钮，打开弹窗可见模板下载链接与拖拽上传区。
- **AC2** 点模板下载：浏览器保存 `.xlsx`（2 Sheet），文件名取自响应头；停后端后点下载 → 就地错误提示含 trace_id，不白屏。
- **AC3** 拖入非 `.xlsx`（如 .csv/.xls）或 >10MB 文件 → `beforeUpload` 拦截，Network 面板**无** `POST /import-jobs` 请求，出现本地提示。
- **AC4** 拖入合法文件 → 发出 `POST /api/v1/import-jobs`（multipart，字段名 `file`，无手动 Content-Type），返回 202；弹窗显示成功与 `total_rows`，提供「查看导入任务」入口。
- **AC5** 上传结构错误文件（表头错位/空/超行数）→ 弹窗不关闭，展示对应 `IMPORT_*` 错误文案与 trace_id，可换文件重传。
- **AC6** 进入导入任务页：默认查第 1 页倒序，首条 SSE 连接建立，连接徽标从 connecting→sse；分页 total 与响应一致。
- **AC7** 造一个含错误行的文件 → 任务从 RUNNING 进度推进（SSE 实时）→ 收敛 PARTIAL；Tag 显示「部分成功」、成功/跳过计数与后端一致、进度 100、错误摘要可见、「错误报告」按钮出现且可下载 xlsx。
- **AC8** 全合法文件 → 收敛 SUCCEEDED「全部成功」、跳过 0、无错误报告按钮、无重试按钮。
- **AC9** 构造 FAILED 任务 → Tag「失败」+ 悬浮错误原因 + 出现「重试」；点重试经 `Popconfirm` 确认后 POST retry，202 回 PENDING，随后 SSE/轮询推进。PARTIAL/SUCCEEDED 行不出现重试。
- **AC10** 版本栅栏：注入两条同 job 事件（version 新→旧乱序），旧事件不覆盖新状态。🧪
- **AC11** 轮询降级：断 SSE（停 Rabbit/模拟 onerror 连续失败≥3）→ 徽标转「轮询中」，有进行中任务时按可见性轮询；恢复后回 sse 并先校准。🧪 + 联调
- **AC12** 列表加载失败（停后端）→ 上次数据保留 + 错误 Alert +「重新加载」，点重载恢复。
- **AC13** 纯逻辑单测（事件校验/栅栏/乐观更新/轮询间隔/预检/上传请求构建）覆盖；`npm test`、`npm run build` 通过。
- **AC14** 与导出页共存回归：切换订单/导出/导入三页互不干扰，导出页行为不变，导航与页题正确显示「导入任务」。
