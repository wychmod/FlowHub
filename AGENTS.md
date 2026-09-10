# AGENTS.md

本文件提供 FlowHub 仓库的完整操作指南，供 Claude Code 在处理本仓库代码时遵循。

## 交互约束

- **必须使用中文回答所有问题**，包括解释、状态说明、错误排查和实现建议。
- **生成的代码必须包含必要的注释，且注释务必精简**：对公开类/接口、非平凡方法、复杂逻辑、状态机和边界处理添加中文注释；类注释 1-3 行说明职责即可，方法注释 1 行说明功能，入参仅做简单描述；禁止长篇解释设计背景、复述签名自明信息（如 `@param raw 原始字符串`）或重复设计文档内容。
- **重大改动须同步文档**：当本次改动属于重大变更（如新增/变更接口、调整架构设计、新增依赖、改变运行方式、调整目录结构或端口等，会影响 README 或本文件描述的准确性）时，必须同步更新并提交 `README.md` 与本文件 `AGENTS.md`，保持文档与代码一致。

## 项目概述

FlowHub 是一个企业级异步 Excel 导出中心的教学/演示项目。完整架构设计（Outbox + RabbitMQ + Redis + SSE + SXSSF 流式 Excel）记录在 `docs/prd.md`、`docs/be-td.md`、`docs/fe-td.md` 中，尚未实现。订单查询能力（条件筛选 + 排序）已按 `docs/order-query-design.md` 的定稿设计实现并接入真实 MyBatis + MySQL 持久化；订单列表页前端（筛选 + 排序 + 勾选 + 导出入口）已按 `docs/order-page-fe/` 四件套方案实现；导出创建接口（`POST /api/v1/export-jobs`，DTO 校验 → Command 规范化 → 业务校验 → 幂等 → 同事务写 export_jobs/outbox_events → 202 受理）已按 `docs/export-http-boundary-plan.md` 实现；Outbox 可靠投递管道（`export/mq/`：分发器定时扫描 + Publisher Confirm 闭环 + 至少一次投递，见 `docs/export-outbox-reliable-delivery-notes.md`）与消费端（手动 Ack + CAS 条件抢占 + Attempt 审计：重复投递收敛为最多一次有效执行，见 `docs/export-consumer-claim-attempt-notes.md`）已实现；执行体已按第 15 章落地确定性数据读取管道（任务快照重建 + ID 高水位 + Keyset 游标批查，见 `docs/export-keyset-read-pipeline-notes.md`）；进度状态分层与实时通知已按第 16 章落地（MySQL 条件推进事实源 + Redis 可失败投影 + SSE AFTER_COMMIT 广播 + HTTP 校准，见 `docs/export-progress-state-notes.md`）；SXSSF 流式 Excel 生成已按第 17 章落地（滑动窗口 + 双临时文件隔离 + safeText 防注入，见 `docs/export-sxssf-writer-notes.md`）；文件发布协议与安全下载已按第 18 章落地（受控 exportRoot + 路径双层防腐 + ATOMIC_MOVE 原子发布 + markSucceeded 成功终态 + 数据库失败补偿删除 + 下载接口，见 `docs/export-file-publishing-notes.md`）；恢复与清理已按第 19 章落地（启动恢复收敛租约失效的 RUNNING + 人工重试 + 过期文件「删除成功才 EXPIRED」+ 孤儿文件三维对账，见 `docs/export-recovery-cleanup-notes.md`）。导出进度 SSE 前端消费（混合实时状态同步：SSE 通知 + `job_version` 版本栅栏 + HTTP 校准 + 轮询降级）的设计与开发计划见 `docs/export-sse-design.md`——纯前端交付计划（`useExportEvents` + 导出任务页），所有未实现的后端依赖（状态机执行器/SSE 端点/列表真实化等）统一列为前置条件管理，实现蓝本为参考项目的 `useExportEvents`。订单 Excel 导入功能（模板下载 + 文件/结构/行级三层校验 + 异步导入任务复刻 Outbox/消费/进度/SSE 管道 + 错误报告下载 + PARTIAL 部分成功语义 + 前端导入入口）：**后端已按 `docs/order-import-design.md` 完整实现**（包 `orderimport/`，含接受链、SAX 流式读、冲突预查、错误报告、维护清理与集成测试），导入格式锚定为导出全 9 列格式（导入导出互逆）；前端入口按设计为独立「导入任务」页（F1-F5），尚未实现。

- **后端**：Java 21、Spring Boot 3.3.2、MyBatis（`mybatis-spring-boot-starter` 3.0.3）、Flyway + MySQL、RabbitMQ（`spring-boot-starter-amqp`，发布确认闭环 + 手动 Ack 消费）、Maven（已内置 Wrapper）。
- **前端**：React 18、TypeScript、Vite 6、antd 6、@tanstack/react-query 5、dayjs。
- **端口约定**：后端 `8080`，前端 `5174`。

## 常用命令

### 一键启动

在 Windows 环境下，双击仓库根目录的 `start.bat`。脚本会自动安装前端依赖（首次），并打开两个窗口分别运行后端（8080）和前端（5174）。**启动后端前需先启动本机 3306 端口的 MySQL（存在 `flowhub` 库与 `flowhub/flowhub` 账号）**，否则 Flyway/数据源初始化会失败。RabbitMQ（默认 `localhost:5672`，guest/guest）为可选前置：未启动时后端照常运行，仅 Outbox 分发器每轮记录 `outbox_publish_deferred` 日志、消费监听容器后台持续重连，且 `/actuator/health` 的 rabbit 组件为 DOWN，Broker 恢复后自动补发并开始消费。如需用 Docker 一键起 RabbitMQ，可在仓库根目录执行 `docker compose up -d rabbitmq`（含 `rabbitmq-diagnostics ping` 健康检查与管理台 `http://localhost:15672` guest/guest）；Redis（活跃进度投影，`localhost:6379` 免认证，含 `redis-cli ping` 健康检查）同样纳入编排，`docker compose up -d redis` 即可（见 `docker-compose.yml`）。该编排不含 MySQL（MySQL 需与本机 3306 端口既有实例共存，避免端口冲突，仍按上述方式单独启动）。

### 后端（`backend/`）

```bash
# 启动 Spring Boot 服务
.\mvnw.cmd spring-boot:run

# 运行全部测试
.\mvnw.cmd test

# 运行单个测试类
.\mvnw.cmd test -Dtest=OrderControllerTest
```

后端测试不依赖外部 MySQL：`src/test/resources/application.yml` 将测试数据源指向 **H2 内存库（MySQL 兼容模式，`jdbc:h2:mem:flowhub_test;MODE=MySQL`）**，Flyway 在测试上下文对该 H2 执行迁移。

在 Unix/Linux/macOS 环境下将 `.\mvnw.cmd` 替换为 `./mvnw`。

**演示数据生成脚本**（`backend/scripts/seed-demo-data.sh`，Git Bash/Linux/macOS 下执行）：

```bash
cd backend/scripts
./seed-demo-data.sh                  # orders 表为空时装入 200,000 行确定性演示订单
SEED_ROWS=50000 ./seed-demo-data.sh  # 自定义行数（1 ~ 1,000,000）
```

脚本仅当 `orders` 表为空时写入（有数据则跳过，绝不覆盖）；数据生成 SQL 位于 `backend/scripts/sql/seed-demo-data.sql`（递归 CTE 实现，需 MySQL 8.0+），状态/渠道/币种按业务权重分布，客户名、手机号、省份、金额、下单时间均为确定性散列生成。连接参数可用 `DB_HOST/DB_PORT/DB_USER/DB_PASSWORD/DB_NAME` 环境变量覆盖，默认与 `application.yml` 一致；mysql 客户端不在 PATH 时会自动探测 Windows 常见安装路径。

### 前端（`frontend/`）

```bash
# 安装依赖并启动 Vite 开发服务器
npm install
npm run dev

# 类型检查并构建生产包
npm run build

# 预览生产构建
npm run preview
```

前端测试命令：

```bash
# 运行全部单测
npm test

# 监听模式
npm run test:watch
```

**前端改动自动校验 hook**：仓库 `.claude/` 配置了 `PostToolUse` hook（`Write|Edit`），每当改动 `frontend/` 目录下的文件时，会自动执行 `npm test && npm run build`；任一步失败都会以非零退出码标记。因此任何对前端源码的修改都必须通过单测与构建，新增功能时应同步补充对应单测。此 hook 的校验脚本为 `.claude/hooks/check-frontend.js`，可在 `/hooks` 菜单中查看或停用。

### 验证地址

- 前端页面：http://localhost:5174
- 健康检查：http://localhost:8080/actuator/health
- 订单接口：http://localhost:8080/api/v1/orders?page=1&page_size=20
- 导出任务列表接口：http://localhost:8080/api/v1/export-jobs（真实分页查询，可选 `status` 过滤，返回进度/下载/错误等完整派生字段，见 be-td.md 4.6）
- 导出任务创建接口：`POST /api/v1/export-jobs`（需 `Idempotency-Key` 头与 JSON 请求体，契约见 be-td.md 4.5）
- 导出任务文件下载：`GET /api/v1/export-jobs/{job_id}/download`（仅 SUCCEEDED 且未过期返回文件流，错误返回结构化 Envelope）
- 导出任务人工重试：`POST /api/v1/export-jobs/{job_id}/retry`（仅 FAILED 且未达尝试上限，202 受理回 PENDING；错误返回 `EXPORT_JOB_NOT_RETRYABLE`/`EXPORT_JOB_NOT_FOUND`）
- 订单导入模板下载：`GET /api/v1/import-jobs/template`（9 列表格，表头与导出格式互逆一致）
- 订单导入上传受理：`POST /api/v1/import-jobs`（multipart 字段 `file`，.xlsx 且 ≤10MB；文件级/结构级校验同步 400，行级校验异步）
- 订单导入任务列表：`GET /api/v1/import-jobs`（分页 + 可选 `status` 过滤，含 PARTIAL；返回 progress_percent/error_report_available/error_summary 派生字段）
- 订单导入任务 SSE：`GET /api/v1/import-jobs/events`（5 类事件，事件 id=`jobId:version`，15s 心跳）
- 订单导入错误报告下载：`GET /api/v1/import-jobs/{job_id}/error-report`（仅 PARTIAL 且登记了报告路径）
- 订单导入人工重试：`POST /api/v1/import-jobs/{job_id}/retry`（仅 FAILED 且未达尝试上限，202 回 PENDING）

### 验证与交付（阶段 08 记录）

**本机环境要求**：任一平台需 Docker、Java 21、Node 22、Chrome。**注意：当前开发机实际为 Node v20.16.0（低于 22）+ Windows 10，本机 3306/5672/6379 运行的是原生 MySQL/RabbitMQ/Redis（属同一进程 PID 2000），8080/5174 空闲**——因此全链路可走原生依赖直连，无需 Docker 绑端口（Docker 编排仅作可选前置，端口与既有原生实例冲突时不参与）。Java 已核验 21.0.3，Chrome 存在于 `C:\Program Files\Google\Chrome\Application\chrome.exe`。

**性能基线要求（契约）**：性能采集须在受控 JVM `-Xmx512m` + 运行令牌下执行，校验 JVM PID 与身份后记录：实际数据量、创建耗时、到达终态耗时、峰值 JVM 内存(RSS)、文件大小、机器环境。**该带令牌/PID 校验的性能脚本本仓库尚未落地（属参考项目的交付物）**，落库前不伪造基准。

**E2E 运行方式**：Playwright 真实浏览器链路（打开订单页→筛选/勾选→创建 Job→PENDING/RUNNING→SUCCEEDED→下载→校验工作簿表头/行数/所选订单）。前置：原生依赖健康 + orders 已有确定性数据（本机为 200,000 行 `EF2026-*`，恰落 10 万–30 万区间，无需重装载）。**Playwright 工程（`.spec.ts`/配置文件）本仓库尚未落地**，需先引入 @playwright/test 才可跑。

**已知未实现能力**：鉴权（无登录/权限/job 归属校验）、对象存储（文件在本地 exportRoot，非 OSS/S3）、多实例部署（SSE 广播为进程内连接表 + 单机 DB/文件，无跨实例 fanout 与分布式锁）。

## 架构说明

### 后端结构

后端代码位于 `backend/src/main/java/com/example/flowhub/`，按**横切 Web 基础设施**与**垂直业务模块**组织：

- `common/web/`：被所有业务模块复用的 Web 层基础设施。
  - `api/ApiResponse`：统一响应 Envelope `{code, message, data, trace_id}`，字段使用 `snake_case`；`ApiV1` 为控制器版本命名空间标记注解。
  - `api/ApiResponseAdvice`：`ResponseBodyAdvice`，对返回裸对象的 `@RestController` 自动包装为统一 Envelope 并回写 `trace_id` 响应头；已包装响应、`Resource`/SSE/流式响应以及标注 `@RawResponse`（`api/RawResponse`）的接口按原样返回，避免二次包装。
  - `error/`：`ErrorCode`（HTTP 状态映射）、`BusinessException`、`GlobalExceptionHandler`，将异常统一转换为错误 Envelope；Bean Validation 失败（`BindException`/`MethodArgumentNotValidException`/`ConstraintViolationException`）在 `data.field_errors` 输出「字段路径 → 文案」对象映射（载体 `FieldErrorData`，与前端 `ApiError.fieldErrors` 结构一致，键为 Java 属性路径），`HttpMessageNotReadableException` 统一转 400 `VALIDATION_ERROR`；`ApiResponse.failure` 有携带 data 的三参重载。业务模块错误码（如 `export/error/ExportErrorCode`）在各自模块内实现 `ErrorCode` 接口，不进 `common/`。
  - `param/ParamUtils`：HTTP 入参归一化与解析公共工具（空值契约的代码化）：归一化类 `trimToNull`（null/空串/纯空白统一折叠为 null）、`splitMultiValue`（逗号多值拆分 + trim + 过滤空 token + 去重，空结果视为未传）、`enumFromName`（枚举常量名大小写不敏感解析，未识别返回 null 由调用方决定报错），失败由调用方决定报错方式；解析类 `parseDecimal`/`parseDateTime`/`parsePhone`/`parseMultiEnum`（数值/时间/手机号/多值枚举解析，null/空白返回 null 或空列表，非法统一抛 VALIDATION_ERROR 400，文案含字段名；`parseMultiEnum` 的 whitelist 由调用方注入且须为大写取值）。各白名单枚举的 `fromName`（如 `SortField`/`SortDirection`）均委托该工具；业务语义层（具体白名单取值、区间比较、错误文案）不属于此类——`parseMultiEnum` 仅提供白名单校验机制，取值仍由业务层定义。
  - `trace/`：链路追踪基础设施。`TraceIdFilter` 生成/透传 `trace_id`；`TraceIdSupport` 提供读取/生成/合法性校验工具；`MdcScope` 管理 MDC 作用域（退出时还原）；`MdcTaskDecorator` 让异步线程继承提交线程的 trace 上下文。
  - `util/Sha256Utils`：SHA-256 摘要工具（UTF-8 编码、64 位小写 hex），供幂等 request hash 等场景复用。
  - `util/ExceptionUtils`：异常描述工具（`messageOrTypeName`：异常 message 空白时回退异常类名，保证失败原因可读），供日志 reason 与失败原因落库场景复用。
  - `config/` 的 `AsyncMdcConfiguration` 定义了统一异步线程池 `flowHubTaskExecutor`（带 `MdcTaskDecorator`），异步任务应注入该 bean 以保持 trace 链路贯穿。
  - `config/`：`WebConfig`（开发期 CORS）；`ApiWebMvcConfiguration` 用 `PathMatchConfigurer` 为所有 `@RestController` 统一追加 `/api/v1` 前缀，控制器只声明相对路径（如 `/orders`），版本号集中维护。
- `order/`：订单查询模块。`OrderController` 暴露 `GET /api/v1/orders`（分页 + 条件筛选 + 排序，契约见 `docs/order-query-design.md`；排序为 `sort_by` + `sort_order` 两个独立参数，`sort_order` 缺省用字段默认方向兜底、脱离 `sort_by` 单独出现返回 400，响应以 `sort_by`/`sort_order` 回显实际生效排序）；`OrderRequest` 为 record（snake_case 参数经 `@BindParam` 构造器绑定），`OrderService` 负责入参归一化与语义级校验后组装 `OrderQuery`（`query/` 包：`OrderQuery`/`OrderCriteria` 值对象 + `SortField` 排序白名单 + `FilterOperator` 操作符枚举）；持久化为 MyBatis 实现（`@Mapper` 接口 + `resources/mapper/OrderMapper.xml` 动态 SQL，列别名驼峰 + record 构造器自动映射）；测试数据由 `TestOrderDataSeeder` 夹具灌入 H2，确定性数据口径与 `seed-demo-data.sql` 对齐。分层为 `controller/dto/service/mapper/entity/vo/query`。
- `export/`：导出任务模块。`ExportJobController` 暴露 `POST /api/v1/export-jobs`（创建入口）、`GET /api/v1/export-jobs`（列表分页查询，支持可选 `status` 过滤（大小写不敏感白名单校验，非法 400 `VALIDATION_ERROR`），返回进度/下载/错误等完整派生字段，be-td.md 4.6）、`POST /api/v1/export-jobs/{job_id}/retry`（人工重试，be-td.md 4.8）与 `GET /api/v1/export-jobs/{job_id}/download`（文件下载，fe-td.md 7.1 契约）。列表 Service 层 `ExportJobService.listJobs` 真实分页并按状态计算 `progress_percent`/`downloadable` 派生字段（与 `ExportJobEventPayload.progressPercent` 同规则），字段与 SSE 事件 payload 对齐使前端可乐观更新。创建链路（契约见 be-td.md 4.5 与 `docs/export-http-boundary-plan.md`）：`dto/` 三件套（`CreateExportJobRequest`/`ExportSelectionRequest`/`ExportFilterSnapshotRequest`，record + JSON `@JsonProperty` snake_case 绑定，`@AssertTrue` 保证 SELECTED_IDS/FILTER 分支互斥）→ `command/`（`CreateExportJobCommand.from` 规范化：ID 剔空去重排序、`ExportColumn` 9 列白名单校验并按白名单序输出、`file_name` 清理路径分隔符等非法字符、FILTER 快照解析为 `OrderCriteria`（复用 `OrderFilterWhitelist`/`OrderSort`/`ParamUtils`，excluded_order_ids 归一进 `OrderCriteria.excludedIds`））→ `service/`（幂等键查询命中时比较 request hash（规范化 Command 的 SHA-256）：相同复用原任务、不同 409 `IDEMPOTENCY_CONFLICT`；业务校验与一致性快照用 `ExportOrderMapper.snapshotByCriteria` 单查询统计命中 COUNT 与范围内 MAX(id)（勾选 0 行 `EXPORT_SELECTION_EMPTY`、筛选 0 行 `EXPORT_FILTER_ZERO_ROWS`、超 `export.filter-max-rows` 上限 `EXPORT_FILTER_TOO_MANY_ROWS`）；`@Transactional` 同事务 INSERT `export_jobs`(PENDING) + `outbox_events`（payload 含 job_id/job_no/request_snapshot/columns/file_name/trace_id），并发撞幂等唯一键降级为复用/冲突判定）→ 202 + `vo/ExportJobAcceptedVO`。错误码在 `error/ExportErrorCode`；mapper 为 `ExportJobMapper`/`OutboxEventMapper`/`ExportJobAttemptMapper`/`ExportOrderMapper`（record 无 setter，INSERT 不用 useGeneratedKeys，job_id 由唯一幂等键查询取回）。执行侧（由消费端调用，设计见 `docs/export-consumer-claim-attempt-notes.md`）：`ExportJobService.claimPendingJob` 以 @Transactional 包「条件 UPDATE 抢占 + `insertRunning`」——仅 `status='PENDING' AND attempt_count<3` 可置 RUNNING（同事务 `attempt_count+1`、`version+1`、回填 started/heartbeat/lease，MAX_ATTEMPTS=3、租约 5 分钟，SQL 在 `ExportJobMapper.xml`），Attempt 以 `MAX(attempt_no)+1` 的 INSERT…SELECT 插入（同事务原子，回滚不留孤儿 RUNNING）；`markFailed` 同事务收敛 Job 与当前 RUNNING Attempt 为 FAILED（error_code/error_message/finished_at，errorMessage 截断 500）；`markSucceeded`（第 18 章发布协议第 3 步）同事务收敛 Job 与当前 RUNNING Attempt 为 SUCCEEDED 并回填 `file_path`/`file_size_bytes`/`finished_at`/`expired_at`（保留期 `export.files.retention-hours` 默认 24h，version 递增供 SSE/HTTP 校准），Job 条件更新 0 行抛异常回滚由执行体补偿删除已发布文件，提交成功后发布 `ExportJobChanged` 广播 job.succeeded；`retry`（第 19 章）以 `status='FAILED' AND attempt_count<3` 条件重置回 PENDING 并同事务写入新 Outbox 事件（复用创建事件契约，分发器/消费端零改动），失败 Attempt 历史保留。维护服务（第 19 章，见 `docs/export-recovery-cleanup-notes.md`）：`ExportMaintenanceService` 以 `@EventListener(ApplicationReadyEvent)`+`@Transactional` 启动恢复——`recoverExpiredRunning` 只收敛「租约已失效（含 NULL）」的 RUNNING 为 FAILED(SERVICE_RESTARTED)，Attempt 先行、Job 收尾（同一前置条件）；`@Scheduled(cron = export.cleanup-cron 默认每小时)` 执行过期清理（`findExpiredSuccess` 每批 100 → 受控解析 → `deletePublished`（返回 boolean）删除成功才 `markExpired` EXPIRED → `deleteProjection` 删 Redis 投影 → 发事件，路径非法保持 SUCCEEDED 下轮再试）与孤儿三维对账（`temporaryCandidatesOlderThan`/`finalCandidatesOlderThan` 候选扫描 + `countRunningWithValidLease` 活跃租约 + Job/Attempt `countByFilePath` 引用检查，三关全过才删，宽限期 1h）。`ExportExecutionService.execute` 为执行壳：业务异常捕获后收敛 FAILED 不外抛，收敛后以 `refreshProjection` 重读事实刷新投影。执行体（设计见 `docs/export-keyset-read-pipeline-notes.md`）为 Keyset 读取管道——`selectById` 加载任务快照 → `filter_snapshot`/`selected_columns` 反序列化重建取数条件与列序 → 循环 `ExportOrderMapper.findBatch`（`id > lastId AND id <= max_order_id_at_create` 主干 + 跨 Mapper 复用 `OrderMapper.criteriaConditions` 共享筛选片段 + `ORDER BY id ASC LIMIT`，SELECTED_IDS/FILTER 由 `criteria.ids` 是否为空自然区分）→ 每批先 `WorkbookSession.writeBatch` 写入再经 `ExportProgressService.report` 推进 → 空批/不足一批双结束。SXSSF 写 Excel（第 17 章，见 `docs/export-sxssf-writer-notes.md`）：`ExcelExportWriter` + `WorkbookSession`（`export/excel/`）——open 列白名单二次复核（未知列在创建文件前抛出）→ `SXSSFWorkbook(100)` 滑动窗口 + 压缩临时文件 → 表头/冻结首行/自动筛选/列宽/金额 `0.00` 样式单例；writeBatch 文本走 `safeText` 公式注入防护、金额写 NUMERIC 数值、时间固定格式；close 链 `write → 关流 → close → dispose`（首异常保留 + suppressed）。业务临时文件由 `ExportFileService` 在受控 exportRoot 内按 `<UTC 日期>/<jobId>/attempt-N.tmp` 分配（attempt 序号经 `ExportJobAttemptMapper.selectRunningAttemptNo`），任何失败路径删除半成品后原样上抛走失败收敛；读取+写盘完成后走第 18 章发布协议（设计见 `docs/export-file-publishing-notes.md`）——`publish` 同文件系统 `ATOMIC_MOVE` 将 `.tmp` 原子切换为 `attempt-N.xlsx`（`AtomicMoveNotSupportedException` 不降级、交由失败收敛），`markSucceeded` 事务提交成功即任务 SUCCEEDED，数据库提交失败时执行体补偿删除未登记正式文件（不留孤儿）；exportRoot 启动期提纯（toAbsolutePath+normalize+createDirectories+toRealPath），路径双层防腐（文本层 normalize 拒绝 `..` 与根组件、物理层逐段符号链接检查 + toRealPath 验真）覆盖临时分配/发布/下载解析/补偿删除每个入口；下载只按 Job 查（`getDownloadableFile`：SUCCEEDED 且未过期 → `resolvePersisted` 受控解析 → 存在性校验 → 文件流 + RFC 5987 展示文件名 `file_name.xlsx`），文件丢失不重新生成返回 `EXPORT_FILE_MISSING`、路径污染返回 `EXPORT_PATH_INVALID`。进度与通知（第 16 章，见 `docs/export-progress-state-notes.md`）：`ExportProgressService.report` 以条件 UPDATE（`status='RUNNING'` 单向 + `processed_rows <= 新值` 单调 + heartbeat/lease 续期，0 行 fail-fast 抛异常走失败收敛）推进事实源，成功后发布 `ExportJobChanged(jobId)` 应用事件（`export/event/`）；`ExportSseService` 以 `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` 在提交后**重读 Job** 广播 SSE（进程内连接表 + 坏连接移除 + `export.sse.heartbeat-ms` 默认 15s 心跳），端点 `GET /api/v1/export-jobs/events`（`ExportJobController`，4 类事件 `job.progress`/`job.succeeded`/`job.failed`/`heartbeat`，payload snake_case 见 `ExportJobEventPayload`——进度/成功事件省略 error 字段、失败终态显式携带，事件 id=`jobId:version` 供前端版本栅栏）；`markFailed` 同事务收敛后发布事件广播失败终态。Redis 投影（`spring-boot-starter-data-redis`，默认 `localhost:6379`）：`export:progress:<jobId>` Hash（status/processedRows/totalRows/percent/updatedAt）+ TTL 48h 尽力写入，失败记 `redis_progress_write_failed` 降级日志（应用照常运行，health 的 redis 组件为 DOWN）；RUNNING percent 封顶 99、SUCCEEDED 才 100。
  - `mq/`：Outbox 可靠投递管道。`RabbitConfig`（`@Configuration`；`@EnableScheduling` 收口于主启动类 `FlowHubApplication`）声明 durable direct 交换机 `export.job.exchange` + 业务队列 `export.job.queue`（routing key `export.job.create`，带 DLX 死信参数）+ 死信交换机/队列 `export.job.dlx`/`export.job.dlq`（DLQ 绑同一 routing key 接住原 key 死信）；`OutboxDispatcher` `@Scheduled` 定时扫描 `findUnpublished`（`published_at IS NULL`，单轮上限 100），发送最小契约消息 `ExportJobMessage`（schema_version/message_id/job_id/event_version，message_id 由 outbox 事件 id 经 `UUID.nameUUIDFromBytes` 稳定派生，Header 携带 `X-Trace-Id`），等待 Publisher Confirm——**仅 ACK 且无 Returned 才 `markPublished`（`AND published_at IS NULL` 单向回填）**；send 异常/NACK/退回/超时一律保留事件并记 `outbox_publish_deferred` 日志（至少一次投递，Job 不改状态）。配置 `export.outbox.dispatch-delay-ms`/`export.outbox.confirm-timeout-ms`（默认 5000）；`OutboxEventMapper.findUnpublished/markPublished` 的 SQL 在 `resources/mapper/OutboxEventMapper.xml`。
  - `ExportJobConsumer`（同 `mq/` 包）：`@RabbitListener(JOB_QUEUE, ackMode="MANUAL", concurrency="2")` 手动确认消费 `ExportJobMessage`——trace Header 合法恢复/缺失新建（`MdcScope` 包裹全程）→ 契约不支持（schema 未知/正文非法/缺 job_id）`basicReject(requeue=false)` 转 DLQ → `claimPendingJob` 抢占失败（重复投递/已被抢占/达上限/不存在）直接 `basicAck` 无副作用 → 执行服务内部收敛失败后 `basicAck`；claim 事务或 Channel 异常穿出不确认（保留重投机会）。消费语义与控制流见 `docs/export-consumer-claim-attempt-notes.md`。

所有 JSON 接口均返回统一 Envelope。参数校验失败返回 HTTP 400，`code` 为 `"VALIDATION_ERROR"`。控制器采用构造器注入，并对查询参数使用 `@Validated` 校验。

- `orderimport/`：订单 Excel 导入模块（后端完整，设计见 `docs/order-import-design.md`）。`ImportJobController` 暴露 6 个端点（前缀 `/api/v1/import-jobs`）：`GET /template`（9 列模板下载，`ImportTemplateWriter` 内存 byte[]，纯表头不放示例行 + 三枚举列下拉 + 金额列整列预设 `0.00` 数值格式 + 填写说明）、`POST`（multipart 上传受理，三层校验：文件级=后缀/.xlsx+PK 魔数/≤10MB、结构级=数据 Sheet 名须为「订单数据」/表头恰好 9 列（多列/缺列/名称/顺序不符均拒绝）/空文件/行数上限，均为同步 400 拒绝；行级异步）、`GET`（分页列表 + 状态白名单含 PARTIAL + 派生字段 progress_percent/error_report_available/error_summary）、`GET /events`（SSE 5 类事件）、`GET /{job_id}/error-report`（错误报告 xlsx 下载，仅 PARTIAL 且登记路径放行）、`POST /{job_id}/retry`（FAILED→PENDING 人工重试 + 新 Outbox）。受理链 `ImportJobService.createJob`：文件落盘（`ImportFileService.persistUpload` 分配 `<日期>/<jobNo>/upload.xlsx`，DB 失败补偿删除）→ `ExcelImportReader.scanStructure` SAX 轻扫（`XSSFSheetXMLHandler` + `ReadOnlySharedStringsTable` + `DataFormatter`，列位由 cell reference 推导，`widestColumn` 记录表头实际列数供严格校验）→ 同事务 INSERT `import_jobs`+`outbox_events` → 202 + `ImportJobAcceptedVO`。执行侧（消费端调用）：`claimPendingJob` CAS 抢占（PENDING + attempt_count<3，同事务建 RUNNING Attempt）+ `ImportExecutionService.execute`（SAX `readRows` 逐行 → `ImportRowValidator` 行级校验/枚举/金额/时间严格文本 → 文件内 HashSet 查重 → `ImportOrderMapper.selectExistingOrderNos` 冲突预查 → `insertBatch` 批量入库 + DuplicateKey 逐行降级（错误报告行号取自 `PendRow.excelRowNo` 与 Excel 行对齐）→ `ImportProgressService.report` 进度推进 → 终态收敛：skipped==0 `markSucceeded`、否则 `ImportErrorReportWriter` 生成错误报告后 `markPartial`（DATABASE 失败补偿删除报告））；状态机 `PENDING→RUNNING→SUCCEEDED|PARTIAL|FAILED→EXPIRED`，`FAILED→PENDING` 人工重试，PARTIAL 不可重试。`ImportOutboxDispatcher`+`ImportRabbitConfig`+`ImportJobConsumer` 复刻导出可靠投递链路（独立拓扑 `import.job.*`，避免与导出 bean/队列冲突）。`ImportMaintenanceService` 启动恢复（租约失效 RUNNING→FAILED(SERVICE_RESTARTED)）+ 定时过期清理（SUCCEEDED/PARTIAL 删除成功才 EXPIRED）+ 孤儿对账（宽限期 + Job/Attempt 引用 + 活跃租约三维，错误报告候选按 jobNo+attemptNo 判执行在岗）。`ImportSseService` 5 类事件（`import.progress/succeeded/partial/failed/heartbeat`，事件 id=`jobId:version`，发送前重读 Job）；`ImportProgressService` MySQL 条件推进 + Redis 投影 `import:progress:<id>`（TTL 48h，失败降级）。`ImportColumn` 为导入侧列语义枚举，key 委托 `ExportColumn`、中文表头委托 `ExcelExportWriter.titleOf`——表头单一事实源在导出列定义表，导出调整时导入自动跟随。

以下六条为**全仓库强制编码约束**，适用于所有新增/修改的后端代码；详细设计与示例见 `docs/order-query-design.md`（第四节、第七节）：

1. **数据承载类型一律使用 record**：实体（entity）、请求/响应 DTO、VO、跨层值对象（如 `OrderQuery`）全部用 record 实现，享受不可变性与语义明确的访问器。**例外条款**：仅当 record 在具体场景存在框架级问题且无绕行方案时才降级为普通类（如 Web 绑定/校验注解实测不生效），降级须在代码注释与 `docs/order-query-design.md` 中记录原因；不涉及 Web 绑定的类型（实体、值对象、VO）**无例外**。
2. **HTTP 入参的 snake_case → 驼峰映射统一使用 `@BindParam`**（`org.springframework.web.bind.annotation.BindParam`，Spring Framework 6.1+ 构造器绑定，本项目 Boot 3.3.2 满足）：Request DTO 写成 record，snake_case 参数在组件上标注 `@BindParam("xxx_yyy")`，Bean Validation 注解直接标注在 record 组件上；**禁止**为兼容下划线参数名编写别名 getter/setter（历史 `OrderRequest.getPage_size()` 别名已随 record 化删除，作为反例警示）。可缺省的入参字段用包装类型（`Integer`/`String` 等），默认值在紧凑构造器中兜底，区分「未传」（null）与「传了零值」。响应侧 snake_case 序列化仍用 Jackson `@JsonProperty`（`@BindParam` 只管入参）。
3. **各层显式空值防御，杜绝 NPE/500**：入参 `null` 是唯一合法的「未传」表达，禁止空串/`0` 哨兵值；字符串入参先 `trimToNull` 归一；逗号拆分的多值参数过滤空 token，拆分后空集合视为未传（防 MyBatis `<foreach>` 生成 `IN ()` 非法 SQL）；非法枚举值立即抛 `BusinessException(VALIDATION_ERROR)`，绝不静默忽略；`BigDecimal` 比较一律用 `compareTo` 而非 `equals`；值对象集合字段构造时 `List.copyOf`（拒绝 null 与 null 元素）；空查询结果返回空集合而非 `null`；可空字段以 `org.springframework.lang.@Nullable` 显式标注；单测须覆盖「全条件为 null / 空白串 / 空集合 / 区间单端」等空值用例。
4. **注解排版横竖以「单行能否放下」为界**：判据是「全部注解 + 目标声明」写在同一行是否会折行。
   - **横版**：注解均为无属性的标记注解（`@Valid`、`@ModelAttribute`、`@GetMapping` 等），叠加后单行放得下，与目标声明同行书写，如 `listOrders(@Valid @ModelAttribute OrderRequest request)`；
   - **竖排**：注解带属性（`@BindParam("xxx")`、`@Min(value = 1, message = "...")`、`@RequestParam(defaultValue = "1")` 等），或横排将被迫折行——此时每个注解独占一行，目标类型声明另起一行收尾。
   禁止把放得下的短注解也竖排（过度竖排），也禁止把超宽注解挤在同一行导致折行错位。多字段 record 头部按业务维度用 `// ==== 分组名 ====` 注释 + 空行分段。排版基准示例：横版见 `order/controller/OrderController.java`，竖排见 `order/dto/OrderRequest.java`（`@BindParam` + Bean Validation 竖排、分页/筛选/排序三段分组）。
5. **优先复用公共工具类，杜绝重复造轮子**：编写归一化、解析、格式转换等通用逻辑前，**必须先检查 `common/web/` 下是否已有同等能力的工具类**（如 `param/ParamUtils`），有则直接复用，禁止在业务类中私有重写；确无现成实现、且该逻辑与具体业务无关并预计存在第二个消费方（如导出模块复用）时，应直接沉淀为 `common/web/` 下的公共工具类并补充单测，而非私有在业务 Service 内。反例警示：`trimToNull` 曾私有在 `OrderService`，已抽取为 `ParamUtils` 并让 `SortField.fromName`/`SortDirection.fromName` 同步委托。
6. **注释精简，只说签名看不出来的事**：类 javadoc 1-3 行说明职责即可；方法 javadoc 原则上 1 行说明功能；`@param`/`@return` 仅在参数含义、取值约束或返回值语义无法从签名自明时才写，且每个 1 行以内。**禁止**：长篇复述设计文档内容（契约细节以 docs/ 为准，注释留引用即可）、解释历史改动过程、为方法体只有一两行的简单方法写多行 javadoc。仅当存在签名无法表达的**关键设计决策或边界约束**（如「BigDecimal 必须用 compareTo」「NULL 视为最小值与 MySQL 对齐」）时才额外说明。排版基准示例：`common/web/param/ParamUtils.java`。

### 前端结构

前端代码位于 `frontend/src/`，按应用层、API 层、业务 feature 分层：

- `main.tsx`：应用入口，挂载 React 根节点，配置 react-query、antd 中文语言包、dayjs 中文 locale；`ConfigProvider` 定制 antd theme（token：`borderRadius: 8`/`colorBgLayout`/中文字体栈；components：Layout headerBg/siderBg/bodyBg、Menu dark 胶囊选中态、Table headerBg），组件内颜色一律取 token 不硬编码；`ConfigProvider` 内以 antd `<App>` 包装根组件（页面经 `App.useApp()` 使用 `message`/`modal`，避免静态方法无上下文告警）。
- `App.tsx`：根组件，目前用本地状态切换两个页面（尚未引入路由），向订单页传入 `onNavigate`（创建成功后「查看任务」跳转导出任务页）。
- `app/AppLayout.tsx`：全局布局壳层——深色 Sider（品牌 Logo 块 + 导航，collapsible 折叠按钮在顶栏，sticky 常驻）+ 白色顶栏（动态页题 + 页题图标块 + 后端健康徽标 + 「演示环境」Tag），不包含业务逻辑；页题文案事实源在 `app/layoutMeta.ts`（`PAGE_META`/`pageLabel`，node 单测覆盖），菜单文案由其派生。`app/HealthBadge.tsx` 为顶栏全局健康徽标（react-query 15s 轮询 `/actuator/health`，四态：检测中/服务正常/服务异常/服务不可达，错误优先于缓存数据；Tooltip 展示 db/rabbit/redis 组件细分）——健康响应是 Actuator 原生 JSON 而非统一 Envelope，经 `api/healthApi.ts` 独立做结构校验与错误转换，不复用 `requestJson`。
- `styles/index.css`：全局样式——`scrollbar-gutter: stable` 滚动条占位（防整页横跳）、`.orders-table` 数字等宽（`font-variant-numeric: tabular-nums`）。
- `api/http.ts`：JSON 请求防腐层（fe-td.md 4），页面只消费业务数据与 `ApiError`，不接触 Envelope/Header/状态码。`requestJson` 统一处理请求头、`asEnvelope` 合法 Envelope 结构校验与解包（成功只返回 `data`；2xx 非 Envelope 抛 `Invalid API envelope`）；HTTP/业务错误统一抛出 `ApiError`（保留 message/code/status/traceId/fieldErrors，fieldErrors 取自错误 Envelope data 的「字符串键值映射」，为字段级校验契约占位）；`envelopeToError` 供文件下载层复用；默认走 Vite 代理，可通过 `VITE_API_BASE_URL` 直连后端。
- `api/download.ts`：文件下载协议工具（fe-td.md 7），处理「同一 URL 成功是二进制文件、失败是 JSON 错误包或网关文本」的分流：`parseBlobError`（失败响应转 ApiError：JSON 错误 Envelope 优先，非 JSON/缺 code 的 JSON 降级为文本错误并截断正文至 200 字符；返回 ApiError 由调用方 throw）、`filenameFromDisposition`（Content-Disposition 文件名解析：`filename*=UTF-8''` 优先并解码非 ASCII，带引号 `filename` 次之，解码失败返回 null 由调用方兜底）、`saveBlob`（Object URL + 临时 `a` 标签触发浏览器保存并释放）。
- `api/exportApi.ts`：导出任务领域 API：列表查询 `listExportJobs`（返回进度/下载/错误等完整字段，类型含 `job_version`/`processed_rows`/`total_rows`/`progress_percent`/`downloadable`/error/`file_size_bytes` 等，字段与 SSE 事件 payload 对齐）+ 创建接口 `createExportJob`（POST + `Idempotency-Key` 头，`selection` 勾选/筛选两种模式判别联合（FILTER 分支可携带 `excluded_order_ids` 反选排除，be-td.md 4.5）、`EXPORT_COLUMN_OPTIONS` 9 列白名单）+ 下载接口 `downloadExportJob`（fe-td.md 7.1：!ok 经 `parseBlobError` 转 ApiError，成功读文件流并按「响应头解析 → `job.file_name` → 任务编号」兜底链命名触发保存）+ 人工重试 `retryExportJob`（POST `/{job_id}/retry`）+ SSE 端点常量 `exportEventsUrl`（`/api/v1/export-jobs/events`）。
- `features/orders/`：订单列表页（筛选 + 排序 + 勾选 + 导出入口，方案见 `docs/order-page-fe/`）。`OrderListPage` 为页面编排层与唯一状态持有者（草稿 antd Form / 已提交 filter+sort+page+pageSize / 本地 selectedIds 与导出弹窗，服务端数据只来自 useQuery 并做最近成功兜底）；useQuery 配置 `placeholderData: keepPreviousData`（`isPlaceholderData` 驱动表格遮罩、并 guard 排序回显与兜底 effect），`lastDataRef` 仅作查询失败时的错误兜底（F18）；9 列固定 width + `tableLayout: fixed` + `scroll x/y`（表体定高内滚）的防抖动列宽约定，翻页/排序/筛选零布局位移；页面本体为筛选/表格两张 Card，页题在布局顶栏；`components/OrderFilterForm`（8 项筛选草稿表单，展开/收起）、`components/ExportModal`（导出范围只读 + 列/文件名配置 + 失败保留重试；FILTER 模式且存在跨页勾选时显示「排除已勾选的 N 条订单」复选框（默认选中，取消则不携带排除），范围行同步显示「已排除勾选 N 条」）；`filters.ts`（草稿→已提交映射、导出筛选快照与反选 selection 组装 `buildFilterSelection` 纯函数——排除列表空时 `excluded_order_ids` 字段折叠不出现）、`selection.ts`（勾选 1000 上限规则）、`constants.ts`（枚举中文选项/Tag 色/默认排序分页）、`api.ts`（查询契约序列化 + `formatLocalDateTime` 时间格式事实源）、`idempotency.ts`（导出创建幂等键生命周期纯函数，单测 4 用例）。导出创建流程：幂等键在点击「确认创建」时解析——`attemptRef` 持有进行中的提交意图，同 payload 重试复用同一 Key（网络重试去重），修改表单（payload 变化）生成新 Key，创建成功/取消后置 null 失效；创建成功后 `invalidateQueries(['exportJobs'])` 刷新任务列表缓存（留在本页也即时生效）**且不清空勾选**，失败保留弹窗/勾选/表单与错误（含 trace_id）。
- `features/exports/`：导出任务页（完整实现，docs/export-sse-design.md 交付计划）。`ExportJobsPage` 为页面编排层——SSE 实时进度 + 列表 + 下载/重试操作列 + 分页，三条件轮询降级（非 sse 模式 + 有 PENDING/RUNNING 时按页面可见性 3s/15s）；`useExportEvents` Hook（连接状态机 connecting/sse/polling/offline + 退避重连 + `job_version` 版本栅栏乱序防御 + 乐观局部更新 + 失效收敛 + 挂载/可见性/在线状态生命周期清理，job_id 数字/字符串口径统一转字符串比较）；`components/ConnectionBadge`（通道状态徽标，只描述连接故障不夸大任务失败）；`types.ts`（`ExportEventConnectionState`）。Hook 与页面的 react-query 缓存键为 `['exportJobs', { page, page_size }]`。

`frontend/vite.config.ts` 将 `/api` 与 `/actuator` 代理到 `http://localhost:8080`。如需前端直连后端，可在 `frontend/.env.local` 中配置 `VITE_API_BASE_URL=http://localhost:8080`。后端 `WebConfig` 已允许 `http://localhost:5174` 的跨域请求。

### 数据流

1. 前端通过 Vite 代理请求 `/api/*`。
2. `TraceIdFilter` 为每个请求生成或透传 `trace_id`，写入 MDC 与响应头。
3. 控制器直接返回数据或 `ApiResponse`，`ApiResponseAdvice` 自动包装为统一 Envelope（裸对象/已包装均被正确处理），异常由 `GlobalExceptionHandler` 统一处理。
4. 前端 `requestJson` 解包 `data`，并在错误中保留 `trace_id`。

## 已实现 vs. 计划实现

当前已实现：
- 统一响应 Envelope 与全局异常处理（含 `ApiResponseAdvice` 自动包装裸对象响应、Bean Validation 失败的 `data.field_errors` 字段级错误映射）。
- 导出任务创建接口（`POST /api/v1/export-jobs`：DTO 跨字段校验 → `CreateExportJobCommand` 规范化 → 存在性/命中数业务校验 → 幂等键 + request hash 判重（复用/409 冲突）→ 同事务写 `export_jobs`(PENDING) 与 `outbox_events` → 202 受理，契约见 `docs/export-http-boundary-plan.md`；筛选命中上限 `export.filter-max-rows` 可配置，默认 500000）。
- Outbox 可靠投递管道（`export/mq/`：`RabbitConfig` 拓扑 + `OutboxDispatcher` 定时扫描发布 + `ExportJobMessage` 最小契约消息；Confirm ACK 且无 Returned 才回填 `published_at`，失败/NACK/退回/超时保留事件下轮补发并记 `outbox_publish_deferred` 日志；`spring.rabbitmq` 配置 `publisher-confirm-type: correlated` + `publisher-returns` + `template.mandatory: true`，见 `docs/export-outbox-reliable-delivery-notes.md`）。
- RabbitMQ 消费端（`ExportJobConsumer` 手动 Ack 消费 + `ExportJobService.claimPendingJob/markFailed` CAS 条件抢占与失败收敛 + `ExportJobAttemptMapper` Attempt 审计 + `ExportExecutionService` 执行壳：重复投递收敛为最多一次有效执行，抢占与 Attempt 同事务原子，契约不支持 Reject 转 DLQ，执行失败收敛 Job/Attempt FAILED；`listener.simple` 配 manual/prefetch=1/concurrency=2，集成测试 `ExportJobConsumerTest`，见 `docs/export-consumer-claim-attempt-notes.md`）。
- 确定性数据读取管道（第 15 章：创建时 `ExportOrderMapper.snapshotByCriteria` 单查询统计命中 COUNT 与范围内 MAX(id) 高水位；执行体 `ExportExecutionService.runJob` 按任务快照 Keyset 批量读取——`findBatch` 复用 `criteriaConditions` 共享片段以 `ORDER BY id ASC LIMIT` 恒定成本推进，每批落 `processed_rows`，空批/不足一批双结束；`ExportExecutionIntegrationTest` 7 用例覆盖高水位阻断/批次边界/快照重建/排除 ID/空值防御/round-trip，见 `docs/export-keyset-read-pipeline-notes.md`）。
- 进度状态分层与实时通知（第 16 章：条件 UPDATE 进度守卫（`processed_rows` 单调 + 终态单向 + heartbeat/lease 续期，0 行 fail-fast）+ `ExportJobChanged` 应用事件 + AFTER_COMMIT SSE 广播（4 类事件、`jobId:version` 事件 id、15s 心跳、坏连接隔离、发送前重读 Job）+ Redis Hash 投影（TTL 48h、失败降级、99% 封顶）+ 回滚事务不广播；集成测试 `ExportProgressSseIntegrationTest` 8 用例与 `ExportJobEventPayloadTest` 6 用例，见 `docs/export-progress-state-notes.md`）。
- SXSSF 流式 Excel 生成（第 17 章：`ExcelExportWriter` + `WorkbookSession`——窗口 100 滑动 + 压缩临时文件 + 列白名单二次复核 + safeText 公式注入防护 + 金额 NUMERIC/样式单例 + close 链 write→close→dispose（suppressed exception）；`ExportFileService` 受控分配临时文件、失败路径删除半成品；Writer 单测 6 用例以真实 XSSF 重开断言 + 执行体扩展 2 用例（行数一致/写失败不虚假推进+清理），见 `docs/export-sxssf-writer-notes.md`）。
- 文件发布协议与安全下载（第 18 章：`ExportFileService` 受控文件边界——exportRoot 提纯、层级目录 `<UTC 日期>/<jobId>/attempt-N`、路径双层防腐（normalize 拒 `..` 与根组件 + 逐段符号链接/toRealPath 验真）；执行体「写完 .tmp → publish 原子移动发布 → markSucceeded 事务登记 → DB 失败补偿删除」收敛顺序，`AtomicMoveNotSupportedException` 不降级；`ExportJobService.markSucceeded` 同事务置 Job/Attempt SUCCEEDED 并回填 file_path/file_size_bytes/finished_at/expired_at；下载接口 `GET /{job_id}/download` 只按 Job 查（SUCCEEDED 且未过期 → resolvePersisted 受控解析 → 文件流）；`ExportFileServiceTest` 8 用例 + `ExportJobDownloadControllerTest` 7 用例 + 执行体发布/补偿用例，见 `docs/export-file-publishing-notes.md`）。
- 恢复与清理（第 19 章：`ExportMaintenanceService` 启动恢复只收敛「租约已失效」的 RUNNING 为 FAILED(SERVICE_RESTARTED)（Attempt 先行、Job 收尾、不自动重跑）；`POST /{job_id}/retry` 人工重试（FAILED→PENDING 条件重置 + 同事务新 Outbox，失败 Attempt 证据保留，`EXPORT_JOB_NOT_RETRYABLE` 409）；过期清理「文件删除成功才 markExpired EXPIRED」（路径非法/删除失败保持 SUCCEEDED 下轮再试，条件更新 0 行不发事件）+ Redis 投影删除；孤儿文件三维对账（宽限期 1h + 活跃租约 `countRunningWithValidLease` + Job/Attempt 引用检查）；`ExportMaintenanceIntegrationTest` 6 用例（活跃租约不误伤/重试保留历史+新 Outbox+抢占递增/上限拒绝/删除即事实/孤儿 tmp 活跃保护/xlsx 三态），见 `docs/export-recovery-cleanup-notes.md`）。
- API v1 统一路径前缀（`ApiWebMvcConfiguration` 为所有 `@RestController` 追加 `/api/v1`）。
- `trace_id` 生成与链路透传（含异步线程 MDC 上下文传递）。
- MySQL 数据源与 Flyway 迁移接入（`spring.datasource.*` + `spring.flyway.enabled=true`，应用启动时自动执行迁移脚本）。
- 订单列表接口（真实 MyBatis + MySQL 持久化、服务端分页 + 条件筛选 + 排序白名单与 sort_by/sort_order 回显，契约见 `docs/order-query-design.md`；含内存/MyBatis 行为对齐测试）。
- 导出任务列表接口（真实分页查询 + 可选 `status` 过滤 + 派生字段 progress_percent/downloadable，契约 be-td.md 4.6；Maven 侧 `findPage`/`countAll` SQL 在 `ExportJobMapper.xml`，集成测试 `ExportJobListTest` 9 用例覆盖空表/倒序分页/状态过滤/派生计算/错误字段透出，并发幂等由 `ExportJobIdempotencyConcurrencyTest` 2 用例验证唯一约束裁决）。
- 前端订单列表页（react-query + antd Table 服务端分页）：8 项条件筛选（草稿/已提交严格分离，输入不触发请求）、订单号/金额/下单时间三列表头三态排序（以响应回显对齐）、跨页勾选（上限 1000 条，超限整体拒绝）、「导出已选 / 导出筛选结果」入口与配置弹窗（9 列白名单、文件名、幂等键，按 be-td.md 4.5 契约先行，后端创建接口就绪前失败走统一错误提示）；查询失败保留上次数据与用户意图。
- 前端界面主题 token 化与列表防抖动治理：深色 Sider 品牌区 + 白色顶栏动态页题 + Card 分区布局（主题收敛于 `main.tsx` 的 ThemeConfig）；`scrollbar-gutter: stable` 滚动条占位、订单表格 9 列固定 width（`tableLayout: fixed`）与表体定高内滚（`scroll.y`）、`placeholderData: keepPreviousData` 平滑过渡，翻页/排序/筛选时零布局位移。
- 前端 API 防腐层：`requestJson` 合法 Envelope 结构校验（2xx 非 Envelope 抛 `Invalid API envelope`）与统一错误转换（`ApiError` 携带 message/code/status/traceId/fieldErrors）；文件下载协议工具 `api/download.ts`（`parseBlobError`/`filenameFromDisposition`/`saveBlob`，fe-td.md 7）与业务语言下载接口 `downloadExportJob`。
- 前端导出任务页（docs/export-sse-design.md 交付）：`useExportEvents` Hook（SSE 连接状态机 + 版本栅栏 + 乐观局部更新 + 失效收敛 + 降级轮询 + 生命周期清理，jsdom 单测 6 用例）+ `ExportJobsPage` 完整页面（状态 Tag/进度条/文件大小/下载/重试/分页/ConnectionBadge）+ `api/exportApi.ts` 补全 `retryExportJob`/`exportEventsUrl` 与完整列表类型；后端 P-4 列表真实化联动（`findPage`/`countAll`/派生字段）；已做真实后端端到端联调（创建 5 万行任务 → 执行推进 → SUCCEEDED → 下载 1.6MB Excel → 重试 409）。

- 订单 Excel 导入后端（第 V9~V17 章/B1-B17 完整落地：Flyway V9 建表、模板/ExcelImportReader SAX/acceptance 三层校验/Outbox+消费+进度+SSE 全链路/PARTIAL 错误报告/维护清理与集成测试（含 `ImportProgressSseIntegrationTest` 进度守卫/AFTER_COMMIT/坏连接隔离矩阵），见 `docs/order-import-design.md`）。

设计文档中规划但尚未实现：
- 订单 Excel 导入前端（独立「导入任务」页 F1-F5 尚未开发，后端就绪可对接）。
- 导出任务闭环补全（文件发布、成功终态、下载接口、崩溃恢复、人工重试与过期清理均已随第 18/19 章落地，见 `docs/export-file-publishing-notes.md` 与 `docs/export-recovery-cleanup-notes.md`；导出任务列表接口真实化（P-4）已随前端导出页交付落地；后续为任务详情接口与第 20 章架构复盘（多实例部署/对象存储/集群调度）；`OrderCriteria` 快照重放与 `ids` 精确取数的查询契约已就绪，见 `docs/order-query-design.md` 第八节第 9 步）。
- Redis（幂等缓存与状态缓存；进度投影缓存已随第 16 章落地）。
- 导出任务详情接口（任务详情未实现；列表真实化、SSE 事件、下载与重试均已落地，前端导出页与 `useExportEvents` 已随 `docs/export-sse-design.md` 交付并完成端到端联调）。
- 前端导出任务页的筛选条件 URL 同步与路由（页面本体、SSE 实时进度、下载与重试均已实现）。

新增功能时，应保持后端各业务模块垂直自治（`order/` 或 `export/` 下自包含 `controller/dto/service/mapper/entity/vo`），横切 Web 能力只放在 `common/web/`。
- 控制器层的复杂查询/提交入参优先封装为 `xxxRequest` DTO，不要在方法签名里堆叠多个 `@RequestParam` 或零散字段；默认值、校验规则和后续扩展字段都收敛在 Request DTO 内。DTO 采用 record 形态，snake_case 参数名经 `@BindParam` 绑定（见上文「后端编码规范」）。

## 补充说明

- 已接入 MySQL 数据源与 Flyway：`application.yml` 配置了 `spring.datasource.url/username/password` 与 `spring.flyway.enabled=true`，启动时 Flyway 自动执行 `src/main/resources/db/migration/` 下的迁移脚本（当前已迁移至 V8，含 orders 全部导出业务列、export_jobs/outbox_events 完整表结构与订单查询索引 V8__add_order_query_indexes.sql）；`FlowHubApplication` 已移除 `DataSourceAutoConfiguration` 排除项。启动后端前需保证本机 3306 端口 MySQL 存在 `flowhub` 库与 `flowhub/flowhub` 账号。订单查询已走真实 MyBatis（`mybatis.mapper-locations` 加载 `resources/mapper/` 下全部 XML），**真实库的 orders 表为空时接口将返回空列表**，需先用上文「演示数据生成脚本」灌入演示数据；测试上下文的 H2 数据由 `TestOrderDataSeeder` 预置，注意 `src/test/resources/application.yml` 会整体遮蔽主配置，mybatis 配置需两处同步维护。
- 导出创建的业务配置：`export.filter-max-rows`（筛选导出命中行数上限，默认 500000，超限返回 `EXPORT_FILTER_TOO_MANY_ROWS`）；导出勾选上限 1000 由 DTO `@Size` 与 Command 防御校验共同承担。
- 导出执行的业务配置：`export.execution.batch-size`（执行体 Keyset 批查每批行数，默认 1000）、`export.execution.lease-minutes`（进度推进时 heartbeat/lease 续期时长，默认 5）、`export.files.dir`（受控文件根目录 exportRoot，默认 `export-files/`，已被 `.gitignore` 忽略；内部按 `<UTC 日期>/<jobId>/attempt-N.tmp|xlsx` 三层定位）、`export.files.retention-hours`（成功文件下载保留期，默认 24h，到期时间随 markSucceeded 写入 `expired_at`）与 `export.cleanup-cron`（维护服务定时清理 cron，默认 `0 0 * * * *` 每小时，测试 yml 置 `-` 静默），均走 `@Value` 默认值不落 yml；孤儿对账宽限期 1h、过期清理单轮上限 100 为 `ExportMaintenanceService` 常量；POI SXSSF 窗口为 `ExcelExportWriter.ROW_WINDOW = 100` 常量。
- SSE 心跳与 Redis：心跳间隔 `export.sse.heartbeat-ms`（默认 15000，测试 yml 置大静默）；Redis 连接默认 `localhost:6379`（主 yml 显式配置），未启动时投影写入降级（`redis_progress_write_failed` 日志）、`/actuator/health` 的 redis 组件为 DOWN，与 rabbit 组件行为一致；测试 yml 另配 `spring.data.redis.connect-timeout/timeout: 100ms` 使投影写入快速失败，避免拖慢测试。
- Outbox 分发的业务配置：`export.outbox.dispatch-delay-ms`（扫描间隔与启动首扫延迟，默认 5000，测试 yml 置大以静默调度）与 `export.outbox.confirm-timeout-ms`（单条 Confirm 等待上限，默认 5000），均走 `@Value` 默认值不落 yml；`spring.rabbitmq` 连接默认 `localhost:5672` guest/guest，RabbitMQ 未启动时应用照常运行（分发器记 `outbox_publish_deferred`，`/actuator/health` 的 rabbit 组件为 DOWN）。消费端参数在主 `application.yml` 的 `spring.rabbitmq.listener.simple`（`acknowledge-mode: manual`/`prefetch: 1`/`concurrency: 2`），Broker 未启动时监听容器后台持续重连、应用照常运行，消息在 Broker 恢复前堆积于队列；测试 yml 以 `spring.rabbitmq.listener.simple.auto-startup: false` 静默监听容器（Consumer 逻辑由测试直接方法调用验证，消费参数不经容器不生效故无需两处同步）。
- `export_jobs` 表结构与状态机（迁移 V1~V5）：核心列 `id`/`job_no`（唯一 `uk_export_jobs_job_no`）/`status`/`version`（乐观锁）/`idempotency_key`（**唯一约束 `uk_export_jobs_idempotency_key`**，并发创建的最终裁决）/`request_hash`（规范化请求 SHA-256 指纹）/`filter_count`（命中行数）/`filter_snapshot`+`selected_order_ids`（JSON 取数快照）/`selected_columns`（JSON 导出列）/`requested_file_name`/执行态列（`attempt_count`/`processed_rows`/`started_at`/`last_heartbeat_at`/`lease_expires_at`/`finished_at`/`expired_at`/`file_path`/`file_size_bytes`/`error_code`/`error_message`）。状态机：`PENDING`（创建初始态）→（claimPendingJob 条件抢占）`RUNNING` →（markSucceeded/markFailed 单向收敛）`SUCCEEDED | FAILED`；`SUCCEEDED` →（过期清理，删除即事实）`EXPIRED`；`FAILED` →（人工重试条件重置）`PENDING`。幂等规则：同 Key 且规范化后 request hash 一致 → 复用原任务（202 受理，不新增）；同 Key 不同请求 → 409 `IDEMPOTENCY_CONFLICT`；并发同 Key 依赖数据库唯一约束裁决唯一成功方，败者降级复用/冲突（见 `ExportJobIdempotencyConcurrencyTest`）。
- 事务 Outbox 另两张表（迁移 V1/V7，与 `export_jobs` 配套构成四张业务表）：`outbox_events`——`id`/`aggregate_type`（`EXPORT_JOB`）/`aggregate_id`（Job 主键）/`event_type`（`EXPORT_JOB_CREATED`）/`payload`（JSON，含 job_id/job_no/request_snapshot/columns/file_name/trace_id）/`published_at`（**NULL 即未发布**，无 `status`/`publish_attempts` 列——Outbox 没有 FAILED，分发器持续重发直到成功）/`trace_id`/`created_at`；索引 `idx_outbox_unpublished (published_at, created_at)` 供分发器捞取。`export_job_attempts`——`id`/`job_id`/`attempt_no`（唯一索引 `uk_attempt_job_no (job_id, attempt_no)` 防重复审计行）/`status`（RUNNING/SUCCEEDED/FAILED）/`error_code`+`error_message`（失败摘要，非独立 `error_summary` 列）/`started_at`/`finished_at`/`created_at`；一次任务多次重试各一行，Attempt 是执行事实/审计记录（Job 状态才是面向用户的业务事实源）。
- 订单导入的业务配置（`@Value` 默认值均不落 yml）：`import.max-rows`（行数上限，默认 100000，超限受理同步 400）、`import.file.max-size`（上传大小上限，默认 10485760=10MB）、`import.execution.batch-size`（批入库每批行数，默认 1000）、`import.execution.lease-minutes`（进度推进租约续期，默认 5）、`import.files.dir`（受控文件根 importRoot，默认 `import-files/`，已 gitignore；内部分层 `<UTC 日期>/<jobNo>/upload.xlsx|errors-attempt-N.xlsx`，未知 jobId 时按 jobNo 目录定位）、`import.files.retention-hours`（SUCCEEDED/PARTIAL 保留期，默认 24h，随 markSucceeded/markPartial 写入 `expired_at`）、`import.error-report-max-rows`（错误报告缓冲上限，默认 5000，超限截断展示、计数如实）、`import.cleanup-cron`（维护定时清理 cron，默认每小时，测试 yml 置 `-`）、`import.sse.heartbeat-ms`（心跳间隔，默认 15000）、`import.outbox.dispatch-delay-ms`/`confirm-timeout-ms`（分发扫描间隔/Confirm 超时，默认 5000，测试 yml 置大静默）。消费端参数复用主 yml 的 `spring.rabbitmq.listener.simple`，独立队列 `import.job.queue` 与导出队列互不干扰。
- `import_jobs` 表结构与状态机（迁移 V9 与导出 export_jobs 对称）：核心列 `id`/`job_no`（唯一 `uk_import_jobs_job_no`）/`status`/`version`/`file_name`/`file_path`（上传原件相对路径）/`total_rows`/`processed_rows`/`succeeded_rows`/`skipped_rows`/`error_report_path`/`error_summary`（Top N JSON `[{reason,count}]`）/`attempt_count`/执行态时间列/`error_code`/`error_message`。状态机：`PENDING →（condition claim）RUNNING →（markSucceeded/markPartial/markFailed）SUCCEEDED | PARTIAL | FAILED`；`SUCCEEDED|PARTIAL →（清理，删除即事实）EXPIRED`；`FAILED →（人工重试）PENDING`。PARTIAL 为部分成功终态（有效行照常导入、错误行跳过并入错误报告），不可重试。`import_job_attempts` 与导出对称，attempt_no 取 MAX+1 原子生成，唯一索引 `uk_attempt_import_job_no`；导入尝试共享上传原件，无 per-attempt 产物列。数据去重与冲突：文件内 HashSet 去重 + `orders.order_no` 唯一约束（V1 `uk_orders_order_no`）兜底，DB 预查 `IN` 命中即跳过，DuplicateKeyException 逐行降级，保证不产生重复订单。
- 本仓库不存在 Cursor 规则（`.cursor/rules/` 或 `.cursorrules`）或 Copilot 指令（`.github/copilot-instructions.md`）。
- 后端使用 Maven Wrapper，不要求系统预装 Maven。
- 后端 `application.yml` 暴露了 Actuator 的 `health` 与 `info` 端点，且 `management.endpoint.health.show-details: always` 使健康响应携带 db/rabbit/redis 组件细分（供前端顶栏健康徽标 Tooltip 展示；rabbit/redis 未启动时组件 DOWN 会拖累整体 status 为 DOWN，应用功能不受影响）。
- 后端生成的 Excel 文件将落入 `export-files/` 目录（已被 `.gitignore` 忽略）。
