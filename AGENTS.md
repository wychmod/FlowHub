# AGENTS.md

本文件提供 ExportFlow 仓库的完整操作指南，供 Claude Code 在处理本仓库代码时遵循。

## 交互约束

- **必须使用中文回答所有问题**，包括解释、状态说明、错误排查和实现建议。
- **生成的代码必须包含必要的注释**：对公开类/接口、非平凡方法、复杂逻辑、状态机和边界处理添加中文或中英双语注释，说明其职责、参数、返回值和关键设计决策。
- **重大改动须同步文档**：当本次改动属于重大变更（如新增/变更接口、调整架构设计、新增依赖、改变运行方式、调整目录结构或端口等，会影响 README 或本文件描述的准确性）时，必须同步更新并提交 `README.md` 与本文件 `AGENTS.md`，保持文档与代码一致。

## 项目概述

ExportFlow 是一个企业级异步 Excel 导出中心的教学/演示项目。当前仓库为**初始骨架**，仅打通了最小可运行的前后端链路。完整架构设计（Outbox + RabbitMQ + Redis + SSE + SXSSF 流式 Excel）记录在 `docs/prd.md`、`docs/be-td.md`、`docs/fe-td.md` 中，尚未实现。订单查询能力（条件筛选 + 排序）已按 `docs/order-query-design.md` 的定稿设计实现并接入真实 MyBatis + MySQL 持久化（导出快照复用待后续迭代）。

- **后端**：Java 21、Spring Boot 3.3.2、MyBatis（`mybatis-spring-boot-starter` 3.0.3）、Flyway + MySQL、Maven（已内置 Wrapper）。
- **前端**：React 18、TypeScript、Vite 6、antd 6、@tanstack/react-query 5、dayjs。
- **端口约定**：后端 `8080`，前端 `5174`。

## 常用命令

### 一键启动

在 Windows 环境下，双击 `backend/scripts/start.bat`。脚本会自动安装前端依赖（首次），并打开两个窗口分别运行后端（8080）和前端（5174）。**启动后端前需先启动本机 3306 端口的 MySQL（存在 `exportflow` 库与 `exportflow/exportflow` 账号）**，否则 Flyway/数据源初始化会失败。

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
- `order/`：订单查询模块。`OrderController` 暴露 `GET /api/v1/orders`（分页 + 条件筛选 + 排序，契约见 `docs/order-query-design.md`）；`OrderRequest` 为 record（snake_case 参数经 `@BindParam` 构造器绑定），`OrderService` 负责入参归一化与语义级校验后组装 `OrderQuery`（`query/` 包：`OrderQuery`/`OrderCriteria` 值对象 + `SortField` 排序白名单 + `FilterOperator` 操作符枚举）；持久化为 MyBatis 实现（`@Mapper` 接口 + `resources/mapper/OrderMapper.xml` 动态 SQL，列别名驼峰 + record 构造器自动映射）；`InMemoryOrderMapperImpl` 已退役为**行为基准**（非运行时 bean），仅用于单测与「内存 vs MyBatis」行为对齐测试（`OrderMapperAlignmentTest`）。分层为 `controller/dto/service/mapper/entity/vo/query`。
- `export/`：导出任务模块骨架。`ExportJobController` 暴露 `GET /api/v1/export-jobs`；`ExportJobService` 目前仅返回空列表占位。

所有 JSON 接口均返回统一 Envelope。参数校验失败返回 HTTP 400，`code` 为 `"VALIDATION_ERROR"`。控制器采用构造器注入，并对查询参数使用 `@Validated` 校验。

### 后端编码规范（record + @BindParam + 空值防御）

以下三条为**全仓库强制编码约束**，适用于所有新增/修改的后端代码；详细设计与示例见 `docs/order-query-design.md`（第四节、第七节）：

1. **数据承载类型一律使用 record**：实体（entity）、请求/响应 DTO、VO、跨层值对象（如 `OrderQuery`）全部用 record 实现，享受不可变性与语义明确的访问器。**例外条款**：仅当 record 在具体场景存在框架级问题且无绕行方案时才降级为普通类（如 Web 绑定/校验注解实测不生效），降级须在代码注释与 `docs/order-query-design.md` 中记录原因；不涉及 Web 绑定的类型（实体、值对象、VO）**无例外**。
2. **HTTP 入参的 snake_case → 驼峰映射统一使用 `@BindParam`**（`org.springframework.web.bind.annotation.BindParam`，Spring Framework 6.1+ 构造器绑定，本项目 Boot 3.3.2 满足）：Request DTO 写成 record，snake_case 参数在组件上标注 `@BindParam("xxx_yyy")`，Bean Validation 注解直接标注在 record 组件上；**禁止**为兼容下划线参数名编写别名 getter/setter（历史 `OrderRequest.getPage_size()` 别名已随 record 化删除，作为反例警示）。可缺省的入参字段用包装类型（`Integer`/`String` 等），默认值在紧凑构造器中兜底，区分「未传」（null）与「传了零值」。响应侧 snake_case 序列化仍用 Jackson `@JsonProperty`（`@BindParam` 只管入参）。
3. **各层显式空值防御，杜绝 NPE/500**：入参 `null` 是唯一合法的「未传」表达，禁止空串/`0` 哨兵值；字符串入参先 `trimToNull` 归一；逗号拆分的多值参数过滤空 token，拆分后空集合视为未传（防 MyBatis `<foreach>` 生成 `IN ()` 非法 SQL）；非法枚举值立即抛 `BusinessException(VALIDATION_ERROR)`，绝不静默忽略；`BigDecimal` 比较一律用 `compareTo` 而非 `equals`；值对象集合字段构造时 `List.copyOf`（拒绝 null 与 null 元素）；空查询结果返回空集合而非 `null`；可空字段以 `org.springframework.lang.@Nullable` 显式标注；单测须覆盖「全条件为 null / 空白串 / 空集合 / 区间单端」等空值用例。

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
- 订单列表接口（真实 MyBatis + MySQL 持久化、服务端分页 + 条件筛选 + 排序白名单与 sort 回显，契约见 `docs/order-query-design.md`；含内存/MyBatis 行为对齐测试）。
- 导出任务列表接口（空列表占位）。
- 前端订单列表页（react-query + antd Table 分页）。

设计文档中规划但尚未实现：
- 导出任务创建接口对 `OrderCriteria` 查询契约的 request snapshot 复用（「勾选导出」经 `ids` 字段精确取数，见 `docs/order-query-design.md` 第八节第 9 步）。
- RabbitMQ、Redis、Outbox 事务发件箱模式。
- Apache POI SXSSF 流式 Excel 生成。
- 导出任务创建、重试、下载、SSE 进度推送。
- 订单勾选导出、筛选导出、幂等创建、文件过期清理。

新增功能时，应保持后端各业务模块垂直自治（`order/` 或 `export/` 下自包含 `controller/dto/service/mapper/entity/vo`），横切 Web 能力只放在 `common/web/`。
- 控制器层的复杂查询/提交入参优先封装为 `xxxRequest` DTO，不要在方法签名里堆叠多个 `@RequestParam` 或零散字段；默认值、校验规则和后续扩展字段都收敛在 Request DTO 内。DTO 采用 record 形态，snake_case 参数名经 `@BindParam` 绑定（见上文「后端编码规范」）。

## 补充说明

- 已接入 MySQL 数据源与 Flyway：`application.yml` 配置了 `spring.datasource.url/username/password` 与 `spring.flyway.enabled=true`，启动时 Flyway 自动执行 `src/main/resources/db/migration/` 下的迁移脚本（当前已迁移至 V8，含 orders 全部导出业务列、export_jobs/outbox_events 完整表结构与订单查询索引 V8__add_order_query_indexes.sql）；`ExportFlowApplication` 已移除 `DataSourceAutoConfiguration` 排除项。启动后端前需保证本机 3306 端口 MySQL 存在 `exportflow` 库与 `exportflow/exportflow` 账号。订单查询已走真实 MyBatis（`mybatis.mapper-locations` 加载 `resources/mapper/OrderMapper.xml`），**真实库的 orders 表为空时接口将返回空列表**，需先用上文「演示数据生成脚本」灌入演示数据；测试上下文的 H2 数据由 `TestOrderDataSeeder` 预置，注意 `src/test/resources/application.yml` 会整体遮蔽主配置，mybatis 配置需两处同步维护。
- 本仓库不存在 Cursor 规则（`.cursor/rules/` 或 `.cursorrules`）或 Copilot 指令（`.github/copilot-instructions.md`）。
- 后端使用 Maven Wrapper，不要求系统预装 Maven。
- 后端 `application.yml` 暴露了 Actuator 的 `health` 与 `info` 端点。
- 后端生成的 Excel 文件将落入 `export-files/` 目录（已被 `.gitignore` 忽略）。
