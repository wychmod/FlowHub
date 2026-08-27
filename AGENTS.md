# AGENTS.md

本文件提供 ExportFlow 仓库的完整操作指南，供 Claude Code 在处理本仓库代码时遵循。

## 交互约束

- **必须使用中文回答所有问题**，包括解释、状态说明、错误排查和实现建议。
- **生成的代码必须包含必要的注释**：对公开类/接口、非平凡方法、复杂逻辑、状态机和边界处理添加中文或中英双语注释，说明其职责、参数、返回值和关键设计决策。
- **重大改动须同步文档**：当本次改动属于重大变更（如新增/变更接口、调整架构设计、新增依赖、改变运行方式、调整目录结构或端口等，会影响 README 或本文件描述的准确性）时，必须同步更新并提交 `README.md` 与本文件 `AGENTS.md`，保持文档与代码一致。

## 项目概述

ExportFlow 是一个企业级异步 Excel 导出中心的教学/演示项目。当前仓库为**初始骨架**，仅打通了最小可运行的前后端链路。完整架构设计（Outbox + RabbitMQ + Redis + SSE + SXSSF 流式 Excel）记录在 `docs/prd.md`、`docs/be-td.md`、`docs/fe-td.md` 中，尚未实现。

- **后端**：Java 21、Spring Boot 3.3.2、MyBatis（`mybatis-spring-boot-starter` 3.0.3）、Flyway + MySQL、Maven（已内置 Wrapper）。
- **前端**：React 18、TypeScript、Vite 6、antd 6、@tanstack/react-query 5、dayjs。
- **端口约定**：后端 `8080`，前端 `5174`。

## 常用命令

### 一键启动

在 Windows 环境下，双击仓库根目录的 `start.bat`。脚本会自动安装前端依赖（首次），并打开两个窗口分别运行后端（8080）和前端（5174）。**启动后端前需先启动本机 3306 端口的 MySQL（存在 `exportflow` 库与 `exportflow/exportflow` 账号）**，否则 Flyway/数据源初始化会失败。

### 后端（`backend/`）

```bash
# 启动 Spring Boot 服务
.\mvnw.cmd spring-boot:run

# 运行全部测试
.\mvnw.cmd test

# 运行单个测试类
.\mvnw.cmd test -Dtest=OrderControllerTest
```

后端测试不依赖外部 MySQL：`src/test/resources/application.yml` 将测试数据源指向 **H2 内存库（MySQL 兼容模式，`jdbc:h2:mem:exportflow_test;MODE=MySQL`）**，Flyway 在测试上下文对该 H2 执行迁移。

在 Unix/Linux/macOS 环境下将 `.\mvnw.cmd` 替换为 `./mvnw`。

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
- 导出任务接口（占位）：http://localhost:8080/api/v1/export-jobs

## 架构说明

### 后端结构

后端代码位于 `backend/src/main/java/com/example/exportflow/`，按**横切 Web 基础设施**与**垂直业务模块**组织：

- `common/web/`：被所有业务模块复用的 Web 层基础设施。
  - `api/ApiResponse`：统一响应 Envelope `{code, message, data, trace_id}`，字段使用 `snake_case`；`ApiV1` 为控制器版本命名空间标记注解。
  - `api/ApiResponseAdvice`：`ResponseBodyAdvice`，对返回裸对象的 `@RestController` 自动包装为统一 Envelope 并回写 `trace_id` 响应头；已包装响应、`Resource`/SSE/流式响应以及标注 `@RawResponse`（`api/RawResponse`）的接口按原样返回，避免二次包装。
  - `error/`：`ErrorCode`（HTTP 状态映射）、`BusinessException`、`GlobalExceptionHandler`，将异常统一转换为错误 Envelope。
  - `trace/`：链路追踪基础设施。`TraceIdFilter` 生成/透传 `trace_id`；`TraceIdSupport` 提供读取/生成/合法性校验工具；`MdcScope` 管理 MDC 作用域（退出时还原）；`MdcTaskDecorator` 让异步线程继承提交线程的 trace 上下文。
  - `config/` 的 `AsyncMdcConfiguration` 定义了统一异步线程池 `exportFlowTaskExecutor`（带 `MdcTaskDecorator`），异步任务应注入该 bean 以保持 trace 链路贯穿。
  - `config/`：`WebConfig`（开发期 CORS）；`ApiWebMvcConfiguration` 用 `PathMatchConfigurer` 为所有 `@RestController` 统一追加 `/api/v1` 前缀，控制器只声明相对路径（如 `/orders`），版本号集中维护。
- `order/`：订单查询模块。`OrderController` 暴露 `GET /api/v1/orders`；`OrderService` 通过 `OrderMapper` 查询，持久化目前由 `InMemoryOrderMapper` 提供固定内存 Mock 数据（`MOCK_TOTAL = 57`）。分层为 `controller/dto/service/mapper/entity/vo`。
- `export/`：导出任务模块骨架。`ExportJobController` 暴露 `GET /api/v1/export-jobs`；`ExportJobService` 目前仅返回空列表占位。

所有 JSON 接口均返回统一 Envelope。参数校验失败返回 HTTP 400，`code` 为 `"VALIDATION_ERROR"`。控制器采用构造器注入，并对查询参数使用 `@Validated` 校验。

### 前端结构

前端代码位于 `frontend/src/`，按应用层、API 层、业务 feature 分层：

- `main.tsx`：应用入口，挂载 React 根节点，配置 react-query、antd 中文语言包、dayjs 中文 locale。
- `App.tsx`：根组件，目前用本地状态切换两个页面（尚未引入路由）。
- `app/AppLayout.tsx`：全局布局壳层，负责导航与页面框架，不包含业务逻辑。
- `api/http.ts`：跨 feature 复用的 HTTP 封装。`requestJson` 解析后端 Envelope，HTTP/业务错误统一抛出 `ApiError`；默认走 Vite 代理，可通过 `VITE_API_BASE_URL` 直连后端。
- `api/exportApi.ts`：导出任务相关 API。
- `features/orders/`：订单列表页、订单 API（`api.ts`），后续将补充勾选状态管理（`selection.ts`）。
- `features/exports/`：导出任务页（占位）。

`frontend/vite.config.ts` 将 `/api` 与 `/actuator` 代理到 `http://localhost:8080`。如需前端直连后端，可在 `frontend/.env.local` 中配置 `VITE_API_BASE_URL=http://localhost:8080`。后端 `WebConfig` 已允许 `http://localhost:5174` 的跨域请求。

### 数据流

1. 前端通过 Vite 代理请求 `/api/*`。
2. `TraceIdFilter` 为每个请求生成或透传 `trace_id`，写入 MDC 与响应头。
3. 控制器直接返回数据或 `ApiResponse`，`ApiResponseAdvice` 自动包装为统一 Envelope（裸对象/已包装均被正确处理），异常由 `GlobalExceptionHandler` 统一处理。
4. 前端 `requestJson` 解包 `data`，并在错误中保留 `trace_id`。

## 已实现 vs. 计划实现

当前已实现：
- 统一响应 Envelope 与全局异常处理（含 `ApiResponseAdvice` 自动包装裸对象响应）。
- API v1 统一路径前缀（`ApiWebMvcConfiguration` 为所有 `@RestController` 追加 `/api/v1`）。
- `trace_id` 生成与链路透传（含异步线程 MDC 上下文传递）。
- MySQL 数据源与 Flyway 迁移接入（`spring.datasource.*` + `spring.flyway.enabled=true`，应用启动时自动执行迁移脚本）。
- 订单列表接口（Mock 数据 + 服务端分页）。
- 导出任务列表接口（空列表占位）。
- 前端订单列表页（react-query + antd Table 分页）。

设计文档中规划但尚未实现：
- MyBatis 真实持久化（订单仍走内存 Mock，迁移脚本待补充；接入后按 write 惯例置于 `db/migration/`）。
- RabbitMQ、Redis、Outbox 事务发件箱模式。
- Apache POI SXSSF 流式 Excel 生成。
- 导出任务创建、重试、下载、SSE 进度推送。
- 订单勾选导出、筛选导出、幂等创建、文件过期清理。

新增功能时，应保持后端各业务模块垂直自治（`order/` 或 `export/` 下自包含 `controller/dto/service/mapper/entity/vo`），横切 Web 能力只放在 `common/web/`。
- 控制器层的复杂查询/提交入参优先封装为 `xxxRequest` DTO，不要在方法签名里堆叠多个 `@RequestParam` 或零散字段；默认值、校验规则和后续扩展字段都收敛在 Request DTO 内。

## 补充说明

- 已接入 MySQL 数据源与 Flyway：`application.yml` 配置了 `spring.datasource.url/username/password` 与 `spring.flyway.enabled=true`，启动时 Flyway 自动执行 `src/main/resources/db/migration/` 下的迁移脚本（当前暂无迁移脚本，仅建 `flyway_schema_history` 表）；`ExportFlowApplication` 已移除 `DataSourceAutoConfiguration` 排除项。启动后端前需保证本机 3306 端口 MySQL 存在 `exportflow` 库与 `exportflow/exportflow` 账号。订单仍由 `InMemoryOrderMapper` 提供内存 Mock，替换为真实 MyBatis 持久化时新增迁移脚本即可。
- 本仓库不存在 Cursor 规则（`.cursor/rules/` 或 `.cursorrules`）或 Copilot 指令（`.github/copilot-instructions.md`）。
- 后端使用 Maven Wrapper，不要求系统预装 Maven。
- 后端 `application.yml` 暴露了 Actuator 的 `health` 与 `info` 端点。
- 后端生成的 Excel 文件将落入 `export-files/` 目录（已被 `.gitignore` 忽略）。
