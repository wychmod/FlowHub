# ExportFlow 后端技术设计文档（Backend TD）

| 文档属性 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 文档状态 | Draft |
| 项目代号 | ExportFlow |
| 关联文档 | `prd.md`、`fe-td.md` |

## 1. 目标与范围

本文档承接 PRD，给出 MVP 后端的技术实现方案，包括：
- 技术栈与模块划分
- 对外 REST / SSE / 下载 API 的详细定义
- 数据库表结构与索引
- 异步导出闭环（Outbox + RabbitMQ + Consumer）
- 状态机、幂等、重试、进度推送、文件清理等关键机制
- 配置清单与验收映射

## 2. 技术栈

| 层级 | 选型 | 说明 |
| --- | --- | --- |
| 运行时 | Java 21 / Spring Boot 3.x | 兼容本地教学环境 |
| 数据持久化 | MySQL 8.x | 订单、任务、Attempt、Outbox |
| 缓存/进度 | Redis | 活跃任务进度、短期幂等映射 |
| 消息队列 | RabbitMQ | 异步任务触发 |
| Excel 生成 | Apache POI SXSSF | 流式写入，内存窗口 100 行 |
| 文件存储 | 本地文件系统 | 配置化根目录 |
| 构建工具 | Maven | 与 Wrapper 一致 |
| 可观测性 | Spring Boot Actuator + 结构化日志 | `/actuator/health`、MDC trace_id/job_id |

## 3. 模块与包结构

后端按**横切 Web 基础设施**和**订单、导出两个业务模块**组织：

```text
backend/src/main/java/com/example/exportflow/
├── common/web/           # 横切 Web 基础设施
│   ├── api/              # 统一响应封装、API 常量
│   ├── error/            # 全局异常、错误码
│   ├── config/           # 通用配置（Web、Jackson、拦截器等）
│   └── trace/            # 链路追踪 / trace_id 相关工具
├── order/                # 订单业务模块
│   ├── controller/       # REST 控制器
│   ├── dto/              # 请求/响应 DTO
│   ├── service/          # 业务逻辑
│   ├── mapper/           # MyBatis Mapper 接口
│   ├── entity/           # 数据库实体
│   └── vo/               # 视图对象 / 内部值对象
└── export/               # 导出任务业务模块
    ├── controller/       # REST 控制器（含下载、SSE）
    ├── dto/              # 请求/响应 DTO
    ├── service/          # 任务创建、重试、状态机
    ├── mapper/           # MyBatis Mapper 接口
    ├── entity/           # 数据库实体
    ├── mq/               # MQ 生产者/消费者
    ├── excel/            # Excel 生成与写入
    └── schedule/         # 定时任务（清理、Outbox 分发等）
```

职责边界：

- `common/web/`：只放与 Web 层横切相关的基础设施，被所有业务模块复用。
- `order/`、`export/`：按业务领域垂直分包，每个模块内部自包含 controller/dto/service/mapper/entity，避免业务间循环依赖。
- `export/` 额外包含 `mq/`、`excel/`、`schedule/` 三个子包，承载异步导出、文件生成和定时调度等专属能力。

## 4. API 设计

### 4.1 端点总览

| 编号 | 方法与路径 | 协议 | 说明 |
| --- | --- | --- | --- |
| 1 | `GET /api/v1/orders` | JSON REST | 查询订单列表 |
| 2 | `GET /api/v1/export-columns` | JSON REST | 获取服务端允许导出的列 |
| 3 | `POST /api/v1/export-jobs` | JSON REST | 创建导出任务 |
| 4 | `GET /api/v1/export-jobs` | JSON REST | 分页查询导出任务 |
| 5 | `GET /api/v1/export-jobs/{jobId}` | JSON REST | 查询单个任务 |
| 6 | `POST /api/v1/export-jobs/{jobId}/retry` | JSON REST | 重试失败任务 |
| 7 | `GET /api/v1/export-jobs/{jobId}/download` | 二进制文件 | 下载完成的 Excel |
| 8 | `GET /api/v1/export-jobs/events` | SSE | 接收任务进度和状态变化 |
| 9 | `GET /actuator/health` | Spring Boot 原生 JSON | 检测后端是否可用 |

### 4.1.1 导出任务接口概览

下表聚焦导出任务模块的三个核心接口，供前后端联调时快速对照：

| 接口 | 请求内容 | 返回内容 | 页面用途（供前端参考） |
| --- | --- | --- | --- |
| `GET /api/v1/export-jobs` | `page`、`page_size`、可选 `status` | 任务分页列表 | 初始加载、刷新、轮询校准 |
| `GET /api/v1/export-jobs/{jobId}` | 路径中的任务 ID | 单个任务 | 读取某个任务的最新状态 |
| `POST /api/v1/export-jobs/{jobId}/retry` | 无请求体 | 更新后的任务 | 对失败任务发起新的执行 |

### 4.2 统一响应格式

非下载、非 SSE、非 Actuator 接口统一返回：

```json
{
  "code": "SUCCESS",
  "message": null,
  "data": { },
  "trace_id": "abc123"
}
```

- `code`: 业务错误码，`SUCCESS` 表示成功。
- `message`: 失败时给出可读摘要，成功时为 `null`。
- `trace_id`: 请求链路标识，写入 MDC 并透传至 MQ。

### 4.3 `GET /api/v1/orders`

查询订单列表，支持筛选、排序、分页。

示例：`GET /api/v1/orders?page=1&page_size=20&order_status=PAID&sort_by=created_at&sort_order=desc`

**Query 参数**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| page | int | 否 | 从 1 开始，默认 1 |
| page_size | int | 否 | 每页行数，默认 20 |
| order_no | string | 否 | 精确匹配，去空格，最长 32 字符 |
| order_status | string | 否 | `PENDING/PAID/SHIPPED/COMPLETED/CANCELED` |
| sales_channel | string | 否 | `WEB/APP/STORE/PARTNER` |
| created_from | ISO-8601 | 否 | 开始时间 |
| created_to | ISO-8601 | 否 | 结束时间，不得早于 created_from |
| min_amount | decimal | 否 | ≥ 0，最多 2 位小数 |
| max_amount | decimal | 否 | ≥ min_amount |
| sort_by | string | 否 | `created_at` / `total_amount`，默认 `created_at` |
| sort_order | string | 否 | `asc` / `desc`，默认 `desc` |

**响应示例**

```json
{
  "code": "SUCCESS",
  "data": {
    "items": [
      {
        "id": 1,
        "order_no": "PERF-000001",
        "order_status": "PAID",
        "sales_channel": "WEB",
        "total_amount": 89.19,
        "created_at": "2026-08-01T08:30:00Z"
      }
    ],
    "page": 1,
    "page_size": 20,
    "total": 110000,
    "total_pages": 5500
  },
  "trace_id": "xxx"
}
```

### 4.4 `GET /api/v1/export-columns`

`GET /api/v1/export-columns` 返回当前项目允许导出的列。前端用它渲染导出配置弹窗，后端仍会在创建任务时重复校验。

**响应示例**

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": [
    { "key": "order_no", "label": "订单号", "default_selected": true },
    { "key": "order_status", "label": "订单状态", "default_selected": true },
    { "key": "sales_channel", "label": "销售渠道", "default_selected": true },
    { "key": "customer_name", "label": "客户姓名", "default_selected": false },
    { "key": "customer_phone", "label": "客户手机号", "default_selected": false },
    { "key": "total_amount", "label": "订单金额", "default_selected": true },
    { "key": "currency", "label": "币种", "default_selected": true },
    { "key": "shipping_province", "label": "收货省份", "default_selected": false },
    { "key": "created_at", "label": "下单时间", "default_selected": true }
  ],
  "trace_id": "..."
}
```

### 4.5 `POST /api/v1/export-jobs`

创建任务使用 `POST /api/v1/export-jobs`。该操作会创建持久化任务，因此使用 `POST` 和 JSON 请求体，返回 `202 Accepted` 风格的成功响应。

**请求头**
- `Idempotency-Key`: UUID，必填。
- `Content-Type: application/json`

**请求体 — 勾选订单**

```json
{
  "selection": {
    "mode": "SELECTED_IDS",
    "order_ids": [101, 203, 408]
  },
  "columns": ["order_no", "order_status", "total_amount"],
  "file_name": "paid-orders"
}
```

**请求体 — 筛选结果**

```json
{
  "selection": {
    "mode": "FILTER",
    "filter": {
      "order_status": "PAID",
      "sales_channel": "WEB",
      "created_from": "2026-08-01T00:00:00Z",
      "created_to": "2026-08-02T00:00:00Z"
    },
    "excluded_order_ids": [203]
  },
  "columns": ["order_no", "sales_channel", "total_amount", "created_at"],
  "file_name": "paid-web-orders"
}
```

**请求字段**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| selection | object | 是 | 导出范围，两种 `mode` 共用该字段 |
| selection.mode | string | 是 | `SELECTED_IDS` 勾选 / `FILTER` 筛选 |
| selection.order_ids | array | 条件 | `mode=SELECTED_IDS` 时必填，1–1000 个 |
| selection.filter | object | 条件 | `mode=FILTER` 时必填，字段与订单查询参数一致 |
| selection.excluded_order_ids | array | 否 | 反选模式排除的订单 ID，最多 1000 个 |
| columns | array | 是 | 至少 1 列，顺序即表头顺序，必须命中白名单 |
| file_name | string | 否 | 不含路径与扩展名，系统追加 `.xlsx` |

**响应示例**

创建接口成功后，`ExportJobController.create()` 返回 HTTP 202，响应 `data` 中包含任务 ID、任务编号、初始状态和预估行数：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "job_id": "52",
    "job_no": "EXP-4e80d4b0-9c61-4c11-b5ce-86d7a1bbdb72",
    "status": "PENDING",
    "total_rows": 12500
  },
  "trace_id": "..."
}
```

202 表示服务器已经接受创建请求，但后台工作尚未完成。此时任务状态通常是 `PENDING`，下载接口还不能返回文件。前端应提示任务已创建，并在导出中心等待状态变化。

**关键错误码**

| 错误码 | 场景 |
| --- | --- |
| `EXPORT_COLUMNS_INVALID` | 列不存在或顺序为空 |
| `EXPORT_SELECTED_EMPTY` | 勾选模式未传 ID |
| `EXPORT_SELECTED_TOO_MANY` | 勾选 ID 超过 1000 |
| `EXPORT_FILTER_ZERO_ROWS` | 筛选 COUNT 为 0 |
| `EXPORT_FILTER_TOO_MANY_ROWS` | 筛选命中超过配置上限（默认 500000） |
| `IDEMPOTENCY_CONFLICT` | 相同 Key 但请求体不同 |
| `IDEMPOTENCY_REUSED` | 正常复用原任务（返回原任务） |

### 4.6 `GET /api/v1/export-jobs`

分页查询任务列表，默认按创建时间倒序。

**Query 参数**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| status | string | 否 | `PENDING/RUNNING/SUCCEEDED/FAILED/EXPIRED` |
| page | int | 否 | 默认 1 |
| pageSize | int | 否 | 默认 10 |

**响应字段**

```json
{
  "code": "SUCCESS",
  "data": {
    "items": [
      {
        "jobId": 1001,
        "jobNo": "EXP20260722-6F4A2C8D",
        "filename": "orders_20260722_143000.xlsx",
        "scope": "筛选结果 5000 条",
        "status": "RUNNING",
        "progress": { "processedRows": 2500, "totalRows": 5000, "percent": 50 },
        "createdAt": "2026-07-22T14:30:00Z",
        "completedAt": null,
        "fileSize": null,
        "errorSummary": null
      }
    ],
    "total": 1,
    "page": 1,
    "pageSize": 10
  },
  "trace_id": "xxx"
}
```

### 4.7 `GET /api/v1/export-jobs/{jobId}`

查询单个任务详情，作为 SSE 断线/终态校准的数据源。

### 4.8 `POST /api/v1/export-jobs/{jobId}/retry`

仅 `FAILED` 状态且执行次数 < 3 时可调用。后端：
1. 校验任务状态与重试次数。
2. 清空聚合视图错误摘要与已处理行数。
3. 删除旧临时文件（保留历史 Attempt 记录）。
4. 将任务状态回置为 `PENDING`，并创建新的 `outbox_events` 记录。
5. 返回新的任务状态与 `estimatedRows`。

### 4.9 `GET /api/v1/export-jobs/{jobId}/download`

该接口与普通 REST 接口不同。任务已成功且文件未过期时，直接返回 Excel 二进制内容。

**成功响应示例**

```http
HTTP/1.1 200 OK
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="orders.xlsx"; filename*=UTF-8''orders.xlsx
X-Trace-Id: 5ed6c4bd8d9e4a38a0e21f6c1bce0b4d
```

- `Content-Disposition` 使用 `attachment` 提示浏览器作为附件下载。
- `filename` 提供 ASCII 文件名；`filename*=UTF-8''...` 提供 UTF-8 文件名，前端优先解析该字段以支持中文名。
- 响应体为 Excel 二进制内容，不进入统一 JSON Envelope。若包一层 JSON，服务端需将二进制编码为字符串，会增加体积和内存消耗，并失去浏览器原生日志下载能力。

**下载失败响应**

| 条件 | HTTP 结果 | 响应体 |
| --- | :---: | --- |
| 任务还未成功 | 409 | JSON 错误 Envelope |
| 文件已过期 | 410 | JSON 错误 Envelope |
| 任务或文件不存在 | 404 | JSON 错误 Envelope |
| 后端依赖不可用 | 503 | JSON 或网关错误响应 |

失败时后端记录 `task_id` 与错误原因日志，返回体保持统一错误 Envelope 格式（503 可能由网关直接返回）。

### 4.10 `GET /api/v1/export-jobs/events`

SSE 连接，事件类型：

| 事件名 | 含义 | 典型数据 |
| --- | --- | --- |
| `job.progress` | 任务等待或执行中的状态变化 | 任务 ID、状态、已处理行数、总行数、版本号 |
| `job.succeeded` | 文件已生成并可下载 | 任务 ID、状态、进度、是否可下载 |
| `job.failed` | 任务失败或文件已过期 | 任务 ID、错误码、错误信息、版本号 |
| `heartbeat` | 保持连接的心跳 | 发生时间 |

一个任务事件的 JSON 数据类似：

```json
{
  "job_id": "52",
  "job_version": 7,
  "status": "RUNNING",
  "processed_rows": 6000,
  "total_rows": 12500,
  "progress_percent": 48,
  "occurred_at": "2026-08-07T08:30:05Z"
}
```

- `job_version` 是同一个任务状态变化的递增版本，由后端在每次状态/进度变更时生成。
- 前端通过比较 `job_version` 只接受更新的事件，避免网络延迟导致较旧进度覆盖较新进度。
- 连接断开、页面重新可见或收到终态事件时，前端应调用 `GET /api/v1/export-jobs/{jobId}` 校准最终结果。
- `heartbeat` 仅用于维持连接，不需要更新任务状态，默认 15 秒一次。

### 4.11 错误码与 HTTP 状态映射

| 情况 | HTTP 状态 | 业务 code | 页面下一步 |
| --- | :---: | --- | --- |
| 请求参数不合法 | 400 | `VALIDATION_ERROR` 等校验类错误 | 标记字段或提示用户修改输入 |
| 同一幂等键表达不同请求 | 409 | `IDEMPOTENCY_CONFLICT` | 清除旧键，等待用户重新明确提交 |
| 文件尚未生成 | 409 | `EXPORT_JOB_NOT_READY` | 刷新任务状态，不显示下载成功 |
| 文件已过期 | 410 | `EXPORT_FILE_EXPIRED` | 提示重新创建任务 |
| 任务或文件不存在 | 404 | 对应不存在类错误 | 返回列表或提示文件不可用 |
| 未分类服务端异常 | 500 | `INTERNAL_ERROR` | 展示安全提示与 `trace_id` |

## 5. 数据模型

### 5.1 `orders` 订单表

```sql
CREATE TABLE orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(32) NOT NULL,
  order_status VARCHAR(20) NOT NULL,
  sales_channel VARCHAR(20) NOT NULL,
  customer_name VARCHAR(64),
  customer_phone VARCHAR(20),
  total_amount DECIMAL(12,2) NOT NULL,
  currency VARCHAR(10) NOT NULL,
  shipping_province VARCHAR(64),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_created_at (created_at),
  KEY idx_status_channel_created (order_status, sales_channel, created_at)
);
```

### 5.2 `export_jobs` 导出任务表

```sql
CREATE TABLE export_jobs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_no VARCHAR(24) NOT NULL COMMENT 'EXPYYYYMMDD-XXXXXXXX',
  mode VARCHAR(20) NOT NULL COMMENT 'SELECTED/FILTER',
  selected_order_ids JSON,
  filter_snapshot JSON,
  max_order_id_at_create BIGINT COMMENT '创建时最大订单 ID，用于执行时一致性边界',
  columns_json JSON NOT NULL COMMENT '导出列顺序',
  filename VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/EXPIRED',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  estimated_rows BIGINT NOT NULL DEFAULT 0,
  processed_rows BIGINT NOT NULL DEFAULT 0,
  total_rows BIGINT NOT NULL DEFAULT 0,
  file_path VARCHAR(500),
  file_size BIGINT,
  error_summary VARCHAR(500),
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  idempotency_key VARCHAR(64),
  expired_at DATETIME(3),
  created_at DATETIME(3) NOT NULL,
  started_at DATETIME(3),
  completed_at DATETIME(3),
  UNIQUE KEY uk_job_no (job_no),
  UNIQUE KEY uk_idempotency_key (idempotency_key),
  KEY idx_status_created (status, created_at)
);
```

### 5.3 `export_job_attempts` 执行审计表

```sql
CREATE TABLE export_job_attempts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  attempt_number INT NOT NULL,
  started_at DATETIME(3),
  completed_at DATETIME(3),
  status VARCHAR(20) NOT NULL COMMENT 'RUNNING/SUCCEEDED/FAILED',
  processed_rows BIGINT DEFAULT 0,
  file_path VARCHAR(500),
  error_summary VARCHAR(500),
  stack_trace TEXT,
  created_at DATETIME(3) NOT NULL,
  KEY idx_job_id (job_id),
  UNIQUE KEY uk_job_attempt (job_id, attempt_number)
);
```

### 5.4 `outbox_events` Outbox 表

```sql
CREATE TABLE outbox_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  aggregate_type VARCHAR(32) NOT NULL DEFAULT 'EXPORT_JOB',
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL DEFAULT 'EXPORT_JOB_CREATED',
  payload JSON NOT NULL,
  trace_id VARCHAR(64),
  published_at DATETIME(3),
  created_at DATETIME(3) NOT NULL,
  KEY idx_published_created (published_at, created_at)
);
```

## 6. 异步处理闭环

### 6.1 创建任务时序

```text
Client -> POST /api/v1/export-jobs
  -> 参数校验（列白名单、数量、幂等 Key）
  -> 同事务写入 export_jobs(PENDING) + outbox_events(PENDING)
  -> 返回 202 + jobId/jobNo/estimatedRows
Outbox Dispatcher（@Scheduled 1s）
  -> 扫描未发布事件
  -> Publisher Confirm 投递 RabbitMQ
  -> 成功后更新 outbox_events.published_at
RabbitMQ Listener
  -> 幂等消费
  -> CAS 更新 PENDING -> RUNNING（version 校验）
  -> 创建 Attempt(RUNNING)
  -> Keyset 分批查询 -> SXSSF 写入
  -> 每批更新 Redis 进度
  -> 成功：原子移动 tmp -> xlsx -> 事务更新 SUCCEEDED + Attempt SUCCEEDED + file
  -> 失败：更新 FAILED + Attempt FAILED + error_summary，清理 tmp
```

### 6.2 MQ 配置要求

- Exchange、Queue、Binding 持久化（`durable=true`）。
- 消息 `deliveryMode=2`（Persistent）。
- Consumer 并发 `2`，`prefetch=1`。
- 死信队列处理格式非法消息。
- 重复消息通过 `PENDING -> RUNNING` 条件更新防护。

### 6.3 Outbox Dispatcher 与发布确认

- 每秒扫描 `published_at IS NULL` 的 Outbox 记录，限制每次扫描条数。
- 使用 RabbitTemplate 的 `ConfirmCallback`；只有收到 `ack` 才更新 `published_at`。
- `ack=false` 时保留事件，等待下次扫描重投。
- Dispatcher 崩溃后重启继续扫描，未确认事件会再次投递，Consumer 幂等处理。

## 7. 状态机

```text
PENDING -> RUNNING -> SUCCEEDED -> EXPIRED
   ^            |
   |            v
   +-------- FAILED
```

| 当前状态 | 允许动作 | 禁止动作 |
| --- | --- | --- |
| `PENDING` | 查看 | 下载、重试 |
| `RUNNING` | 查看进度 | 下载、重试 |
| `SUCCEEDED` | 下载 | 重试 |
| `FAILED` | 查看错误、重试 | 下载 |
| `EXPIRED` | 查看 | 下载、重试 |

应用启动时扫描 `RUNNING` 状态任务，统一置为 `FAILED`，错误摘要："服务重启导致任务中断，可手动重试"。

## 8. 幂等设计

- Redis Key: `export:idempotency:{idempotencyKey}`，Value: `jobId`，TTL 24h。
- 收到请求时先查 Redis：
  - 命中且请求体一致 → 返回原任务。
  - 命中但请求体不一致 → `IDEMPOTENCY_CONFLICT`。
- Redis 不可用时fallback到数据库 `uk_idempotency_key` 唯一约束。
- 任务表 `idempotency_key` 字段唯一索引兜底。

## 9. Excel 生成与存储

### 9.1 生成流程

1. 按 `id` 游标分批读取，每批 1000 行。
2. 筛选导出时附加 `id <= max_order_id_at_create` 边界。
3. 使用 `SXSSFWorkbook`，内存窗口 100 行。
4. 文本值前导 `= + - @` 时添加单引号，防止公式注入。
5. 手机号按文本写入。
6. 单元格文本超过 32767 字符时截断并记录 Warning。
7. 先写入 `{filename}.tmp`，关闭 Workbook 后原子移动为 `{filename}.xlsx`。
8. 失败时关闭流与 Workbook，删除临时文件。

### 9.2 文件路径约定

```text
{export.root-dir}/
  ├── 2026/
  │   └── 07/
  │       └── 22/
  │           ├── orders_20260722_143000_{jobId}.xlsx
  │           └── orders_20260722_143000_{jobId}.tmp   (生成中)
```

文件名模板：`orders_yyyyMMdd_HHmmss_{jobId}.xlsx`，非法字符（`/ \ : * ? " < > | ..`）过滤。

### 9.3 过期清理

- 每小时扫描 `SUCCEEDED` 且 `expired_at <= NOW()` 的任务。
- 删除文件成功后更新状态为 `EXPIRED`。
- 文件已不存在时仍标记 `EXPIRED` 并记录 Warning。
- 删除失败保持 `SUCCEEDED`，下次调度重试。
- 失败任务产生的 `.tmp` 在任务收尾时立即删除；清理调度兜底删除超过 1 小时的孤立临时文件。

## 10. 进度跟踪与 SSE

### 10.1 Redis 进度结构

```text
HSET export:job:{jobId}
  processedRows -> 2500
  totalRows     -> 5000
  status        -> RUNNING
  percent       -> 50
  jobVersion    -> 3
  updatedAt     -> 2026-07-22T14:31:00Z
EXPIRE 48h
```

### 10.2 进度写入策略

- 每完成一个批次，Consumer 更新 Redis Hash。
- 同时异步写回 MySQL `export_jobs.processed_rows`，降低 MySQL 压力。
- Redis 不可用时全部回写 MySQL，SSE 不可用时前端走轮询。

### 10.3 SSE 实现

- 使用 `SseEmitter` 为每个客户端维护连接。
- 服务端发送 `heartbeat` 每 15 秒。
- 事件携带单调递增 `job_version`，前端仅接受更高版本。
- 终态事件发出后，Emitter 在完成时自动关闭。
- 断线由前端负责重连与降级轮询。

## 11. 异常与降级

| 故障 | 处理策略 |
| --- | --- |
| Redis 不可用 | 进度写 MySQL；幂等走数据库唯一索引；SSE 失效时前端轮询。 |
| RabbitMQ 不可用 | 新任务保留 PENDING，Outbox Dispatcher 持续扫描重投。 |
| SSE 连接异常 | 前端退避重连，3 次失败后切换轮询。 |
| 磁盘空间不足 | Consumer 写入异常 → FAILED；清理调度尝试释放空间；发布/应急停止创建。 |
| 数据库不可用 | 创建接口失败，页面提示服务不可用。 |
| MQ 重复消息 | 通过 `PENDING -> RUNNING` CAS 更新过滤，保证同 job_id 只执行一次。 |

## 12. 核心配置（application.yml）

```yaml
export:
  root-dir: ${EXPORT_ROOT_DIR:./export-files}
  max-rows: ${EXPORT_MAX_ROWS:500000}
  batch-size: ${EXPORT_BATCH_SIZE:1000}
  sxssf-window: ${EXPORT_SXSSF_WINDOW:100}
  file-retention-hours: ${EXPORT_FILE_RETENTION_HOURS:24}
  tmp-cleanup-hours: ${EXPORT_TMP_CLEANUP_HOURS:1}
  sse-heartbeat-seconds: ${EXPORT_SSE_HEARTBEAT:15}
  redis-progress-ttl-hours: ${EXPORT_REDIS_PROGRESS_TTL:48}

rabbitmq:
  consumer:
    concurrency: 2
    prefetch: 1
    queue: export.job.queue
    exchange: export.job.exchange
    routing-key: export.job.created
```

## 13. 验收映射

| PRD 用例 | 后端实现要点 |
| --- | --- |
| AC-01 默认查询 | 订单分页接口 + 索引 `idx_created_at` |
| AC-02 组合筛选 | 多条件动态 SQL + `idx_status_channel_created` |
| AC-03 勾选导出 | `mode=SELECTED`，按 ID 列表导出 |
| AC-04 筛选导出 | `mode=FILTER`，保存快照与 `max_order_id_at_create` |
| AC-05 进度展示 | Redis 进度 + SSE `job.progress` |
| AC-06 下载文件 | download API + Content-Disposition |
| AC-07 幂等创建 | Redis + 唯一索引 |
| AC-08 列白名单 | 服务端白名单校验 |
| AC-09 行数上限 | `COUNT` 后校验 `max-rows` |
| AC-10 文件丢失 | 下载时校验文件存在性并记录日志 |
| AC-11 公式注入 | 写入前对 `= + - @` 加单引号 |
| AC-12 服务重启恢复 | 启动监听器将 RUNNING 置为 FAILED |
| AC-13 MQ 重复消息 | `PENDING -> RUNNING` CAS 更新 |
| AC-14 SSE 断线降级 | 前端行为；后端提供轮询详情接口 |
| AC-15 Redis 降级 | Redis 不可用时写 MySQL |
| AC-15a Outbox 恢复 | Dispatcher 扫描 + Publisher Confirm |
| AC-16 失败重试 | retry API + 新 Attempt + 新 Outbox 事件 |
| AC-17 重试上限 | `attempt_count < max_attempts` 校验 |
| AC-18 文件过期 | 定时清理调度 |
| AC-19 MQ 首次发布失败 | Outbox 重投机制 |

## 14. 风险与约束

- SXSSF 使用临时磁盘文件，磁盘空间不足会失败，需监控。
- 本地文件系统不支持多实例共享，MVP 限定单机运行。
- SSE 在默认单实例下可直接推送；多实例需要后续引入 Redis Pub/Sub 广播。
- 无鉴权，仅允许本地或受信任内网运行。
