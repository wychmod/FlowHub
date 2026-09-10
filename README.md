# FlowHub

企业级异步导出中心（订单筛选 → 异步 Excel 导出 → 进度推送 → 下载；订单 Excel 批量导入）。

当前仓库为按 [docs/prd.md](docs/prd.md)、[docs/be-td.md](docs/be-td.md)、[docs/fe-td.md](docs/fe-td.md) 搭建的教学/演示项目：订单条件查询（后端接口 + 前端完整列表页，含筛选、排序、勾选与导出入口）、导出全链路（创建受理 → Outbox 可靠投递 → RabbitMQ 消费端条件抢占与 Attempt 审计 → 执行体按任务快照 Keyset 分批读取订单 → SXSSF 流式写 Excel → 进度状态分层通知（MySQL 事实源 + Redis 投影 + SSE 广播 + HTTP 校准）→ 文件发布协议（临时文件原子移动发布 + 成功终态登记 + 受控下载）→ 恢复与清理（租约到期收敛 + 人工重试 + 过期文件下架 + 孤儿对账））以及订单 Excel 导入（模板下载 + 文件/结构/行级三层校验 + 异步导入任务复刻 Outbox/消费/进度/SSE 管道 + 错误报告下载 + PARTIAL 部分成功语义 + 前端导入任务页与上传入口）已实现。

## 技术栈

| 端 | 技术 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.3.2 · MyBatis（`mybatis-spring-boot-starter` 3.0.3）· Flyway + MySQL · RabbitMQ（`spring-boot-starter-amqp`，发布确认闭环 + 手动 Ack 消费）· Redis（`spring-boot-starter-data-redis`，进度投影）· Apache POI（`poi-ooxml`，SXSSF 流式 Excel）· Maven（Wrapper，内置于 `backend/.mvn/wrapper/`） |
| 前端 | React 18.3.1 · TypeScript · Vite 6 · antd 6 · @ant-design/icons 6 · @tanstack/react-query 5 · dayjs |

端口约定：**后端 8080，前端 5174**。

## 一键启动

前置条件：JDK 21、Node.js ≥ 18、MySQL（本机 3306 端口存在 `flowhub` 数据库与 `flowhub/flowhub` 账号，见下方数据源说明；首次运行需联网下载依赖）。RabbitMQ 为**可选**前置（默认 `localhost:5672`，guest/guest）：未启动时后端仍可正常启动、创建接口可用，仅 Outbox 分发器每轮记录 `outbox_publish_deferred` 日志、消费监听容器后台持续重连，Broker 恢复后自动补发并开始消费；但 `/actuator/health` 会因 rabbit 组件显示 DOWN。Redis（默认 `localhost:6379`）同为**可选**前置：未启动时进度投影写入自动降级（仅记 `redis_progress_write_failed` 日志），任务执行与查询均不受影响，`/actuator/health` 会因 redis 组件显示 DOWN。

想用 Docker 快速起 RabbitMQ：仓库根目录执行 `docker compose up -d rabbitmq`（管理台 `http://localhost:15672`，guest/guest；健康检查 `rabbitmq-diagnostics ping`，拓扑由应用启动时自动声明，见 `docker-compose.yml`）。该编排不含 MySQL——MySQL 保持与既有环境共存单独启动，避免 3306 端口冲突。

双击 `backend/scripts/start.bat`：

- 首次运行会自动执行 `npm install`；
- 随后打开两个窗口分别运行后端（8080）与前端（5174）；
- 浏览器访问 <http://localhost:5174>，后端日志出现 `Started FlowHubApplication` 即就绪；
- 关闭对应窗口即停止对应服务。

### 手动启动（等价方式）

```bash
# 后端（backend 目录；.\ 前缀在 cmd 与 PowerShell 中均可用）
.\mvnw.cmd spring-boot:run

# 前端（frontend 目录）
npm install
npm run dev
```

### 验证

| 检查项 | 地址 |
| --- | --- |
| 前端页面 | http://localhost:5174 |
| 健康检查 | http://localhost:8080/actuator/health |
| 订单接口（分页） | http://localhost:8080/api/v1/orders?page=1&page_size=20 |
| 订单接口（筛选 + 排序） | http://localhost:8080/api/v1/orders?order_status=PAID,SHIPPED&total_amount_min=100&sort=total_amount,desc |
| 任务列表接口 | http://localhost:8080/api/v1/export-jobs（真实分页，可选 `status` 过滤，返回进度/下载/错误等派生字段，契约见 be-td.md 4.6） |
| 任务创建接口（POST，契约见 be-td.md 4.5） | `POST /api/v1/export-jobs` + `Idempotency-Key` 头，202 受理（Outbox 落库并由分发器发布至 RabbitMQ——Confirm ACK 且无 Returned 才标记已发布；消费者以条件抢占领取执行权，执行体按任务快照 Keyset 分批读取订单、SXSSF 流式写 Excel 至 `export-files/` 并推进 `processed_rows`，写盘完成后按发布协议原子移动为正式文件并登记 SUCCEEDED） |
| SSE 事件订阅（契约见 be-td.md 4.10） | `GET /api/v1/export-jobs/events`（`text/event-stream`）：`job.progress`/`job.succeeded`/`job.failed`/`heartbeat` 4 类事件，事件 id = `jobId:version`，15s 心跳 |
| 任务文件下载（fe-td.md 7.1 契约） | `GET /api/v1/export-jobs/{job_id}/download`：仅 SUCCEEDED 且未过期返回文件流（`Content-Disposition` 携带展示文件名），其余状态/过期/缺失/路径污染分别返回 `EXPORT_JOB_NOT_DOWNLOADABLE`(409)/`EXPORT_FILE_EXPIRED`(410)/`EXPORT_JOB_NOT_FOUND`/`EXPORT_FILE_MISSING`/`EXPORT_PATH_INVALID`(404) 结构化错误 |
| 任务人工重试（be-td.md 4.8 契约） | `POST /api/v1/export-jobs/{job_id}/retry`：仅 FAILED 且未达尝试上限（3 次）可重试，202 受理回 PENDING（同事务新 Outbox 事件重走可靠投递）；越限/状态不符返回 `EXPORT_JOB_NOT_RETRYABLE`(409) |
| 订单导入模板下载 | `GET /api/v1/import-jobs/template`：9 列纯表头模板（与导出格式互逆一致，不放示例行），含下拉数据验证、金额列 `0.00` 预设格式与填写说明 |
| 订单导入上传受理 | `POST /api/v1/import-jobs`：multipart 字段 `file`，.xlsx 且 ≤10MB；文件级/结构级校验同步 400 拒绝，行级校验异步（设计见 [docs/order-import-design.md](docs/order-import-design.md)） |
| 订单导入任务列表 | `GET /api/v1/import-jobs`：分页 + 可选 `status` 过滤（含 PARTIAL），返回 progress_percent/error_report_available/error_summary 派生字段 |
| 订单导入任务 SSE | `GET /api/v1/import-jobs/events`：`import.progress`/`import.succeeded`/`import.partial`/`import.failed`/`heartbeat` 5 类事件，事件 id=`jobId:version`，15s 心跳 |
| 订单导入错误报告下载 | `GET /api/v1/import-jobs/{job_id}/error-report`：仅 PARTIAL 且登记了报告路径时返回 xlsx |
| 订单导入人工重试 | `POST /api/v1/import-jobs/{job_id}/retry`：仅 FAILED 且未达尝试上限（3 次），202 受理回 PENDING（同事务新 Outbox） |

后端生成的 Excel 落入 `backend/export-files/` 受控根目录（已被 `.gitignore` 忽略），内部按 `<UTC 日期>/<jobId>/attempt-N.tmp|xlsx` 三层定位：写入期只针对 `.tmp` 临时文件，发布时同文件系统 `ATOMIC_MOVE` 原子切换为 `.xlsx` 正式文件。

后端测试：`cd backend && .\mvnw.cmd test`；前端构建检查：`cd frontend && npm run build`。

### 演示数据（可选）

订单查询已走真实 MyBatis + MySQL（`orders` 表为空时接口返回空列表），需向 MySQL 装入演示订单数据（前端列表展示、后续验证 Excel 导出规模）可执行数据生成脚本：

```bash
cd backend/scripts
./seed-demo-data.sh                  # Git Bash / Linux / macOS；默认生成 200,000 行
SEED_ROWS=50000 ./seed-demo-data.sh  # 自定义行数（1 ~ 1,000,000）
```

脚本特性：仅当 `orders` 表为空时才写入（已有数据则自动跳过，绝不覆盖）；数据为确定性生成（同参数重跑结果一致），订单状态/销售渠道/币种按业务权重分布，包含个人与企业客户、11 位手机号、16 个省市、多币种金额与近一年的下单时间。数据生成 SQL 位于 `backend/scripts/sql/seed-demo-data.sql`（需 MySQL 8.0+，脚本会自动探测常见安装路径下的 mysql 客户端；连接参数可用 `DB_HOST/DB_PORT/DB_USER/DB_PASSWORD/DB_NAME` 环境变量覆盖）。

## 目录结构

```text
flowhub/
├── docs/                      # PRD 与前后端技术设计文档
├── backend/                   # Spring Boot 后端
│   ├── mvnw / mvnw.cmd        # Maven Wrapper（使用 .mvn/wrapper/maven-wrapper.jar）
│   ├── scripts/               # 脚本（start.bat 一键启动、seed-demo-data.sh 演示数据生成）
│   └── src/main/java/com/example/flowhub/
│       ├── common/web/        # 横切 Web 基础设施
│       │   ├── api/           #   统一响应 Envelope（ApiResponse/ApiResponseAdvice/RawResponse）
│       │   ├── error/         #   错误码、业务异常、全局异常处理
│       │   ├── config/        #   Web 通用配置（CORS、API v1 统一前缀、异步线程池）
│       │   └── trace/         #   trace_id 生成/透传 + MDC（TraceIdSupport、MdcScope、MdcTaskDecorator）
│       ├── order/             # 订单业务模块（自包含 controller/dto/service/mapper/entity/vo/query）
│       │   ├── controller/    #   GET /api/v1/orders（分页 + 条件筛选 + 排序 + 参数校验）
│       │   ├── dto/           #   输入 OrderRequest（record + @BindParam）/ 响应 OrderPageResp（含 sort 回显）
│       │   ├── service/       #   入参归一化 + 语义校验 + 实体转 VO
│       │   ├── query/         #   OrderQuery/OrderCriteria 值对象 + SortField 白名单与操作符枚举
│       │   ├── mapper/        #   OrderMapper（@Mapper，SQL 见 resources/mapper/OrderMapper.xml）+ InMemoryOrderMapperImpl（行为基准，非运行时）
│       │   ├── entity/        #   Order 实体（record）
│       │   └── vo/            #   列表行视图对象
│       └── export/            # 导出任务业务模块（自包含 controller/dto/service/mapper/entity/vo/command/error/mq）
│           ├── controller/    #   POST /api/v1/export-jobs（创建，202 受理）+ GET（分页列表）+ POST /{job_id}/retry（重试）+ GET /{job_id}/download（文件下载）
│           ├── dto/           #   创建请求三件套（JSON 绑定 + 跨字段校验）/ 分页响应 DTO
│           ├── command/       #   CreateExportJobCommand 规范化命令 + ExportColumn 列白名单 + 模式枚举
│           ├── excel/         #   ExcelExportWriter（SXSSF 流式写 Excel：窗口 100 + safeText 防注入 + 样式复用）
│           ├── event/         #   应用事件 ExportJobChanged + SSE 事件 payload（snake_case 契约）
│           ├── mq/            #   RabbitMQ 拓扑（RabbitConfig）+ Outbox 分发器 + 消息契约 ExportJobMessage + 消费者 ExportJobConsumer
│           ├── service/       #   幂等判断 + 业务校验 + 同事务写 export_jobs/outbox_events + 条件抢占/成功与失败终态收敛 + 可下载文件解析 + Keyset 执行体 + 发布协议（ExportFileService：受控路径/原子移动/补偿删除）+ 进度状态分层（ExportProgressService）+ SSE 广播（ExportSseService）
│           ├── error/         #   ExportErrorCode 业务错误码（列白名单/选择空/筛选零行/幂等冲突等）
│           ├── mapper/        #   ExportJobMapper/OutboxEventMapper/ExportJobAttemptMapper/ExportOrderMapper（@Mapper，SQL 见 resources/mapper/*.xml）
│           ├── entity/        #   ExportJobEntity/OutboxEventEntity/ExportOrderRow/ExportSelectionSnapshot（record）
│           └── vo/            #   受理结果 ExportJobAcceptedVO / 列表行视图对象
└── frontend/                  # Vite + React 前端
    └── src/
        ├── main.tsx           # 入口（react-query、antd 中文环境 + 全局主题 token）
        ├── App.tsx            # 根组件（轻量页面切换）
        ├── app/               # 布局壳层
        │   ├── AppLayout.tsx  #   深色 Sider（品牌区 + 导航）+ 白色顶栏（动态页题 + 后端健康徽标）
        │   ├── HealthBadge.tsx#   顶栏健康徽标（轮询 /actuator/health，Tooltip 组件细分）
        │   └── layoutMeta.ts  #   页面元信息（Header 页题事实源）
        ├── styles/index.css   # 全局样式（滚动条占位、表格数字等宽）
        ├── api/               # 跨 feature 复用的 API 防腐层
        │   ├── http.ts        #   requestJson（Envelope 结构校验与解包）/ ApiError / Envelope 类型
        │   ├── download.ts    #   文件下载协议工具（parseBlobError / filenameFromDisposition / saveBlob）
        │   ├── exportApi.ts   #   导出任务 API（列表 listExportJobs + 创建 createExportJob + 下载 downloadExportJob + 重试 retryExportJob + SSE eventsUrl）
        │   └── healthApi.ts   #   Actuator 健康查询（原生 JSON 防腐，非 Envelope 结构）
        └── features/          # 业务 feature
            ├── orders/        #   订单列表页（筛选 + 排序 + 勾选 + 导出入口）
            └── exports/       #   导出任务页（列表 + SSE 实时进度 + 下载/重试 + useExportEvents Hook + ConnectionBadge）
```

与 TD 文档的差异（均为后续迭代内容）：后端 `mq/` 包已随 Outbox 分发器与消费者落地（`excel/`、`schedule/` 等在引入 POI 时创建）；订单查询已接入真实 MyBatis（动态 SQL + record 构造器自动映射 + V8 索引），`InMemoryOrderMapperImpl` 仅保留为行为基准供对齐测试；前端 `useExportEvents.ts`/导出任务页/ConnectionBadge 已随 SSE 进度推送交付实现。后端已接入 MySQL 数据源与 Flyway（`spring.datasource` + `spring.flyway`，迁移脚本置于 `backend/src/main/resources/db/migration/`，导入模块新增 V9 建 `import_jobs`/`import_job_attempts`）。订单导入后端已落地（独立 `orderimport/` 模块，Flyway V9 + 独立 RabbitMQ 拓扑 `import.job.*`，前端导入任务页与上传入口已交付）。

## 已实现的最小案例

- **统一响应 Envelope**：所有 JSON 接口返回 `{code, message, data, trace_id}`，字段风格为 snake_case（对齐 be-td.md 4.2/4.3 示例）；`ApiResponseAdvice` 将控制器返回的裸对象自动包装为 Envelope，标注 `@RawResponse` 或返回 `Resource`/SSE/流式的接口保持原生响应。
- **API v1 统一前缀**：`ApiWebMvcConfiguration` 为所有 `@RestController` 统一追加 `/api/v1` 前缀，控制器只声明相对路径，版本号集中维护。
- **trace_id 链路**：`TraceIdSupport` + `MdcScope` 为每个请求生成/透传 trace_id，写入 MDC（日志可打印）、响应头 `X-Trace-Id` 与响应体；`MdcTaskDecorator` 使异步线程池（`flowHubTaskExecutor`）继承请求的 trace_id，贯穿异步链路。
- **错误路径**：参数校验失败返回 400 + `VALIDATION_ERROR` Envelope（`page_size=0`、非法枚举值、区间颠倒等可复现）；请求体 Bean Validation 失败时 Envelope 的 `data.field_errors` 输出「字段路径 → 文案」对象映射（`GlobalExceptionHandler` + `FieldErrorData`）。
- **订单条件查询**：状态/渠道/币种多值筛选、姓名模糊、订单号前缀、手机号精确、金额与时间区间、排序白名单（`sort=total_amount,desc`），全契约见 [docs/order-query-design.md](docs/order-query-design.md)；入参 record + `@BindParam` 构造器绑定，各层显式空值防御。
- **导出任务创建接口**：`POST /api/v1/export-jobs`（`Idempotency-Key` 头 + selection 勾选/筛选判别联合 + 9 列白名单，契约见 be-td.md 4.5 与 [docs/export-http-boundary-plan.md](docs/export-http-boundary-plan.md)）——DTO 跨字段校验 → Command 规范化（ID 去重排序/列白名单重排/文件名清理/筛选快照转 `OrderCriteria`）→ 业务校验（存在性、筛选命中 0 行/超上限；`snapshotByCriteria` 单查询统计命中数与范围内最大订单 ID 作为高水位）→ 幂等判断（request hash SHA-256，相同复用/不同 409）→ 同事务写 `export_jobs`(PENDING) + `outbox_events`，成功返回 202 + `{job_id, job_no, status, total_rows}`。筛选命中上限可经 `export.filter-max-rows` 配置（默认 500000）。
- **Outbox 可靠投递管道**：`OutboxDispatcher` 定时扫描未发布事件（`published_at IS NULL`，`export.outbox.dispatch-delay-ms` 默认 5000）→ 发送最小契约消息 `ExportJobMessage`（schema_version/message_id/job_id/event_version，message_id 由 outbox 事件 id 稳定派生，Header 携带 `X-Trace-Id`）至 direct 交换机 `export.job.exchange` → 等待 Publisher Confirm，**仅 ACK 且无 Returned 才回填 `published_at`**；send 异常/NACK/退回/超时一律保留事件下轮补发（至少一次投递，`outbox_publish_deferred` 日志），Job 不因发布失败改变状态。消息正文最小化，执行数据以库内 Job 为准。设计见 [docs/export-outbox-reliable-delivery-notes.md](docs/export-outbox-reliable-delivery-notes.md)。
- **RabbitMQ 消费端（条件抢占 + Attempt 审计）**：`ExportJobConsumer` 手动 Ack 消费（`listener.simple` 配 manual/prefetch=1/concurrency=2）——trace Header 合法恢复/缺失新建 → 契约不支持 `basicReject` 转 DLQ → `claimPendingJob` 以条件 UPDATE（`status='PENDING' AND attempt_count<3`）抢占执行权，与 RUNNING Attempt 插入（`MAX+1`）同一事务原子生效 → 抢占失败直接 Ack（重复投递无副作用，收敛为最多一次有效执行）→ 执行服务内部把业务异常收敛为 Job/Attempt FAILED（`FILE_GENERATION_FAILED`）后正常 Ack；claim 事务或 Channel 异常穿出不确认，保留重投机会。设计见 [docs/export-consumer-claim-attempt-notes.md](docs/export-consumer-claim-attempt-notes.md)。
- **确定性数据读取管道（第 15 章）**：创建时 `ExportOrderMapper.snapshotByCriteria` 单查询统计命中 COUNT 与范围内 MAX(id)（高水位 `max_order_id_at_create`，比全表 MAX 更贴合任务边界）；执行体 `ExportExecutionService.runJob` 加载 Job 快照 → `filter_snapshot` 反序列化重建 `OrderCriteria`（结构性杜绝漏字段重建）→ Keyset 批查 `findBatch`（`id > lastId AND id <= max_order_id_at_create` + 跨 Mapper 复用 `OrderMapper.criteriaConditions` 共享筛选片段 + `ORDER BY id ASC LIMIT`，SELECTED_IDS/FILTER 两模式共享同一条 SQL）→ 每批推进 `processed_rows` → 空批/不足一批双结束。游标推进遵循「先写入成功、后推进」（writeBatch 扩展点预留第 17 章），读取完成后任务仍收敛 FAILED（Excel 生成未接入）；`export.execution.batch-size` 默认 1000。设计见 [docs/export-keyset-read-pipeline-notes.md](docs/export-keyset-read-pipeline-notes.md)。
- **进度状态分层与实时通知（第 16 章）**：进度推进走条件 UPDATE 守卫（`status='RUNNING'` 单向 + `processed_rows <= 新值` 单调 + heartbeat/lease 续期 + version 递增，0 行 fail-fast）→ 发布 `ExportJobChanged` 应用事件 → `@TransactionalEventListener(AFTER_COMMIT)` 提交后**重读 Job** 广播 SSE（`job.progress`/`job.succeeded`/`job.failed`/`heartbeat` 4 类事件，payload snake_case，事件 id = `jobId:version` 供前端版本栅栏，15s 心跳，坏连接隔离）；Redis 投影 `export:progress:<jobId>`（Hash + TTL 48h）尽力写入、失败仅降级日志；RUNNING 进度百分比封顶 99（SUCCEEDED 才 100）；失败收敛 `markFailed` 同事务提交后广播 `job.failed`（携带 error_code/error_message），事务回滚不广播（已测）。设计见 [docs/export-progress-state-notes.md](docs/export-progress-state-notes.md)。
- **SXSSF 流式 Excel 生成（第 17 章）**：`ExcelExportWriter` + `WorkbookSession`（`export/excel/`）封装全部 POI 细节——`open`（列白名单二次复核、`SXSSFWorkbook(100)` 滑动窗口 + 压缩临时文件、表头/冻结首行/自动筛选/列宽、金额 `0.00` 样式单例复用）→ `writeBatch`（文本经 `safeText` 公式注入防护、金额写 NUMERIC 数值、时间固定格式）→ `close`（`write → 关流 → close → dispose` 链式收敛 + suppressed exception）；业务临时文件由 `ExportFileService` 在受控根目录内分配（attempt 序号查 RUNNING Attempt），写盘失败删除半成品、不虚假推进游标与进度。设计见 [docs/export-sxssf-writer-notes.md](docs/export-sxssf-writer-notes.md)。
- **文件发布协议与安全下载（第 18 章）**：`ExportFileService` 为受控文件边界——exportRoot 启动期提纯（`toAbsolutePath().normalize()` → `createDirectories` → `toRealPath()`）、层级目录 `<UTC 日期>/<jobId>/attempt-N`、路径双层防腐（文本层 normalize 拒绝 `..` 与根组件，物理层逐段符号链接检查 + `toRealPath` 验真）；执行体收敛顺序为「写完 `.tmp` → `publish()` 同文件系统 `ATOMIC_MOVE` 发布为 `.xlsx`（不支持原子移动不降级、任务失败）→ `markSucceeded` 同事务置 Job/Attempt SUCCEEDED 并回填 `file_path`/`file_size_bytes`/`finished_at`/`expired_at`（保留期 `export.files.retention-hours` 默认 24h）→ 数据库失败补偿删除未登记文件」；下载接口 `GET /api/v1/export-jobs/{job_id}/download` 只按 Job 查（SUCCEEDED 且未过期 → `resolvePersisted` 受控解析 → 文件流 + RFC 5987 展示文件名），文件丢失不重新生成、路径污染返回结构化错误。设计见 [docs/export-file-publishing-notes.md](docs/export-file-publishing-notes.md)。
- **恢复与清理（第 19 章）**：`ExportMaintenanceService` 启动恢复只收敛「租约已失效（含未写租约）」的 RUNNING 为 FAILED(`SERVICE_RESTARTED`)——Attempt 先行、Job 收尾同一前置条件，不自动重跑、不误伤活跃执行；人工重试 `POST /{job_id}/retry`（FAILED→PENDING + 同事务新 Outbox，失败 Attempt 证据保留，`attempt_count<3` 上限）；过期清理「文件删除成功才 markExpired EXPIRED」（路径非法/删除失败保持 SUCCEEDED 下轮再试）+ Redis 投影删除；孤儿文件三维对账（宽限期 1h + 活跃租约校验 + Job/Attempt 引用检查）；`export.cleanup-cron` 默认每小时。设计见 [docs/export-recovery-cleanup-notes.md](docs/export-recovery-cleanup-notes.md)。
- **订单 Excel 导入后端（第 V9~V17 章/B1-B17）**：独立模块 `orderimport/` 复刻导出全链路——`GET /api/v1/import-jobs/template` 模板下载（`ImportTemplateWriter` 内存 byte[]，纯表头不放示例行 + 下拉校验 + 金额列 `0.00` 预设格式 + 填写说明）；`POST /api/v1/import-jobs` 上传受理，三层校验：文件级（.xlsx 后缀 + PK 魔数 + ≤10MB）与结构级（`ExcelImportReader` POI SAX 流式轻扫，数据 Sheet 名「订单数据」+ 表头恰好 9 列（多列/缺列/名称/顺序不符均拒绝）/空文件/≤10 万行）同步 400 拒绝，行级校验异步；执行侧 `ImportRowValidator` 逐行校验（枚举/金额 `compareTo`/严格文本时间 `uuuu-MM-dd HH:mm:ss`/公式注入防护）+ 文件内 HashSet 查重 + `orders.order_no` 唯一约束冲突预查与 DuplicateKey 逐行降级；`ImportExecutionService` SAX 流式读 + 批入库 + `ImportProgressService` 进度推进，skipped==0 收敛 SUCCEEDED、否则 `ImportErrorReportWriter` 生成错误报告后收敛 PARTIAL（DATABASE 失败补偿删除报告）；`ImportOutboxDispatcher`/`ImportRabbitConfig`/`ImportJobConsumer`/`ImportSseService`/`ImportMaintenanceService` 复刻可靠投递、条件抢占、5 类 SSE 事件、启动恢复与过期清理/孤儿对账；状态机 `PENDING→RUNNING→SUCCEEDED|PARTIAL|FAILED→EXPIRED`，PARTIAL 为部分成功终态不可重试。上传原件存 `<import-files>/<UTC日期>/<jobNo>/upload.xlsx`，错误报告 `errors-attempt-N.xlsx`。设计见 [docs/order-import-design.md](docs/order-import-design.md)；前端「导入任务」页（F1-F5）已实现，见下条。
- **前端数据流**：`requestJson` 统一解析 Envelope（2xx 非 Envelope 抛 `Invalid API envelope`，错误统一抛 `ApiError`，携带 message/code/status/traceId/fieldErrors）→ react-query 管理请求缓存 → antd Table 服务端分页 + dayjs 时间格式化；订单 API 层已就绪完整筛选/排序参数序列化（时间用本地格式，无时区后缀）。
- **前端订单列表页**：8 项条件筛选（草稿与已提交严格分离，输入不触发请求）、订单号/金额/下单时间三列表头三态排序（以响应回显对齐）、跨页勾选（上限 1000 条）、「导出已选 / 导出筛选结果」配置弹窗与创建请求（`Idempotency-Key` 头 + 勾选/筛选两种 selection 模式；筛选导出且存在勾选时提供「排除已勾选的 N 条订单」复选框（默认选中），走 `excluded_order_ids` 反选契约，排除列表空时字段折叠不出现）；查询失败保留上次数据与全部用户意图。方案见 [docs/order-page-fe/](docs/order-page-fe/)。
- **前端界面主题与防抖动**：antd theme token 定制（深色 Sider 品牌区 + 白色顶栏动态页题 + Card 分区布局）；表格启用固定列宽（`tableLayout: fixed`）、固定表体高度（`scroll.y` 内部滚动）与 `scrollbar-gutter: stable` 滚动条占位，配合 react-query `placeholderData: keepPreviousData` 平滑过渡，翻页/排序/筛选时页面零抖动。
- **前端 API 防腐层**：页面只说业务语言，协议细节收敛在 `api/` 层——`requestJson` 统一请求头、Envelope 结构校验与解包、错误转 `ApiError`；文件下载按 fe-td.md 7 处理「同一 URL 成功是文件流、失败是 JSON/文本」的分流（`parseBlobError`）与文件名解析（`filenameFromDisposition`）、浏览器保存（`saveBlob`），业务入口为 `downloadExportJob`。
- **后端导出任务列表接口（P-4 真实化）**：`GET /api/v1/export-jobs` 从空列表占位改为真实分页查询（`ExportJobMapper.findPage` 按 `created_at DESC, id DESC` + `countAll`），Service 层 `listJobs` 按状态计算派生字段 `progress_percent`（复用 `ExportJobEventPayload.progressPercent` 规则，SUCCEEDED 才 100）与 `downloadable`（SUCCEEDED 且未过期）；`ExportJobItemVO` 扩为完整字段（进度/文件大小/错误/完成时间/过期时间/version），列表行字段与 SSE 事件 payload 对齐，使前端 `applyEvent` 可就地乐观更新。集成测试 `ExportJobListTest` 6 用例。
- **前端导出任务页 + useExportEvents**：`useExportEvents` Hook 消费 SSE（docs/export-sse-design.md）——连接状态机 connecting/sse/polling/offline（连续 3 次失败切轮询 + 1/2/5/10s 退避）、`job_version` 版本栅栏乱序防御、乐观局部更新（仅覆盖事件携带字段，错误字段缺失保留/显式 null 清空的 hasOwnProperty 语义）、失效收敛（每次有效事件后 invalidate）、筛选缓存智能移除、挂载/可见性/在线状态生命周期清理；`ExportJobsPage` 完整页面（状态 Tag/进度条 + 行数/文件大小/创建/完成时间/下载按钮/重试 Popconfirm/分页/ConnectionBadge/空态/错误态），三条件降级轮询（非 sse + 有进行中任务时可见 3s / 隐藏 15s）；jsdom 单测 6 用例 + API 契约测试。已真实联调（创建 5 万行任务 → 执行 → SUCCEEDED → 下载 1.6MB Excel → 重试 409）。
- **前端导入任务页 + 导入入口（F1-F5）**：`api/importApi.ts` 覆盖列表/上传/模板下载/错误报告下载/重试/SSE 端点，multipart 上传走独立于 `requestJson` 的 `fetch`+`FormData` 通道（字段 `file`、不手动设 `Content-Type`、错误归一 `ApiError`）；订单列表页「导入订单」按钮 + `ImportModal`（`Upload.Dragger` 拖拽 + `beforeUpload` 本地预检仅 `.xlsx`/≤10MB + 模板下载 + 受理成功跳转导入任务页）；`useImportEvents` 复刻 `useExportEvents`（连接状态机/`jobId:version` 版本栅栏/乐观更新/失效收敛/三条件轮询降级），事件类型增 `import.partial` 并乐观更新 `succeeded_rows`/`skipped_rows`/`error_report_available`；独立「导入任务」页 `ImportJobsPage`（复刻导出页布局，6 态 Tag 含 **PARTIAL「部分成功」gold**、进度条、成功/跳过计数、错误分类 Top N 悬浮、PARTIAL 下载错误报告、FAILED 重试、`ConnectionBadge`）；`ConnectionBadge` 与连接态类型抽为导出/导入共享。新增 jsdom 单测（`useImportEvents` 7 + `importApi` 10 + `uploadGuards` 4）+ 导航三页切换。方案见 [docs/import-page-fe/](docs/import-page-fe/)。

## 前端访问后端的方式

默认走 **Vite 代理**：前端同源请求 `/api/*`，由 Vite 转发到 `http://localhost:8080`（见 `frontend/vite.config.ts`），无跨域问题。

如需前端直连后端，在 `frontend/.env.local` 中配置：

```
VITE_API_BASE_URL=http://localhost:8080
```

后端 `WebConfig` 已放行 `http://localhost:5174` 的跨域请求（OPTIONS 预检已验证通过）。

## 常见问题

- **后端窗口提示 "mvnw.cmd 不是内部或外部命令"**：部分环境（如 Git Bash 派生进程）携带 `NoDefaultCurrentDirectoryInExePath=1`，禁止 cmd 从当前目录查找可执行文件。`start.bat` 已在脚本内清除该变量并用 `.\mvnw.cmd` 显式路径调用，不受影响；若在其它终端手动执行，请同样使用 `.\mvnw.cmd` 写法。
- **后端首次启动较慢**：Maven Wrapper 首次需下载依赖（本机 Maven 仓库已缓存 Spring Boot 3.3.2 时通常几十秒内完成），以窗口出现 `Started FlowHubApplication` 为准。
- **端口冲突**：后端 8080、前端 5174 被占用时无法启动，先关闭旧的服务窗口（Vite 配置了 `strictPort`，不会静默换端口）。
- **`/actuator/health` 显示 DOWN**：RabbitMQ 未启动时 rabbit 健康组件为 DOWN（db 组件不受影响，应用功能正常，仅消息投递与消费延迟）。启动本机 RabbitMQ 后即恢复 UP，积压的 Outbox 事件由分发器自动补发、堆积的消息由消费者开始消费。`management.endpoint.health.show-details` 已设为 `always`，健康响应携带 db/rabbit/redis 组件细分，前端顶栏健康徽标（15s 轮询）的 Tooltip 据此展示各组件状态。

## 后续迭代指引

按 TD 文档分模块推进，每个迭代保持「后端接口 + 前端页面」可联调：

HTTP 请求边界补全计划见 [docs/export-http-boundary-plan.md](docs/export-http-boundary-plan.md)。
持久层补全计划见 [docs/persistence-domain-plan.md](docs/persistence-domain-plan.md)。
创建任务幂等与 Outbox 补全计划见 [docs/export-create-idempotency-outbox-plan.md](docs/export-create-idempotency-outbox-plan.md)。

1. 导出任务对订单查询契约的复用：创建导出任务时以 `OrderCriteria` 做 request snapshot（筛选导出），「勾选导出」经 `ids` 字段精确取数（[docs/order-query-design.md](docs/order-query-design.md) 第八节第 9 步；**创建入口已实现**，见 [docs/export-http-boundary-plan.md](docs/export-http-boundary-plan.md)）。
2. 导出任务闭环补全：文件发布、成功终态、下载接口、崩溃恢复、人工重试、过期清理、任务列表（P-4 真实化）均已实现（见 [docs/export-file-publishing-notes.md](docs/export-file-publishing-notes.md) 与 [docs/export-recovery-cleanup-notes.md](docs/export-recovery-cleanup-notes.md)）；后续为任务详情接口真实化（be-td.md 4.6-4.10、6-9）。
3. 进度推送前端消费：`useExportEvents` + SSE 事件消费 + 轮询降级（be-td.md 10、fe-td.md 6；开发计划见 [docs/export-sse-design.md](docs/export-sse-design.md)）——已随前端导出任务页交付并完成端到端联调。
4. 前端任务中心：导出任务列表、进度展示（SSE + 轮询降级）、下载与重试入口（fe-td.md 6-8）——已实现；后续为筛选条件 URL 同步与路由引入。
5. 订单导入前端：「导入任务」页（模板下载 + 上传受理 + 实时进度 SSE + 错误报告下载 + 人工重试入口，设计 F1-F5）尚未实现，后端已就绪可对接。
