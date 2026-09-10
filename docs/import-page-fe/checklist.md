# 订单导入前端（上传入口 + 导入任务页 + 实时进度）Checklist

> 状态：**草案待评审**（2026-09-10；阶段四）。上游：`spec.md`（AC1–AC14）、`plan.md`、`task.md`。开发按 task.md 执行时以本清单验收。

## 验收前置

| 项 | 要求 |
|---|---|
| 后端 | 本机 3306 MySQL（`flowhub` 库）；后端 8080 启动（`.\mvnw.cmd spring-boot:run`）。RabbitMQ/Redis 建议起（SSE/进度更真实；不起则验证降级路径）|
| 前端 | `npm run dev`，访问 http://localhost:5174，开 DevTools Network + Console |
| 测试夹具 | 用「下载模板」得到合法 `.xlsx`；复制若干行、故意把某行订单状态改成非法值 / 塞重复订单号 / 改表头列名，分别造出 SUCCEEDED、PARTIAL、结构 400 三类样本 |
| 失败注入 | 「停后端」用于 AC2/AC11/AC12；「停 RabbitMQ」用于 AC11 降级 |

标注约定：🧪=单测/构建可先行验证；🌐=需后端就绪联调（后端已实现，现即可验）；其余为纯前端可验证项。

## 功能验收

- [ ] **AC1 入口存在**：订单列表页出现「导入订单」按钮；点开弹窗含模板下载链接 + 拖拽上传区，文案提示「仅 .xlsx、≤10MB、表头 9 列」。
- [ ] **AC2 模板下载** 🌐：点下载 → 浏览器保存 `.xlsx`（「订单数据」+「填写说明」两 Sheet）；停后端再点 → 就地 `message.error` 含 trace_id，弹窗不崩。
- [ ] **AC3 预检拦截**：拖 `.csv`/`.xls`/`.txt` 或 >10MB 文件 → Network **无** `POST /import-jobs` 请求，出现本地提示。🧪 `checkUploadFile` 边界由 `uploadGuards.test.ts` 覆盖（含恰好 10MB、大小写后缀）。
- [ ] **AC4 受理成功**：拖合法文件点「开始导入」→ Network 见 `POST /api/v1/import-jobs`（multipart，字段 `file`，**请求头无手动 `Content-Type`**、有 `Accept: application/json`），响应 202；弹窗显示文件名与 `total_rows` + 「查看导入任务」。🧪 `importApi.test.ts` 断言请求形态与 Envelope 解包。
- [ ] **AC5 受理失败**：上传表头错位/空/超行数文件 → 对应 `IMPORT_TEMPLATE_MISMATCH`/`IMPORT_EMPTY_FILE`/`IMPORT_TOO_MANY_ROWS` 文案 + trace_id，弹窗**不关闭**、可换文件重传。🌐
- [ ] **AC6 页面与默认查询** 🌐：从弹窗「查看导入任务」或直接点导航进入导入页；首条列表请求 `/api/v1/import-jobs?page=1&page_size=10`；SSE 建连、徽标 connecting→sse；total 与响应一致；页题为「导入任务」。
- [ ] **AC7 PARTIAL 全链路** 🌐：上传含 N 行错误（非法状态/重复订单号）的文件 → 进度 RUNNING 推进（`import.progress`，SSE 实时，percent 封顶 99）→ 收敛 PARTIAL：Tag「部分成功」(gold)、`成功/跳过`计数与后端一致、percent=100、`error_summary` Top N 悬浮可见、「错误报告」按钮出现 → 点击下载 `errors-attempt-N.xlsx` 成功（Network 见 GET error-report）。
- [ ] **AC8 SUCCEEDED** 🌐：全合法文件 → Tag「全部成功」、跳过 0、无错误报告按钮、无重试按钮。
- [ ] **AC9 重试语义** 🌐：FAILED 行 Tag「失败」+ 悬浮错误码/原因 + 「重试」；点重试经 `Popconfirm` → `POST /import-jobs/{id}/retry` 202 回 PENDING → SSE/轮询推进。PARTIAL/SUCCEEDED/EXPIRED 行**不出现**重试按钮。
- [ ] **AC10 版本栅栏** 🧪：`useImportEvents.test.tsx` 注入同 job 两条事件（version 新→旧），旧事件被丢弃，缓存行不回退。
- [ ] **AC11 轮询降级** 🧪+🌐：单测覆盖连续 3 次失败 → mode=polling；联调停 RabbitMQ/模拟 onerror → 徽标转「轮询中」，有 PENDING/RUNNING 时可见 3s / 隐藏 15s 轮询；恢复后回 sse 且先 `invalidateQueries` 校准。
- [ ] **AC12 列表失败兜底**：停后端点刷新/换页 → 上次数据保留 + 错误 Alert +「重新加载」；点重载恢复。
- [ ] **AC13 单测与构建** 🧪：`npm test` 全绿（新增 `importApi.test.ts`/`useImportEvents.test.tsx`/`uploadGuards.test.ts` + 既有全绿）；`npm run build`（tsc）通过。
- [ ] **AC14 共存回归** 🧪+🌐：订单/导出/导入三页切换互不干扰；导出页 `useExportEvents` 与徽标行为不变；导航三项与页题正确；`ConnectionBadge` 复用化后导出页渲染一致。

## 集成检查

- [ ] 每次 `frontend/` 改动后 `npm test && npm run build` 均通过，无跳过。
- [ ] Console 无报错、无 antd 静态方法上下文告警（`message`/`modal` 经 `App.useApp()`）。
- [ ] 未引入新第三方依赖（`package.json` dependencies 无变化）。
- [ ] `importApi.ts` 未重复实现 `asEnvelope`/`envelopeToError`/下载分流（均走 `http.ts`/`download.ts`）。
- [ ] 布局无抖动：换页/刷新/SSE 更新时表格列宽、滚动条位置、行高稳定（`tableLayout:fixed` + `scroll.y` + `scrollbar-gutter:stable` + `keepPreviousData`）。

## 端到端场景

- [ ] **场景 1 部分成功闭环** 🌐：下载模板 → 复制行并注入 3 类错误（非法状态/金额负数/重复订单号各若干）→ 上传 202 → 进入导入页看进度 → PARTIAL → 下载错误报告 → 对照报告修正 → 重传 → SUCCEEDED。
- [ ] **场景 2 结构错误秒回** 🌐：改表头列名上传 → 弹窗内即得 `IMPORT_TEMPLATE_MISMATCH`，不产生任务。
- [ ] **场景 3 通道降级** 🌐：跑一个大导入中途停 RabbitMQ → 徽标转轮询、进度仍随轮询推进 → 恢复 RabbitMQ → 回 sse 实时。
- [ ] **场景 4 失败重试**：构造 FAILED → 重试 → 观察回 PENDING 并再走一遍管道。

## 回归检查

- [ ] 既有 `exportApi.test.ts`/`useExportEvents.test.tsx`/`download.test.ts`/`http.test.ts` 全绿。
- [ ] 导出任务页端到端（列表/下载/重试/进度）行为与改造前一致。
- [ ] 订单列表页原有筛选/排序/勾选/导出入口不受影响，仅新增导入按钮与弹窗。

## 失败标准

- 任一非 🌐 检查项不通过 → 验收不通过，就地修复后重验该项及关联项。
- 🌐 项须后端就绪实测通过；暂不可验（如缺 RabbitMQ 环境）→ 留痕说明，不算通过。
- 出现 plan.md 之外的新模块/新依赖/新端点消费 → 视为越界，回 plan.md 走变更控制。
- PARTIAL 被实现成可重试或按失败红色呈现 → 直接判失败（违反 spec 核心语义原则 4）。
