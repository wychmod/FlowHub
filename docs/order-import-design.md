# 订单 Excel 导入功能详细设计（可执行）

## 1. 文档定位

本文是 `order-import-plan.md` 的可执行落地详细设计。5 个关键决策点已拍板，现状与目标如下：

| 文档属性 | 内容 |
| --- | --- |
| 前置 | `docs/order-import-plan.md`（规划定稿，5 决策已拍板） |
| 代码基线 | `master @ 4244e1c` |
| 范围 | 订单 Excel 导入全链路：模板下载 → 上传受理（文件级/结构级三层校验）→ 异步行级校验+批量入库 → PARTIAL 错误报告 → SSE 进度 → 前端导入任务页 |
| 明确排除 | 鉴权/权限、对象存储、多实例、导入文件二次编辑留痕、错误行增量导入（见 §12） |
| 交付物形态 | 后端 `order-import/` 模块 + Flyway V9 + 前端 `features/import-jobs/` 独立页 |

**已拍板决策（引用 plan §8，全文生效）**：

1. 订单号冲突（与库内已有订单重复）→ **冲突行记入错误报告并跳过**，不整体失败。
2. 校验失败裁决 → **有效行照常导入、错误行跳过**，任务引入 **PARTIAL 部分成功**语义 + `skipped` 统计。
3. 行数上限 → `import.max-rows` 默认 **100,000** 行（可配置）。
4. 下单时间单元格 → **固定类型**：严格只认 `yyyy-MM-dd HH:mm:ss` 文本，不兼容 Excel 日期单元格。
5. 导入任务页落点 → **独立「导入任务」页**。

**关键派生结论（决策 2 扩散）**：状态机 `PENDING → RUNNING → SUCCEEDED | PARTIAL | FAILED`，FAILED 仅保留给执行/基础设施异常；行级错误一律走 PARTIAL。

---

## 2. 状态机与任务流转

### 2.1 状态机

```
PENDING ──claimPendingJob 条件抢占──▶ RUNNING ──markSucceeded（全成功）──▶ SUCCEEDED ──过期清理──▶ EXPIRED
                                              ──markPartial（有跳过行）──▶ PARTIAL  ──过期清理──▶ EXPIRED
                                              ──markFailed（执行异常）──▶ FAILED  ──人工重试──▶ PENDING
```

- **SUCCEEDED**：`skipped_rows == 0`（全部行入库成功），无可下载错误报告。
- **PARTIAL**：`skipped_rows > 0`（有效行已入库 + 错误行跳过 + 错误报告 xlsx 已生成），任务不失败。
- **FAILED**：仅在**执行/基础设施异常**时收敛（SAX 解析 IO 异常、Rabbit 消费壳未捕获异常、抢占后执行抛异常），与行级错误无关。
- **EXPIRED**：上传原件 / 错误报告到达保留期（`import.files.retention-hours` 默认 24h）后清理。

**PARTIAL 不可人工重试**（重试仅 FAILED → PENDING）：PARTIAL 已是可交付的终态，用户下载错误报告、修正错误行后作为**新任务**重新上传。避免「PARTIAL 重试生成仅含错误行的新文件」这类复杂路径（列入 §12 不做）。

### 2.2 终态判定（执行体收尾处）

```
解析结束时：
  skipped_rows == 0  → markSucceeded（同事务收敛 Job + Attempt SUCCEEDED）
  skipped_rows >  0  → 先生成错误报告 xlsx → markPartial（同事务收敛 PARTIAL + 回填 error_report_path + expired_at）
```

---

## 3. 表结构 DDL（Flyway V9）

新增 2 张表，`outbox_events` 复用现有表（新增 `aggregate_type=IMPORT_JOB` 事件）。

### 3.1 `import_jobs`

```sql
CREATE TABLE import_jobs (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_no              VARCHAR(32)              NOT NULL,
  -- 状态机：PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/EXPIRED
  status              VARCHAR(16)              NOT NULL,
  -- 乐观锁，供 SSE/HTTP 校准与并发收敛 guard
  version             INT                      NOT NULL DEFAULT 0,
  -- 上传元信息
  file_name           VARCHAR(255)             NOT NULL,               -- 原始上传文件名（含 .xlsx）
  file_path           VARCHAR(512)             DEFAULT NULL,           -- 上传原件落盘路径（受控 importRoot）
  total_rows          INT                      NOT NULL DEFAULT 0,     -- 数据行总数（受理时结构级扫描确定）
  processed_rows      INT                      NOT NULL DEFAULT 0,     -- 已处理行数（进度推进）
  succeeded_rows      INT                      NOT NULL DEFAULT 0,     -- 成功入库行数
  skipped_rows        INT                      NOT NULL DEFAULT 0,     -- 跳过行数（错误行）
  error_report_path   VARCHAR(512)             DEFAULT NULL,           -- PARTIAL 时错误报告 xlsx 路径
  error_summary       JSON                     DEFAULT NULL,           -- 错误分类 Top N（生成错误报告时统计）
  -- 缓存状态明细列（version 记录的 JSON 载体）
  cache_snapshot      JSON                     DEFAULT NULL,           -- 备用；非本版必建，见 §10 注记
  -- 执行态
  attempt_count       INT                      NOT NULL DEFAULT 0,
  started_at          DATETIME                 DEFAULT NULL,
  last_heartbeat_at   DATETIME                 DEFAULT NULL,
  lease_expires_at    DATETIME                 DEFAULT NULL,
  finished_at         DATETIME                 DEFAULT NULL,
  expired_at          DATETIME                 DEFAULT NULL,
  error_code          VARCHAR(64)              DEFAULT NULL,
  error_message       VARCHAR(500)             DEFAULT NULL,
  created_at          DATETIME                 NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_import_jobs_job_no (job_no),
  KEY idx_import_jobs_status (status),
  KEY idx_import_jobs_created (created_at)
);
```

> **说明**：`import_jobs` **不建幂等键/request_hash 列**——创建接口不引入幂等键（plan §4 F7），重复上传 = 两个独立任务，数据层靠 `orders.order_no` 唯一约束兜底。`error_summary` JSON 存 `[{reason, count}]` Top N，供列表派生字段展示。

### 3.2 `import_job_attempts`

与 `export_job_attempts` 对称，Attempt 是执行事实/审计记录：

```sql
CREATE TABLE import_job_attempts (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id        BIGINT        NOT NULL,
  attempt_no    INT           NOT NULL,
  -- RUNNING/SUCCEEDED/PARTIAL/FAILED
  status        VARCHAR(16)   NOT NULL,
  error_code    VARCHAR(64)   DEFAULT NULL,
  error_message VARCHAR(500)  DEFAULT NULL,
  started_at    DATETIME      DEFAULT NULL,
  finished_at   DATETIME      DEFAULT NULL,
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_attempt_import_job_no (job_id, attempt_no),
  KEY idx_attempt_import_job (job_id)
);
```

### 3.3 Flyway 迁移

- `V9__add_order_import_tables.sql`：创建上述 2 表。
- 不新增 outbox 表结构变更；`outbox_events.aggregate_type` 为 VARCHAR 现有列，直接插入 `IMPORT_JOB`。

---

## 4. 后端模块结构

垂直自治模块 `order-import/`，横切能力只放 `common/web/`：

```
order-import/
├── controller/ImportJobController.java        # 6 个端口 + SSE
├── dto/UploadOrderImportRequest.java          # multipart（本版无 body DTO，仅 @RequestPart file）
├── dto/ListImportJobsRequest.java             # 分页 + 可选 status 过滤
├── dto/ImportJobPageResp.java                 # 列表响应载体
├── command/ImportColumn.java                  # 导入列白名单（复用/映射 ExportColumn 9 列）
├── service/ImportJobService.java              # 创建受理/幂等空置/行列查询/claim/mark* 收敛
├── service/ImportExecutionService.java        # 执行壳：SAX 流式读 + 批校验 + 批量入库 + 收尾
├── service/ImportProgressService.java         # 条件 UPDATE 推进 + Redis 投影
├── service/ImportSseService.java              # AFTER_COMMIT SSE 广播
├── service/ImportMaintenanceService.java      # 恢复/过期清理/孤儿对账/重试
├── service/ImportFileService.java             # 受控 importRoot + 上传落盘 + 错误报告落盘 + 下载解析
├── excel/ExcelImportReader.java               # POI XSSF SAX 流式读（核心新增）
├── excel/ImportTemplateWriter.java            # 模板 SXSSF（复用 ExcelExportWriter 骨架）
├── excel/ImportErrorReportWriter.java         # 错误报告 SXSSF
├── mapper/ImportJobMapper.java                # CRUD + 条件收敛 SQL
├── mapper/ImportJobAttemptMapper.java         # Attempt 审计
├── mapper/ImportOrderMapper.java              # 冲突预查 + 批量 INSERT orders
├── entity/ImportJobEntity.java                # record，映射 import_jobs
├── entity/ImportJobAttemptEntity.java
├── entity/ImportOrderRow.java                 # 解析出的待导入行值对象
├── event/ImportJobChanged.java                # 应用事件
├── event/ImportJobEventPayload.java           # SSE payload
├── error/ImportErrorCode.java                 # 模块错误码
└── mq/ImportJobMessage.java                   # IMPORT_JOB_CREATED 最小契约消息
```

---

## 5. 接口契约（后端）

所有 JSON 接口统一 Envelope；参数校验失败 400 `VALIDATION_ERROR`；控制器构造器注入 + `@ApiV1` + 相对路径（`ApiWebMvcConfiguration` 自动拼接 `/api/v1`）。

> **落地注记**：设计阶段端点前缀拟为 `/order-imports`，实现时统一调整为 `/import-jobs`（与导出 `/export-jobs` 命名对称），本文 URL 已按落地回填。

### 5.1 `GET /import-jobs/template`（模板下载）

返回 xlsx 模板文件流（`@RawResponse`，成功二进制 / 失败 Envelope 分流，前端 `download.ts` 复用）。
模板含 2 Sheet：
- **订单数据**：纯表头 9 列（`ImportColumn` 顺序 + 中文标题与 `ExcelExportWriter.buildColumns` 严格一致），冻结首行 + 列宽 + 金额列 `0.00` 数值格式 + 枚举列（订单状态/销售渠道/币种）`DataValidation` 下拉约束。
- **填写说明**：每列必填性/格式示例/枚举取值/常见错误提示文本。

### 5.2 `POST /import-jobs`（上传受理，202）

- 请求：multipart，`file` 字段（`.xlsx`）。
- 校验成功后返回 **202** + `ImportJobAcceptedVO`。
- **无 `Idempotency-Key`**（plan §4 F7：导入不引入幂等键）。

错误码（同步段，400，任一失败不落盘不建任务）：
| 错误码 | 触发 |
| --- | --- |
| `IMPORT_FILE_TOO_LARGE` | 文件 > `import.file.max-size`（容器层已拦截时此兜底） |
| `IMPORT_FORMAT_NOT_SUPPORTED` | 非 `.xlsx` 后缀 |
| `IMPORT_FILE_CORRUPTED` | 前 2 字节非 `PK`（ZIP 魔数）/ 打开失败 |
| `IMPORT_TEMPLATE_MISMATCH` | 表头 9 列名称或顺序不一致（附第一个错位列位置） |
| `IMPORT_EMPTY_FILE` | 无数据行（仅表头） |
| `IMPORT_TOO_MANY_ROWS` | 数据行数 > `import.max-rows` |

`ImportJobAcceptedVO`（202 `data`）：
```json
{
  "job_id": 42,
  "job_no": "IMP202609090001",
  "status": "PENDING",
  "total_rows": 98210,
  "file_name": "orders.xlsx",
  "created_at": "2026-09-09T10:20:30"
}
```

### 5.3 `GET /import-jobs`（列表分页）

参数：`page` / `page_size` / `status`（可选，大小写不敏感白名单校验，非法 400 `VALIDATION_ERROR`，同导出列表）。
返回 `ImportJobPageResp`，列表项派生字段与 SSE payload 对齐：

```json
{
  "job_id": 42, "job_no": "IMP202609090001", "status": "PARTIAL",
  "job_version": 6, "total_rows": 98210, "processed_rows": 98210,
  "succeeded_rows": 98012, "skipped_rows": 198,
  "progress_percent": 100, "error_report_available": true,
  "error_summary": [{"reason": "订单状态非法", "count": 120}, {"reason": "订单号冲突", "count": 78}],
  "file_name": "orders.xlsx", "file_size_bytes": null,
  "created_at": "...", "finished_at": "...", "expired_at": "..."
}
```

### 5.4 `GET /import-jobs/{job_id}/error-report`（错误报告下载）

- 仅 `PARTIAL` 且 `error_report_path` 存在可下载；成功二进制文件流 / 失败 Envelope 分流。
- 错误返回：`IMPORT_JOB_NOT_FOUND`（404）、`IMPORT_ERROR_REPORT_NOT_AVAILABLE`（409/404，非 PARTIAL 或无报告）。

### 5.5 `POST /import-jobs/{job_id}/retry`（人工重试）

- 仅 `FAILED` 且 `attempt_count < MAX_ATTEMPTS(3)` → 条件重置回 `PENDING` 并同事务写新 Outbox 事件；202 受理。
- 错误返回：`IMPORT_JOB_NOT_FOUND`（404）、`IMPORT_JOB_NOT_RETRYABLE`（409）。
- PARTIAL/SUCCEEDED 不重试。

### 5.6 `GET /import-jobs/events`（SSE）

独立事件流（第 5 个端点决策暂定独立，见 §12 权衡）。5 类事件：
`import.progress` / `import.succeeded` / `import.partial` / `import.failed` / `heartbeat`；事件 id = `jobId:version` 版本栅栏；15s 心跳。

SSE payload `ImportJobEventPayload`（参考 `ExportJobEventPayload.from`，字段存在性即协议，NON_NULL）：

```json
// import.partial 事件 data：
{
  "job_id": "42", "job_version": 6, "status": "PARTIAL",
  "processed_rows": 98210, "total_rows": 98210,
  "succeeded_rows": 98012, "skipped_rows": 198,
  "progress_percent": 100, "error_report_available": true,
  "occurred_at": "2026-09-09T10:22:00"
}
// import.failed 事件 data 额外显式带 error_code / error_message；succeeded/failed 终态不带 succeeded/skipped 计数省略则按住 status 由列表校准
```

---

## 6. 三层校验与处理管道（后端核心）

### 6.1 同步对：文件级 + 结构级（`ImportJobService.createJob` / HTTP 线程内）

```
multipart(file)
 → FileValidators.checkSize（≤ max-size）
 → FileValidators.checkExtension（仅 .xlsx）
 → FileValidators.checkMagic（前 2 字节 PK）
 → 受控落盘 importRoot（ImportFileService.persistUpload）
 → ExcelImportReader.scanStructure(file)：SAX 轻量全扫
     ├ 表头 9 列名称/顺序比对（与 ImportColumn 列序表）→ 不一致 IMPORT_TEMPLATE_MISMATCH + 定位列号
     ├ 数据行计数 → 0 行 IMPORT_EMPTY_FILE
     ├ > import.max-rows → IMPORT_TOO_MANY_ROWS（尽早拦截，不建任务）
     └ 回填 total_rows
 → @Transactional 同事务 INSERT import_jobs(PENDING,total_rows,file_path) + outbox_events(IMPORT_JOB_CREATED)
 → 202
```

**为什么结构级做轻量全扫**：SAX 只做表头回调与行计数，不实例化单元格对象，10 万行毫秒级完成，可在同步受理中安全执行；同时把「行数超限」与「空文件」前移到受理前拦截，满足 plan F3「倾向受理前拦截」且确定 `total_rows`。

### 6.2 异步对：行级校验 + 批量入库（`ImportExecutionService` / 消费线程）

`ExcelImportReader`（**POI XSSF SAX 流式读**）按批回调，每批 500~1000 行，内存恒定：

```
SAX 逐行回调 → 每攒满 batch-size 行触发一次：
  1. 行级校验（ImportRowValidator.validate）：对每行产出 {通过 | 错误明细列表}
  2. 文件内查重（HashSet<order_no>，跨批持有）
  3. 冲突预查：SELECT order_no FROM orders WHERE order_no IN (本批通过订单号)
       └ 已存在 → 归入跳过集（原因「订单号与库内已有单据重复」，决策 1）
  4. 剩余通过行 insertBatch（ImportOrderMapper）
  5. 跳过行 → ImportErrorBuffer 缓冲（cap error-report-max-rows）+ skipped_rows++
  6. ImportProgressService.report(processed_rows, succeeded_rows, skipped_rows) 推进
解析结束（无更多批）：
  skipped_rows == 0 → markSucceeded
  skipped_rows >  0 → ImportErrorReportWriter 生成错误报告 xlsx → markPartial
```

**行级校验规则表**（每行逐列）：

| 列 | 规则 | 失败文案示例 |
| --- | --- | --- |
| 订单号 | 非空；trim ≤ 64；文件内唯一 | 「订单号为空」「订单号过长」「订单号在文件内重复」 |
| 订单状态 | 大小写不敏感枚举 `PENDING/PAID/SHIPPED/COMPLETED/CANCELED` | 「订单状态非法」 |
| 销售渠道 | 枚举 `WEB/APP/STORE/PARTNER` | 「销售渠道非法」 |
| 客户姓名 | 非空；≤ 128 | 「客户姓名为空」「客户姓名过长」 |
| 客户电话 | 可空；非空 ≤ 32（宽松，仅长度） | 「客户电话过长」 |
| 订单金额 | 必填；非负；≤ 2 位小数；DECIMAL(18,2) 上下界（`compareTo`） | 「订单金额必须为非负数字」「订单金额最多 2 位小数」 |
| 币种 | 白名单枚举（对齐 `seed-demo-data.sql` 币种口径） | 「币种非法」 |
| 收货省份 | 可空；非空 ≤ 64 | 「收货省份过长」 |
| 下单时间 | **固定文本** `yyyy-MM-dd HH:mm:ss` 严格 `DateTimeFormatter` 解析 | 「下单时间须为 yyyy-MM-dd HH:mm:ss 文本」 |
| 横切 | 文本以 `= + - @` 开头判公式注入 | 「内容以 = 开头，可能为公式注入」 |

> 枚举解析一律委托 `ParamUtils.enumFromName`（大小写不敏感）；`BigDecimal` 比较一律 `compareTo`；错误按行聚合（多列错误合并一条记录，原因用「；」连接）。

### 6.3 冲突预查 vs 唯一约束

- **主路径**：每批 INSERT 前 `SELECT order_no ... IN` 预查，把与库内已有订单重复的行干净地归入跳过集（决策 1），避免依赖 INSERT 撞键后清异常。
- **最终防线**：`orders.order_no` 唯一约束——即使预查后仍撞键（并发写入），`DuplicateKeyException` 由执行壳兜底捕获，冲掉的批重试一次或该行转跳过，保证绝不产生重复订单。

### 6.4 错误缓冲与错误报告

- `ImportErrorBuffer`：有界缓冲（上限 `import.error-report-max-rows` 默认 5000），存 {excelRowNo, orderNo, columns, reasons}。超限只记 `skipped_rows`（如实统计），错误报告截断前 5000 条并注明「仅展示前 N 条」。
- 错误报告 xlsx（SXSSF）：列 = **Excel 行号 / 订单号 / 错误列 / 错误原因**；生成时同步统计 Top N 错误分类写 `error_summary` JSON；落受控 `importRoot/<UTC 日期>/<jobId>/errors-attempt-N.xlsx`。

---

## 7. 异步管道（复用 Outbox + Rabbit + Redis + SSE）

**整体复刻 `export/` 已验证的模式，仅换事件类型与业务 bean。**

### 7.1 Outbox 投递

- `ImportJobService.createJob` 与 `outbox_events` 同事务写：`aggregate_type=IMPORT_JOB`、`aggregate_id=import_jobs.id`、`event_type=IMPORT_JOB_CREATED`、payload 含 `job_id/job_no/file_name/trace_id`。
- 既有 `OutboxDispatcher` **零改动**：按 `published_at IS NULL` 通用捞取，发送 `ImportJobMessage`（`schema_version=1` / `message_id` 由 outbox 事件 id `UUID.nameUUIDFromBytes` 稳定派生 / `job_id` / `event_version=1`），Confirm ACK 且无 Returned 才回填，失败保留下轮补发（至少一次投递）。

### 7.2 消费端（`ImportJobConsumer`）

- `@RabbitListener(IMPORT_JOB_QUEUE, ackMode="MANUAL", concurrency="2")`。
- 流程与 `ExportJobConsumer` 完全一致：trace 恢复/新建（`MdcScope`）→ 契约不支持（schema 未知/缺 job_id）`basicReject(requeue=false)` 转 DLQ → `claimPendingJob` 抢占失败直接 `basicAck` 无副作用 → 执行壳返回后 `basicAck`；claim 事务或 Channel 异常穿出不确认（保留重投）。

### 7.3 CAS 抢占 + Attempt 审计（`ImportJobService`）

`claimPendingJob`（@Transactional）：
```
UPDATE import_jobs SET status='RUNNING', attempt_count=attempt_count+1, version=version+1,
       started_at=now, last_heartbeat_at=now, lease_expires_at=now+5min
 WHERE id=? AND status='PENDING' AND attempt_count<3
```
条件更新 0 行 → 抢占失败（重复投递/已被抢占/上限）。Attempt 以 `MAX(attempt_no)+1` 的 INSERT…SELECT 同事务插入（含 PARTIAL/FAILED 终态回写），保证回滚不留孤儿 RUNNING。

### 7.4 进度推进 + Redis 投影 + SSE

- **条件 UPDATE**（`ImportProgressService.report`）：`status='RUNNING'` 单向 + `processed_rows<=` 新值单调 + `succeeded_rows/skipped_rows` 自增 + heartbeat/lease 续期；0 行 fail-fast 抛异常走失败收敛。
- **Redis 投影**：`import:progress:<jobId>` Hash（status/processedRows/succeededRows/skippedRows/percent/updatedAt）+ TTL 48h 尽力写入，失败 `redis_progress_write_failed` 降级日志。
- **SSE**（`ImportSseService`）：`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` 提交后重读 Job 广播 5 类事件；事件 id=`jobId:version`；15s 心跳；坏连接隔离。
- **percent 语义**：RUNNING 封顶 99，SUCCEEDED/PARTIAL 才 100（`error_report_available` 用以区分 PARTIAL）。

### 7.5 恢复/重试/清理（`ImportMaintenanceService`）

- `@EventListener(ApplicationReadyEvent)`：收敛租约失效的 RUNNING（Attempt 先行、Job 收尾，FAILED `SERVICE_RESTARTED`）。
- `@Scheduled(cron=import.cleanup-cron)`：过期清理——上传原件与错误报告按保留期，「文件删除成功才 EXPIRED」，删 Redis 投影发事件；孤儿三维对账（宽限期 1h + 活跃租约 + Job/Attempt 引用检查）。

---

## 8. 前端详细设计（独立「导入任务」页）

### 8.1 技术要点

| 需求 | 技术 | 复用点 |
| --- | --- | --- |
| 上传 | antd `Upload.Dragger` + `beforeUpload` | 本地预检（仅 `.xlsx` ≤10MB）拦截不发请求 |
| multipart 通道 | **独立于 `requestJson`**：手写 `fetch` 发 `FormData`，不设 Content-Type（浏览器带 boundary）；错误收敛 `envelopeToError`→`ApiError` | 复用错误模型统一 |
| 下载 | `api/download.ts` | `parseBlobError`/`filenameFromDisposition`/`saveBlob` 直用 |
| 进度 | `EventSource` + `jobId:version` 栅栏 | 复刻 `useExportEvents` → `useImportEvents` |
| 状态 | react-query，键 `['importJobs', {page,page_size}]` | 创建后 `invalidateQueries` |

### 8.2 `api/importApi.ts`

- `uploadOrderImport(file)`：POST `/api/v1/import-jobs`（FormData，202 返回 AcceptedVO）
- `downloadOrderImportTemplate()`：GET `/api/v1/import-jobs/template`（blob）
- `listImportJobs(params)`：GET `/api/v1/import-jobs`（含 `succeeded_rows/skipped_rows/error_report_available/error_summary` 类型）
- `downloadImportErrorReport(jobId)`：GET `/api/v1/import-jobs/{jobId}/error-report`（`parseBlobError` 分流）
- `retryImportJob(jobId)`：POST `/api/v1/import-jobs/{jobId}/retry`
- `importEventsUrl`：`/api/v1/import-jobs/events`

### 8.3 `features/import-jobs/`

- `ImportJobsPage`：独立页（决策 5），布局复刻 `ExportJobsPage`——状态 Tag（**PARTIAL「部分成功」**配色）/ 进度条 / 总·成功·跳过统计 / 错误报告下载（PARTIAL 可见）/ 重试 / 分页 / `ConnectionBadge`。三条件轮询降级同导出。
- `useImportEvents`：复刻 `useExportEvents` 连接状态机 + 版本栅栏 + 乐观更新 + 失效收敛 + 生命周期清理，事件类型增加 `import.partial`。
- `types.ts`：`ImportEventConnectionState` / 列表项类型。
- 订单列表页新增「导入订单」按钮 + 弹窗（模板下载链接 + `Upload.Dragger`），受理成功提示 + 跳转导入任务页。

---

## 9. 环境 / 配置项清单

新增 `application.yml`（或 `@Value` 默认值，默认值不落 yml，测试 yml 置静默，对齐导出做法）：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `import.max-rows` | `100000` | 行数上限（决策 3） |
| `import.file.max-size` | `10MB` | 文件大小上限（与 `spring.servlet.multipart.max-file-size` 协同） |
| `import.execution.batch-size` | `1000` | SAX 批大小（500~1000 内可调） |
| `import.execution.lease-minutes` | `5` | 抢占/进度 lease 续期 |
| `import.files.dir` | `import-files/` | 受控文件根（已 `.gitignore`，按 `<UTC 日期>/<jobId>/` 分层） |
| `import.files.retention-hours` | `24` | 上传原件与错误报告保留期 |
| `import.error-report-max-rows` | `5000` | 错误报告条数上限 |
| `import.cleanup-cron` | `0 0 * * * *` | 维护服务 cron（测试置 `-`） |
| `import.sse.heartbeat-ms` | `15000` | 心跳（测试置大静默） |
| `spring.servlet.multipart.max-file-size/max-request-size` | `10MB/10MB` | 容器层拦截（主 yml） |

生产前置：MySQL、RabbitMQ（可选，未启动仅 Outbox 延迟 + 组件 DOWN）、Redis（可选，投影降级）。启动后端需本机 3306 MySQL 存在 `flowhub` 库账号。

---

## 10. 实施任务拆解（可勾选，后端在前、前端随后端接口就绪）

### 后端
- [x] **B1** Flyway V9 建 `import_jobs` / `import_job_attempts` 两表
- [x] **B2** `order-import/` 包骨架 + `ImportColumn`（引用 `ExportColumn` 9 列单一事实源）+ `ImportErrorCode`
- [x] **B3** `GET /import-jobs/template`（`ImportTemplateWriter` SXSSF + 下拉约束 + 填写说明 Sheet）
- [x] **B4** `POST /import-jobs`：multipart 接收 + 文件级校验（大小/后缀/魔数）+ `ImportFileService` 受控落盘
- [x] **B5** `ExcelImportReader` **POI XSSF SAX 流式读**（核心新增，先行 6.1 结构级扫描 + 6.2 行级回调两用）+ 结构级校验（表头/空文件/行数上限）
- [x] **B6** 创建受理链路：`@Transactional` 写 `import_jobs`(PENDING)+`outbox_events`→202（`ImportJobService.createJob`）
- [x] **B7** `ImportJobMessage` + 接通既有 `OutboxDispatcher`（新事件类型零改动）
- [x] **B8** `ImportJobConsumer` + `claimPendingJob` CAS 抢占 + Attempt 审计
- [x] **B9** 行级校验引擎（`ImportRowValidator`，含枚举/金额/时间固定格式/公式注入/文件内查重）
- [x] **B10** 冲突预查 + `insertBatch` 批量入库 + DuplicateKey 兜底转跳过
- [x] **B11** 错误缓冲 + `ImportErrorReportWriter` 错误报告生成 + PARTIAL 收敛（`markPartial`）与 `markSucceeded`/`markFailed`
- [x] **B12** 进度推进（条件 UPDATE）+ Redis 投影 + `ImportSseService` 5 类事件
- [x] **B13** `GET /import-jobs` 列表分页 + 派生字段
- [x] **B14** `GET /import-jobs/{job_id}/error-report` 下载（受控解析）
- [x] **B15** `POST /import-jobs/{job_id}/retry` 人工重试
- [x] **B16** `ImportMaintenanceService` 恢复/过期清理/孤儿对账
- [x] **B17** 后端集成测试：`ImportJobServiceTest`（受理/结构校验/冲突预查）、`ImportExecutionIntegrationTest`（SAX 批处理/终态三态/批量入库）、`ImportProgressSseIntegrationTest`（5 类事件/版本栅栏）、`ImportJobConsumerTest`（抢占/DLQ）、`ImportMaintenanceIntegrationTest`（恢复/清理/重试）

### 前端
- [x] **F1** `api/importApi.ts`（multipart 独立通道 + 下载 + 列表 + 重试 + SSE 端点）
- [x] **F2** 订单列表页「导入订单」按钮 + 弹窗（模板下载 + `Upload.Dragger`）
- [x] **F3** `useImportEvents` Hook（复刻 `useExportEvents` + `import.partial` 类型，jsdom 单测）
- [x] **F4** 独立「导入任务」页 `ImportJobsPage`（PARTIAL Tag/进度/统计/下载/重试/分页）
- [x] **F5** 前端单测 + `npm run build`（受 `check-frontend.js` hook 约束）

### 联调与收尾
- [ ] **E1** 端到端手工链路：模板下载→填错几行→上传 202→RUNNING→PARTIAL→下载错误报告→修正→重传 SUCCEEDED
- [x] **E2** 补齐 README/AGENTS.md 一节的「导入」能力更新（见 §11）

---

## 11. 文档同步

- 本文件落地后，同步更新 `AGENTS.md` 对 `order-import-plan.md` 的引用段：决策点已拍板 + 已细化 `order-import-design.md`。
- 实现某阶段交付后，把对应能力从「计划实现」迁入「已实现」清单（复用现有收尾节奏）。

---

## 12. 待后续权衡 / 明确不做

- **错误行增量导入**（下载错误报告修正后仅重传错误行）：本次不做；错误报告 xlsx 仅含「行号/订单号/错误列/原因」，不含原始数据，无法直接回灌，需另行设计「原始行 + 错误标注」载体。列为后续。
- **SSE 端点合并**（导入导出统一事件流）：本次暂定独立端点（前端第二个 EventSource），与导出合并留待架构复盘。
- **错误明细落库**：本次错误明细用内存缓冲 + xlsx 持久化，不建 `import_order_errors` 表；若后续需要「按错误类别统计/检索」，再引入明细表。
- **导入时间兼容 Excel 日期单元格**：决策 4 已明确不做。
- **`cache_snapshot`/“version JSON 记录”**：非本版必建，导入执行数据全部在列字段，表结构已留 JSON 列位备用。

---

## 13. 验收标准摘要

- **正例**：全合法文件 → SUCCEEDED，行数一致，`skipped_rows=0`。
- **部分失败**：混入 N 行错误 → PARTIAL，`succeeded_rows + skipped_rows == total_rows`，错误报告可下载，报告内行号与实际 Excel 行对齐（含表头偏移）。
- **冲突**：重复订单号（文件内/库内）→ 跳过并入错误报告，不整体失败。
- **结构**：表头错位/空文件/超 10 万行 → 400 同步拦截，不建任务。
- **进度/通知**：RUNNING percent 封顶 99，PARTIAL/SUCCEEDED 才 100；SSE 事件乱序被 `jobId:version` 栅栏拦截；离线轮询兜底。
- **健壮**：`-Xmx512m` 下导入 10 万行不 OOM（SAX 恒内存）；Rabbit/Redis 未启动导入不挂、降级可查；重启恢复租约失效 RUNNING。