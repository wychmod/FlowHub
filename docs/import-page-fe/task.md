# 订单导入前端（上传入口 + 导入任务页 + 实时进度）Task

> 状态：**草案待评审**（2026-09-10；阶段三）。上游：`spec.md`（F1–F19 / AC1–AC14）、`plan.md`（文件清单与数据结构）。
> 执行约定：按 T 顺序推进，**纯逻辑测试先行**（先写 `*.test.ts` 断言，再实现使之为绿）；每完成一组 `frontend/` 改动跑 `npm test && npm run build`（受仓库前端 hook 约束）。🧪=node/jsdom 可先行；🌐=需后端就绪联调（后端已实现，多数 🌐 现在即可验）。

## 阶段 A · API 防腐层（无 UI 依赖，最先）

- [ ] **T1** 🧪 新建 `api/importApi.ts` 类型：`ImportJobStatus` / `ImportJobItem` / `ImportJobPage` / `ImportJobAccepted` / `ImportJobEvent` / `ImportErrorSummaryItem`（对齐 §5.2/5.3/5.6）。
- [ ] **T2** 🧪 先写 `api/importApi.test.ts`：以 stub fetch 断言
      - `listImportJobs` URL/查询参数（含可选 status）、Envelope 解包取 `data`；
      - `uploadOrderImport` 发 `POST /api/v1/import-jobs`、body 为 `FormData` 且 `file` 字段存在、**不手动设 Content-Type**、非 2xx 经 `asEnvelope`+`envelopeToError` 抛 `ApiError`（带 code/traceId）；
      - `downloadImportTemplate`/`downloadImportErrorReport`：`!ok → parseBlobError`、`ok → saveBlob`（文件名兜底链）；
      - `retryImportJob` POST 方法与 URL。
- [ ] **T3** 实现 `api/importApi.ts` 五个方法 + `importEventsUrl`（使 T2 全绿）。

## 阶段 B · SSE Hook

- [ ] **T4** 🧪 新建 `features/import-jobs/types.ts`：`ImportEventConnectionState`（与导出结构一致）。
- [ ] **T5** 🧪 先写 `useImportEvents.test.tsx`（jsdom + mock `EventSource`）：连接态迁移、`import.partial` 事件处理、`jobId:version` 栅栏乱序丢弃、乐观更新仅覆盖携带字段、错误字段 hasOwnProperty（缺失保留 / null 清空）、连续 3 次失败切 polling、`invalidateQueries(['importJobs'])`。用例对齐 `useExportEvents.test.tsx` 覆盖面。
- [ ] **T6** 实现 `useImportEvents.ts`：逐段照搬 `useExportEvents`，仅替换 queryKey、事件名（含 `import.partial`）、payload 类型与新增字段更新（使 T5 全绿）。

## 阶段 C · 导入任务页

- [ ] **T7** 新建 `features/import-jobs/importConstants.ts`：`IMPORT_STATUS_META`（含 PARTIAL「部分成功」gold）。
- [ ] **T8** 复用化改造 `ConnectionBadge`：prop 类型放宽为结构化的连接态（或移至共享层），使导出/导入两页共用不复制。回归：导出页徽标不变。
- [ ] **T9** 新建 `ImportJobsPage.tsx`：复刻 `ExportJobsPage` 骨架（useQuery `['importJobs',{page,page_size}]` + `keepPreviousData` + 三条件 `refetchInterval` + `useImportEvents(true)`）。
- [ ] **T10** 列定义：任务编号/文件名/状态（FAILED 悬浮原因、PARTIAL 特殊色）/进度/结果统计（succeeded/skipped + error_summary 悬浮）/创建·完成时间/操作。
- [ ] **T11** 操作：PARTIAL 且 `error_report_available`→错误报告下载；FAILED→重试 Popconfirm；`busyJobId` loading；`errorMessage`（含 trace_id）+ actionError/列表 error 双 Alert。空态文案引导「去订单管理导入」。
- [ ] 🧪 建议对「轮询间隔判定」「错误摘要渲染」等抽为纯函数补最小单测（对齐 N5）。

## 阶段 D · 上传入口

- [ ] **T12** 🧪 先写 `uploadGuards.test.ts`：`checkUploadFile` 对 `.xls`/`.csv`/无后缀、>10MB、恰好 10MB、合法 `.xlsx` 的边界判定。
- [ ] **T13** 实现 `features/import-jobs/uploadGuards.ts`（使 T12 全绿）。
- [ ] **T14** 新建 `features/orders/components/ImportModal.tsx`：模板下载链接（`downloadImportTemplate`）+ `Upload.Dragger`（`beforeUpload`→`checkUploadFile`，返回 false 手动持有 file）+「开始导入」→`useMutation(uploadOrderImport)`。
- [ ] **T15** 成功态：展示 `ImportJobAccepted`（文件名 + total_rows）+「查看导入任务」→`onNavigate('import-jobs')` 并关窗；失败态：Alert 展示 `IMPORT_*`/错误 + trace_id，弹窗不关、可重传。
- [ ] **T16** `OrderListPage.tsx` 工具栏接入「导入订单」按钮与 `ImportModal`，扩展 `onNavigate` 支持 `import-jobs`。

## 阶段 E · 导航接入

- [ ] **T17** `AppLayout.tsx`：`PageKey` 增 `'import-jobs'`、`PAGE_ICONS['import-jobs']=<ImportOutlined/>`。
- [ ] **T18** `layoutMeta.ts`：`PAGE_META['import-jobs']={label:'导入任务'}`。
- [ ] **T19** `App.tsx`：页面切换分支接入 `<ImportJobsPage/>`。

## 阶段 F · 联调与收尾

- [ ] **T20** 🌐 端到端手工链路（对应 checklist 场景）：下载模板→填几行含错误→上传 202→RUNNING 进度→PARTIAL→下载错误报告→修正重传→SUCCEEDED；FAILED 重试；停后端验证降级/错误兜底。
- [ ] **T21** 🧪 `npm test` 全绿（新增 importApi/useImportEvents/uploadGuards 用例 + 既有不变）；`npm run build`（tsc）通过。
- [ ] **T22** 回归：订单页/导出页行为不变；三页切换与页题正确；Console 无 antd 上下文告警（`message` 经 `App.useApp()`）。
- [ ] **T23** 文档同步：后端 `order-import-design.md` §10 前端 F1–F5 勾选完成；`README.md`「前端导入任务页」从「未开发」迁入「已实现」；`AGENTS.md` 相关差异段更新。

## 交付物清单

- 新增：`api/importApi.ts(+test)`、`features/import-jobs/{types,useImportEvents(+test),importConstants,uploadGuards(+test),ImportJobsPage}.tsx?`、`features/import-jobs/components/*`、`features/orders/components/ImportModal.tsx`。
- 改动：`app/AppLayout.tsx`、`app/layoutMeta.ts`、`App.tsx`、`features/orders/OrderListPage.tsx`、（可能）`exports/components/ConnectionBadge.tsx` 复用化。
- 不新增第三方依赖。

## 风险与前置

- `error_summary` 为后端 JSON 字段，形态以实际响应为准（`[{reason,count}]`）；若为对象 map，`ImportErrorSummaryItem[]` 归一在 API 层适配，页面不感知。
- `file_size_bytes` 对导入可能恒为 null（设计示例中该字段 null），列展示对导入可省略文件大小、聚焦行计数——以联调实际返回裁剪列。
- SSE 事件名（`import.partial` 等）以后端 `ImportSseService` 广播名为准，联调首条即校准监听器名称。
