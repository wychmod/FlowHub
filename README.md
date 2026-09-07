# ExportFlow

企业级异步导出中心（订单筛选 → 异步 Excel 导出 → 进度推送 → 下载）。

当前仓库为按 [docs/prd.md](docs/prd.md)、[docs/be-td.md](docs/be-td.md)、[docs/fe-td.md](docs/fe-td.md) 搭建的教学/演示项目：订单条件查询（后端接口 + 前端完整列表页，含筛选、排序、勾选与导出入口）与导出消息链路（创建受理 → Outbox 可靠投递 → RabbitMQ 消费端条件抢占与 Attempt 审计 → 执行体按任务快照 Keyset 分批读取订单 → 进度状态分层通知（MySQL 事实源 + Redis 投影 + SSE 广播 + HTTP 校准））已实现，SXSSF Excel 生成、文件下载与成功终态在后续迭代实现。

## 技术栈

| 端 | 技术 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.3.2 · MyBatis（`mybatis-spring-boot-starter` 3.0.3）· Flyway + MySQL · RabbitMQ（`spring-boot-starter-amqp`，发布确认闭环 + 手动 Ack 消费）· Redis（`spring-boot-starter-data-redis`，进度投影）· Maven（Wrapper，内置于 `backend/.mvn/wrapper/`） |
| 前端 | React 18.3.1 · TypeScript · Vite 6 · antd 6 · @ant-design/icons 6 · @tanstack/react-query 5 · dayjs |

端口约定：**后端 8080，前端 5174**。

## 一键启动

前置条件：JDK 21、Node.js ≥ 18、MySQL（本机 3306 端口存在 `exportflow` 数据库与 `exportflow/exportflow` 账号，见下方数据源说明；首次运行需联网下载依赖）。RabbitMQ 为**可选**前置（默认 `localhost:5672`，guest/guest）：未启动时后端仍可正常启动、创建接口可用，仅 Outbox 分发器每轮记录 `outbox_publish_deferred` 日志、消费监听容器后台持续重连，Broker 恢复后自动补发并开始消费；但 `/actuator/health` 会因 rabbit 组件显示 DOWN。Redis（默认 `localhost:6379`）同为**可选**前置：未启动时进度投影写入自动降级（仅记 `redis_progress_write_failed` 日志），任务执行与查询均不受影响，`/actuator/health` 会因 redis 组件显示 DOWN。

双击 `backend/scripts/start.bat`：

- 首次运行会自动执行 `npm install`；
- 随后打开两个窗口分别运行后端（8080）与前端（5174）；
- 浏览器访问 <http://localhost:5174>，后端日志出现 `Started ExportFlowApplication` 即就绪；
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
| 任务列表接口（空占位） | http://localhost:8080/api/v1/export-jobs |
| 任务创建接口（POST，契约见 be-td.md 4.5） | `POST /api/v1/export-jobs` + `Idempotency-Key` 头，202 受理（Outbox 落库并由分发器发布至 RabbitMQ——Confirm ACK 且无 Returned 才标记已发布；消费者以条件抢占领取执行权，执行体按任务快照 Keyset 分批读取订单并推进 `processed_rows`，Excel 生成接入前任务收敛为 FAILED 并留 Attempt 记录） |
| SSE 事件订阅（契约见 be-td.md 4.10） | `GET /api/v1/export-jobs/events`（`text/event-stream`）：`job.progress`/`job.succeeded`/`job.failed`/`heartbeat` 4 类事件，事件 id = `jobId:version`，15s 心跳 |

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
export-flow/
├── docs/                      # PRD 与前后端技术设计文档
├── backend/                   # Spring Boot 后端
│   ├── mvnw / mvnw.cmd        # Maven Wrapper（使用 .mvn/wrapper/maven-wrapper.jar）
│   ├── scripts/               # 脚本（start.bat 一键启动、seed-demo-data.sh 演示数据生成）
│   └── src/main/java/com/example/exportflow/
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
│           ├── controller/    #   POST /api/v1/export-jobs（创建，202 受理）+ GET（空列表占位）
│           ├── dto/           #   创建请求三件套（JSON 绑定 + 跨字段校验）/ 分页响应 DTO
│           ├── command/       #   CreateExportJobCommand 规范化命令 + ExportColumn 列白名单 + 模式枚举
│           ├── event/         #   应用事件 ExportJobChanged + SSE 事件 payload（snake_case 契约）
│           ├── mq/            #   RabbitMQ 拓扑（RabbitConfig）+ Outbox 分发器 + 消息契约 ExportJobMessage + 消费者 ExportJobConsumer
│           ├── service/       #   幂等判断 + 业务校验 + 同事务写 export_jobs/outbox_events + 条件抢占/失败收敛 + Keyset 执行体 + 进度状态分层（ExportProgressService）+ SSE 广播（ExportSseService）
│           ├── error/         #   ExportErrorCode 业务错误码（列白名单/选择空/筛选零行/幂等冲突等）
│           ├── mapper/        #   ExportJobMapper/OutboxEventMapper/ExportJobAttemptMapper/ExportOrderMapper（@Mapper，SQL 见 resources/mapper/*.xml）
│           ├── entity/        #   ExportJobEntity/OutboxEventEntity/ExportOrderRow/ExportSelectionSnapshot（record）
│           └── vo/            #   受理结果 ExportJobAcceptedVO / 列表行视图对象
└── frontend/                  # Vite + React 前端
    └── src/
        ├── main.tsx           # 入口（react-query、antd 中文环境 + 全局主题 token）
        ├── App.tsx            # 根组件（轻量页面切换）
        ├── app/               # 布局壳层
        │   ├── AppLayout.tsx  #   深色 Sider（品牌区 + 导航）+ 白色顶栏（动态页题）
        │   └── layoutMeta.ts  #   页面元信息（Header 页题事实源）
        ├── styles/index.css   # 全局样式（滚动条占位、表格数字等宽）
        ├── api/               # 跨 feature 复用的 API 防腐层
        │   ├── http.ts        #   requestJson（Envelope 结构校验与解包）/ ApiError / Envelope 类型
        │   ├── download.ts    #   文件下载协议工具（parseBlobError / filenameFromDisposition / saveBlob）
        │   └── exportApi.ts   #   导出任务 API（列表占位 + 创建 createExportJob + 下载 downloadExportJob）
        └── features/          # 业务 feature
            ├── orders/        #   订单列表页（筛选 + 排序 + 勾选 + 导出入口）
            └── exports/       #   导出任务页（占位）
```

与 TD 文档的差异（均为后续迭代内容）：后端 `mq/` 包已随 Outbox 分发器与消费者落地（`excel/`、`schedule/` 等在引入 POI 时创建）；订单查询已接入真实 MyBatis（动态 SQL + record 构造器自动映射 + V8 索引），`InMemoryOrderMapperImpl` 仅保留为行为基准供对齐测试；前端 `useExportEvents.ts` 等在实现 SSE 进度推送时创建（订单页筛选/勾选/导出入口已实现）。后端已接入 MySQL 数据源与 Flyway（`spring.datasource` + `spring.flyway`，迁移脚本置于 `backend/src/main/resources/db/migration/`）。

## 已实现的最小案例

- **统一响应 Envelope**：所有 JSON 接口返回 `{code, message, data, trace_id}`，字段风格为 snake_case（对齐 be-td.md 4.2/4.3 示例）；`ApiResponseAdvice` 将控制器返回的裸对象自动包装为 Envelope，标注 `@RawResponse` 或返回 `Resource`/SSE/流式的接口保持原生响应。
- **API v1 统一前缀**：`ApiWebMvcConfiguration` 为所有 `@RestController` 统一追加 `/api/v1` 前缀，控制器只声明相对路径，版本号集中维护。
- **trace_id 链路**：`TraceIdSupport` + `MdcScope` 为每个请求生成/透传 trace_id，写入 MDC（日志可打印）、响应头 `X-Trace-Id` 与响应体；`MdcTaskDecorator` 使异步线程池（`exportFlowTaskExecutor`）继承请求的 trace_id，贯穿异步链路。
- **错误路径**：参数校验失败返回 400 + `VALIDATION_ERROR` Envelope（`page_size=0`、非法枚举值、区间颠倒等可复现）；请求体 Bean Validation 失败时 Envelope 的 `data.field_errors` 输出「字段路径 → 文案」对象映射（`GlobalExceptionHandler` + `FieldErrorData`）。
- **订单条件查询**：状态/渠道/币种多值筛选、姓名模糊、订单号前缀、手机号精确、金额与时间区间、排序白名单（`sort=total_amount,desc`），全契约见 [docs/order-query-design.md](docs/order-query-design.md)；入参 record + `@BindParam` 构造器绑定，各层显式空值防御。
- **导出任务创建接口**：`POST /api/v1/export-jobs`（`Idempotency-Key` 头 + selection 勾选/筛选判别联合 + 9 列白名单，契约见 be-td.md 4.5 与 [docs/export-http-boundary-plan.md](docs/export-http-boundary-plan.md)）——DTO 跨字段校验 → Command 规范化（ID 去重排序/列白名单重排/文件名清理/筛选快照转 `OrderCriteria`）→ 业务校验（存在性、筛选命中 0 行/超上限；`snapshotByCriteria` 单查询统计命中数与范围内最大订单 ID 作为高水位）→ 幂等判断（request hash SHA-256，相同复用/不同 409）→ 同事务写 `export_jobs`(PENDING) + `outbox_events`，成功返回 202 + `{job_id, job_no, status, total_rows}`。筛选命中上限可经 `export.filter-max-rows` 配置（默认 500000）。
- **Outbox 可靠投递管道**：`OutboxDispatcher` 定时扫描未发布事件（`published_at IS NULL`，`export.outbox.dispatch-delay-ms` 默认 5000）→ 发送最小契约消息 `ExportJobMessage`（schema_version/message_id/job_id/event_version，message_id 由 outbox 事件 id 稳定派生，Header 携带 `X-Trace-Id`）至 direct 交换机 `export.job.exchange` → 等待 Publisher Confirm，**仅 ACK 且无 Returned 才回填 `published_at`**；send 异常/NACK/退回/超时一律保留事件下轮补发（至少一次投递，`outbox_publish_deferred` 日志），Job 不因发布失败改变状态。消息正文最小化，执行数据以库内 Job 为准。设计见 [docs/export-outbox-reliable-delivery-notes.md](docs/export-outbox-reliable-delivery-notes.md)。
- **RabbitMQ 消费端（条件抢占 + Attempt 审计）**：`ExportJobConsumer` 手动 Ack 消费（`listener.simple` 配 manual/prefetch=1/concurrency=2）——trace Header 合法恢复/缺失新建 → 契约不支持 `basicReject` 转 DLQ → `claimPendingJob` 以条件 UPDATE（`status='PENDING' AND attempt_count<3`）抢占执行权，与 RUNNING Attempt 插入（`MAX+1`）同一事务原子生效 → 抢占失败直接 Ack（重复投递无副作用，收敛为最多一次有效执行）→ 执行服务内部把业务异常收敛为 Job/Attempt FAILED（`FILE_GENERATION_FAILED`）后正常 Ack；claim 事务或 Channel 异常穿出不确认，保留重投机会。设计见 [docs/export-consumer-claim-attempt-notes.md](docs/export-consumer-claim-attempt-notes.md)。
- **确定性数据读取管道（第 15 章）**：创建时 `ExportOrderMapper.snapshotByCriteria` 单查询统计命中 COUNT 与范围内 MAX(id)（高水位 `max_order_id_at_create`，比全表 MAX 更贴合任务边界）；执行体 `ExportExecutionService.runJob` 加载 Job 快照 → `filter_snapshot` 反序列化重建 `OrderCriteria`（结构性杜绝漏字段重建）→ Keyset 批查 `findBatch`（`id > lastId AND id <= max_order_id_at_create` + 跨 Mapper 复用 `OrderMapper.criteriaConditions` 共享筛选片段 + `ORDER BY id ASC LIMIT`，SELECTED_IDS/FILTER 两模式共享同一条 SQL）→ 每批推进 `processed_rows` → 空批/不足一批双结束。游标推进遵循「先写入成功、后推进」（writeBatch 扩展点预留第 17 章），读取完成后任务仍收敛 FAILED（Excel 生成未接入）；`export.execution.batch-size` 默认 1000。设计见 [docs/export-keyset-read-pipeline-notes.md](docs/export-keyset-read-pipeline-notes.md)。
- **进度状态分层与实时通知（第 16 章）**：进度推进走条件 UPDATE 守卫（`status='RUNNING'` 单向 + `processed_rows <= 新值` 单调 + heartbeat/lease 续期 + version 递增，0 行 fail-fast）→ 发布 `ExportJobChanged` 应用事件 → `@TransactionalEventListener(AFTER_COMMIT)` 提交后**重读 Job** 广播 SSE（`job.progress`/`job.succeeded`/`job.failed`/`heartbeat` 4 类事件，payload snake_case，事件 id = `jobId:version` 供前端版本栅栏，15s 心跳，坏连接隔离）；Redis 投影 `export:progress:<jobId>`（Hash + TTL 48h）尽力写入、失败仅降级日志；RUNNING 进度百分比封顶 99（SUCCEEDED 才 100）；失败收敛 `markFailed` 同事务提交后广播 `job.failed`（携带 error_code/error_message），事务回滚不广播（已测）。设计见 [docs/export-progress-state-notes.md](docs/export-progress-state-notes.md)。
- **前端数据流**：`requestJson` 统一解析 Envelope（2xx 非 Envelope 抛 `Invalid API envelope`，错误统一抛 `ApiError`，携带 message/code/status/traceId/fieldErrors）→ react-query 管理请求缓存 → antd Table 服务端分页 + dayjs 时间格式化；订单 API 层已就绪完整筛选/排序参数序列化（时间用本地格式，无时区后缀）。
- **前端订单列表页**：8 项条件筛选（草稿与已提交严格分离，输入不触发请求）、订单号/金额/下单时间三列表头三态排序（以响应回显对齐）、跨页勾选（上限 1000 条）、「导出已选 / 导出筛选结果」配置弹窗与创建请求（`Idempotency-Key` 头 + 勾选/筛选两种 selection 模式，按 be-td.md 4.5 契约先行，后端创建接口未实现前失败走统一错误提示）；查询失败保留上次数据与全部用户意图。方案见 [docs/order-page-fe/](docs/order-page-fe/)。
- **前端界面主题与防抖动**：antd theme token 定制（深色 Sider 品牌区 + 白色顶栏动态页题 + Card 分区布局）；表格启用固定列宽（`tableLayout: fixed`）、固定表体高度（`scroll.y` 内部滚动）与 `scrollbar-gutter: stable` 滚动条占位，配合 react-query `placeholderData: keepPreviousData` 平滑过渡，翻页/排序/筛选时页面零抖动。
- **前端 API 防腐层**：页面只说业务语言，协议细节收敛在 `api/` 层——`requestJson` 统一请求头、Envelope 结构校验与解包、错误转 `ApiError`；文件下载按 fe-td.md 7 处理「同一 URL 成功是文件流、失败是 JSON/文本」的分流（`parseBlobError`）与文件名解析（`filenameFromDisposition`）、浏览器保存（`saveBlob`），业务入口为 `downloadExportJob`（后端下载接口就绪前调用必然失败，走统一错误提示）。

## 前端访问后端的方式

默认走 **Vite 代理**：前端同源请求 `/api/*`，由 Vite 转发到 `http://localhost:8080`（见 `frontend/vite.config.ts`），无跨域问题。

如需前端直连后端，在 `frontend/.env.local` 中配置：

```
VITE_API_BASE_URL=http://localhost:8080
```

后端 `WebConfig` 已放行 `http://localhost:5174` 的跨域请求（OPTIONS 预检已验证通过）。

## 常见问题

- **后端窗口提示 "mvnw.cmd 不是内部或外部命令"**：部分环境（如 Git Bash 派生进程）携带 `NoDefaultCurrentDirectoryInExePath=1`，禁止 cmd 从当前目录查找可执行文件。`start.bat` 已在脚本内清除该变量并用 `.\mvnw.cmd` 显式路径调用，不受影响；若在其它终端手动执行，请同样使用 `.\mvnw.cmd` 写法。
- **后端首次启动较慢**：Maven Wrapper 首次需下载依赖（本机 Maven 仓库已缓存 Spring Boot 3.3.2 时通常几十秒内完成），以窗口出现 `Started ExportFlowApplication` 为准。
- **端口冲突**：后端 8080、前端 5174 被占用时无法启动，先关闭旧的服务窗口（Vite 配置了 `strictPort`，不会静默换端口）。
- **`/actuator/health` 显示 DOWN**：RabbitMQ 未启动时 rabbit 健康组件为 DOWN（db 组件不受影响，应用功能正常，仅消息投递与消费延迟）。启动本机 RabbitMQ 后即恢复 UP，积压的 Outbox 事件由分发器自动补发、堆积的消息由消费者开始消费。

## 后续迭代指引

按 TD 文档分模块推进，每个迭代保持「后端接口 + 前端页面」可联调：

HTTP 请求边界补全计划见 [docs/export-http-boundary-plan.md](docs/export-http-boundary-plan.md)。
持久层补全计划见 [docs/persistence-domain-plan.md](docs/persistence-domain-plan.md)。
创建任务幂等与 Outbox 补全计划见 [docs/export-create-idempotency-outbox-plan.md](docs/export-create-idempotency-outbox-plan.md)。

1. 导出任务对订单查询契约的复用：创建导出任务时以 `OrderCriteria` 做 request snapshot（筛选导出），「勾选导出」经 `ids` 字段精确取数（[docs/order-query-design.md](docs/order-query-design.md) 第八节第 9 步；**创建入口已实现**，见 [docs/export-http-boundary-plan.md](docs/export-http-boundary-plan.md)）。
2. 导出任务闭环：SXSSF Excel 生成与文件发布/成功终态（Keyset 读取管道与 writeBatch 扩展点已就绪，见 [docs/export-keyset-read-pipeline-notes.md](docs/export-keyset-read-pipeline-notes.md)）、状态机执行器、详情/重试/下载接口（be-td.md 4.6-4.10、6-9；创建接口、Job/Outbox 同事务落库与 RabbitMQ 消费端条件抢占已就绪，消费链路见 [docs/export-consumer-claim-attempt-notes.md](docs/export-consumer-claim-attempt-notes.md)）。
3. 进度推送前端消费：SSE 事件消费 + `useExportEvents` + 轮询降级（be-td.md 10、fe-td.md 6；纯前端开发计划见 [docs/export-sse-design.md](docs/export-sse-design.md)，后端 SSE 端点与进度投影已就绪，端到端联调另依赖任务列表接口真实化 P-4）。
4. 前端任务中心：导出任务列表、进度展示（SSE + 轮询降级）、下载与重试入口（fe-td.md 6-8；订单页筛选、勾选与导出入口已实现）。
