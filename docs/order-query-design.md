# Orders 查询能力架构设计（条件筛选 + 排序）

> 状态：**已实现（第八节第 1-8 步：查询契约 + MyBatis 真实持久化 + V8 索引 + 内存/MyBatis 行为对齐测试），导出快照复用（第 9 步）待后续迭代**。
> 本文档是订单列表「条件查询 + 排序」能力的设计依据，
> 后续接入真实 MyBatis 持久化、以及导出任务的「筛选导出」功能均以本文档的查询契约为准。
>
> 设计基线：`orders` 表结构见 Flyway 迁移 `V1__init_schema.sql` + `V6__complete_order_export_columns.sql`；
> 演示数据取值分布见 `backend/scripts/sql/seed-demo-data.sql`。
>
> 全局编码约束（贯穿本文档所有新增类型）：
> 1. **实体 / DTO / VO / 查询对象一律使用 record 实现**，仅当 record 在某个场景存在框架级问题时
>    （见第四节第 8 点的例外条款）才降级为普通类；
> 2. **HTTP 入参的 snake_case -> 驼峰映射统一用 Spring 的 `@BindParam` 注解完成**，
>    不再使用别名 getter/setter（现有 `OrderRequest.getPage_size()` 即将被此方案取代）；
> 3. **各层做显式空值防御**，契约见第七节「空值防御与健壮性设计」。

## 一、字段盘点与分类

`orders` 表当前共 **11 列**（V1 建 6 列 + V6 新增 5 列），按查询角色分为四类：

| 列名 | 类型 | 角色 | 说明 |
|---|---|---|---|
| `order_status` | VARCHAR(32) | ✅ 筛选（等值/多值） | 业务状态列，取值 PENDING/PAID/SHIPPED/COMPLETED/CANCELED |
| `sales_channel` | VARCHAR(32) | ✅ 筛选（等值/多值） | WEB/APP/STORE/PARTNER |
| `currency` | VARCHAR(8) | ✅ 筛选（等值/多值） | CNY/USD/EUR/HKD |
| `customer_name` | VARCHAR(128) | ✅ 筛选（模糊 LIKE） | 前后通配模糊匹配 |
| `order_no` | VARCHAR(64) | ✅ 筛选（精确/前缀） | 唯一键，适合「精确输入」或 `EF2026-` 前缀匹配 |
| `customer_phone` | VARCHAR(32) | ✅ 筛选（精确等值） | 标准手机号精确匹配，走 B+ 树等值索引 |
| `total_amount` | DECIMAL(18,2) | ✅ 筛选（范围）+ 排序 | `[min, max]` 区间，可只传一端 |
| `created_at` | TIMESTAMP | ✅ 筛选（范围）+ 排序 | `[begin, end)` 左闭右开区间 |
| `status` | VARCHAR(32) | ❌ 不开放筛选 | V6 遗留兼容列，与 `order_status` 双写同值（V6 回填 + seed 脚本双写）；筛选协议统一走 `order_status`，避免语义分裂 |
| `shipping_province` | VARCHAR(64) | ❌ 不开放筛选（本期） | 收件省份。16 省近似均匀分布，单省命中比例约 6.25%，低于索引选择性阈值（10%~20%），且页面暂无按省份筛选的需求；若后续「省份导出维度」成为高频场景再评估纳入协议 |
| `id` | BIGINT | ❌ 不开放给页面筛选 | 内部一致性边界 + 游标分页键；但作为 `OrderCriteria.ids` 集合字段保留在协议中供导出模块「勾选导出」复用（见第四节第 1 点） |

## 二、筛选协议设计

**每个可筛选字段固定一种操作符**（不做自由组合，降低协议复杂度）：

| 参数名 | 操作符 | 传参形式 | 校验规则 |
|---|---|---|---|
| `order_status` | IN 多值 | `order_status=PAID,SHIPPED`（逗号分隔） | 枚举白名单，非法值 -> 400 `VALIDATION_ERROR` |
| `sales_channel` | IN 多值 | 同上 | 同上 |
| `currency` | IN 多值 | 同上 | 同上 |
| `customer_name` | LIKE | 单值字符串 | 最长 128，去首尾空格；为空则忽略 |
| `order_no` | PREFIX（前缀精确） | 单值字符串 | 最长 64 |
| `customer_phone` | EQ 精确等值 | 单值字符串 | 最长 32；校验为 11 位数字（演示数据为标准手机号），非法字符 -> 400 |
| `total_amount_min` / `total_amount_max` | RANGE | 数值字符串 | min ≤ max（两端都传时才校验），否则 400 |
| `created_at_begin` / `created_at_end` | RANGE（左闭右开） | 本地时间字符串 | begin < end（两端都传时才校验），否则 400 |

**时间参数格式契约**（前端对接必须遵守）：

- 格式为 `yyyy-MM-dd'T'HH:mm:ss`，容许带 `.SSS` 毫秒变体；**不接受时区偏移 / `Z` 后缀**。
- 时区换算由前端负责：dayjs 必须用 `format('YYYY-MM-DDTHH:mm:ss')` 本地格式，
  **禁止 `toISOString()`**（其输出 `2026-01-01T00:00:00.000Z` 带 UTC 后缀，后端解析直接 400）。
- 区间语义为**左闭右开 `[begin, end)`**：`created_at_end` 是排他上界。
  「查 6 月整月」应传 `created_at_end=2026-07-01T00:00:00`，而非 `2026-06-30T23:59:59`——
  后者会漏掉 `23:59:59.500` 的行，且与毫秒精度数据天然兼容。

设计要点：

1. **入参全部收敛进 `OrderRequest`**（record 形态见第四节），分页 + 筛选 + 排序三组字段共置一个 DTO；
   snake_case 参数名经 `@BindParam` 绑定到 record 组件，不再依赖别名 setter。
2. **筛选字段在 DTO 中一律以 `String` 原样接收**，数值/时间/枚举的解析统一放在 Service 归一化阶段：
   解析格式完全可控（上表契约即唯一事实源）、空值语义统一（`null` = 未传，见第七节）、
   解析失败统一转 400 `VALIDATION_ERROR`，避免 Spring 隐式类型转换抛出的 `BindException`
   与业务校验错误语义混淆。
3. **这份筛选 DTO 是导出模块的「查询契约」**：将来「勾选导出/筛选导出」创建导出任务时，
   request snapshot 序列化的是 **`OrderCriteria`（仅筛选 + 排序，见第四节第 1 点）**，
   保证「列表所见 = 导出所得」。**分页字段（page/page_size）不进快照**——
   导出是全量流式拉取，分页语义对快照重放无意义甚至有害（重放会退化成只导一页）。
4. 所有筛选条件为**可选 AND 语义**：不传即不过滤，传了多个条件取交集。

## 三、排序协议设计

**白名单枚举，从高到底（DESC）为默认方向**：

| 排序字段 | 支持方向 | 默认 | 理由 |
|---|---|---|---|
| `created_at` | ASC / DESC | DESC（最新在前） | 下单时间，业务主排序键，索引友好 |
| `total_amount` | ASC / DESC | DESC（金额从高到低） | 金额维度排序，索引友好 |
| `order_no` | ASC / DESC | ASC | 字典序，唯一键，可作为稳定排序 |
| `id` | ASC / DESC | ASC | 内部兜底键 |

设计要点：

1. **排序参数**：`sort_by` 指定字段 + `sort_order` 指定方向两个独立参数，
   如 `sort_by=total_amount&sort_order=desc` / `sort_by=created_at&sort_order=asc`。
   两者均缺省、为空串或纯空白时取默认值 `sort_by=created_at` + `sort_order=desc`；
   只传 `sort_by` 时方向用该字段的默认方向（见上表）兜底；
   `sort_order` 不允许脱离 `sort_by` 单独出现（缺字段的方向没有语义，直接 400，绝不静默忽略）。
   两个字段均大小写不敏感、容忍首尾空白。
2. **防注入是硬约束**：`ORDER BY` 无法参数化，必须经 `SortField` 枚举 -> 列名映射，
   任何不在白名单里的值直接 400，绝不字符串拼接进 SQL。
   拦截分两层：DTO 层 `@Pattern` 引用枚举内正则常量
   （`SortField.NAMES_PATTERN` / `SortDirection.NAMES_PATTERN`，与枚举常量同处维护）
   在参数绑定期即拒绝非白名单取值（纵深防御）；语义级白名单校验仍在 Service 归一化阶段
   （`SortField.fromName` / `SortDirection.fromName`）。两层正则均容忍首尾空白与纯空白，
   「空白 = 未传」的归一化语义统一由 Service 的 trimToNull 承担，不因前置校验收紧。
3. **稳定排序兜底**：所有排序查询的 `ORDER BY` 末尾固定追加 `id` 作为 tie-breaker，
   **方向与主排序字段一致**（即 `ORDER BY total_amount DESC, id DESC` / `ORDER BY total_amount ASC, id ASC`），
   保证分页翻页时同值行不跳动（金额重复率高的数据必须这样做）。
   选「与主排序一致」而非「固定 DESC」有两个理由：
   - 结果确定性：两种选择产出的顺序不同，必须定死一种才能做「内存实现 vs MyBatis 实现」的行为对齐测试；
   - 索引友好：InnoDB 二级索引隐含主键作行定位，`(created_at)` 索引实际等价 `(created_at, id)`，
     `ORDER BY created_at DESC, id DESC` 可走索引反向扫描免 filesort；固定 DESC 则 ASC 场景必 filesort。
4. **不开放排序**的字段：`customer_name`（中文姓名区分度低、filesort 代价大）、`customer_phone`、
   各枚举列（排序无业务意义）、`status`（遗留列）、`shipping_province`（省份排序无业务意义）。

## 四、后端分层架构

```
OrderController (@Valid @ModelAttribute OrderRequest)
        │  record 构造器绑定：@BindParam 完成 snake_case -> record 组件的参数名映射
        │  Bean Validation（格式级校验：长度、数值范围等，注解直接标注在 record 组件上）
        ▼
OrderService
        │  ① Criteria 归一化：trimToNull、逗号拆分（过滤空 token）、枚举解析、数值/时间解析
        │  ② 语义级校验：min≤max、begin<end、非法枚举/格式 -> BusinessException(VALIDATION_ERROR)
        │  ③ SortField 枚举解析（白名单）-> (列名, 方向)
        ▼
OrderQuery（不可变 record = OrderCriteria + 分页参数，service->mapper 的唯一契约）
        ▼
OrderMapper
   ├─ MyBatis 实现（当前运行时，@Mapper + mapper/OrderMapper.xml）：<where> 动态 SQL + #{} 参数化 + record 构造器自动映射
   └─ InMemoryOrderMapperImpl：Java Stream 等价实现（行为基准，非运行时 bean，供单测与行为对齐）
```

关键决策：

1. **Service ↔ Mapper 之间定义 `OrderQuery` 值对象**（record），而不是把 DTO 直接传给 Mapper。
   HTTP 层参数形态（逗号分隔字符串）与持久层查询语义（`List<Status>` + 范围区间）解耦。
   `OrderQuery` 内部再拆一层：**`OrderCriteria` record（筛选条件 + 排序，不含分页）**
   与分页参数并列——导出任务快照序列化的就是 `OrderCriteria`，
   两个入口（页面查询 / 导出重放）共用同一套查询语义。
   `OrderCriteria` 含 `ids`（`List<Long>`）字段：页面查询恒为空集合，
   导出模块「勾选导出」通过 V3 的 `selected_order_ids` 快照反序列化后填充该字段精确取数
   （`id` 因此不出现在 HTTP 筛选协议中，但保留在查询对象协议中）。
2. **record + `@BindParam` 是入参绑定的唯一方案**：
   - `@BindParam` 是 Spring Framework 6.1+（`org.springframework.web.bind.annotation.BindParam`）
     为 `@ModelAttribute` **构造器绑定**提供的参数名注解，可标注在构造器参数或对应字段上；
     项目 Spring Boot 3.3.2 对应 Spring 6.1.x，版本满足。
   - `OrderRequest` 改写为 record，每个 snake_case 参数的组件上标注 `@BindParam("order_status")` 等；
     与组件名相同的参数（如 `page`）无需注解。
   - 现有 `OrderRequest` 的 `getPage_size()/setPage_size()` 别名随 record 化**整体删除**。
   - 形态示例（仅示意，非最终代码）：

   ```java
   /**
    * 订单查询请求（record + 构造器绑定）。
    * 筛选字段一律以 String 原样接收，解析统一收敛在 Service 归一化阶段（见第二节要点 2）。
    */
   public record OrderRequest(
           Integer page,                                          // 缺省由紧凑构造器兜底为 1
           @BindParam("page_size") @Min(1) @Max(100) Integer pageSize,
           @BindParam("order_status") @Size(max = 64) String orderStatus,
           @BindParam("sales_channel") @Size(max = 64) String salesChannel,
           @BindParam("currency") @Size(max = 32) String currency,
           @BindParam("customer_name") @Size(max = 128) String customerName,
           @BindParam("order_no") @Size(max = 64) String orderNo,
           @BindParam("customer_phone") @Size(max = 32) String customerPhone,
           @BindParam("total_amount_min") @Size(max = 20) String totalAmountMin,
           @BindParam("total_amount_max") @Size(max = 20) String totalAmountMax,
           @BindParam("created_at_begin") @Size(max = 32) String createdAtBegin,
           @BindParam("created_at_end") @Size(max = 32) String createdAtEnd,
           @BindParam("sort_by") @Size(max = 64)
           @Pattern(regexp = SortField.NAMES_PATTERN) String sortBy,
           @BindParam("sort_order") @Size(max = 16)
           @Pattern(regexp = SortDirection.NAMES_PATTERN) String sortOrder) {

       /** 紧凑构造器：未传的分页参数兜底默认值（null-safe，避免原始类型 0 哨兵歧义）。 */
       public OrderRequest {
           if (page == null) page = 1;
           if (pageSize == null) pageSize = 20;
       }
   }
   ```

   - 分页字段用包装类型 `Integer`：缺失参数在构造器绑定时注入 `null` 而非 `0`，
     「未传」与「传了 0」在进入校验前即可区分，紧凑构造器兜底默认值。
3. **InMemoryOrderMapperImpl 必须与未来的 MyBatis 实现语义完全对齐**
   （同样的过滤、排序、tie-breaker、空值跳过规则），切换持久化时业务行为零变化；
   内存 Mock 数据的取值口径（渠道/币种权重、手机号、省份）同步与 `seed-demo-data.sql` 对齐。
4. 分页维持 offset 分页（page/page_size）。
5. **游标分页升级路径**：演示数据量在 100w 内 offset 可接受；数据量继续增长时切换为 keyset
   （游标）分页，**游标键 = 当前排序键 + `id`**（`created_at` 排序时为 `(created_at, id)`，
   `total_amount` 排序时为 `(total_amount, id)`）——不同排序字段的游标结构不同，
   只有默认 `created_at` 场景因 seed 数据按时间排序写入、`id` 与时间单调一致，
   可退化为 `id` 单键。
6. 操作符类型在 `OrderQuery` 中以枚举标记（`EQ` / `IN` / `LIKE` / `PREFIX` / `RANGE`），
   Mapper 实现按操作符选择 SQL 片段，不解析原始字符串。
7. **MyBatis 对 record 的两项支持是持久层 record 化的基础**（接入时配置）：

   ```yaml
   mybatis:
     configuration:
       map-underscore-to-camel-case: true            # 下划线列名 -> 驼峰属性
       arg-name-based-constructor-auto-mapping: true # 按构造器参数名自动映射（record 必需）
   ```

   - 出参：`argNameBasedConstructorAutoMapping`（MyBatis 3.5.10+）按参数名映射列到 record 构造器，
     配合 `mapUnderscoreToCamelCase` 完成 `order_status -> orderStatus` 转换；
     需要 Maven 编译参数 `-parameters` 保留参数名。
   - 入参：MyBatis 的 `Reflector` 对 record 有专门处理（`addRecordGetMethods`），
     record 访问器被识别为可读属性，`OrderQuery` record 可直接作为 mapper 参数对象
     （XML 中 `#{query.orderStatus}` 直接可用）。项目 mybatis-spring-boot-starter 3.0.3
     对应 MyBatis 3.5.13，满足版本要求。
8. **record 例外条款**：仅当 record 在具体场景出现框架级问题才降级为普通类，且必须降级有据：
   - `@ModelAttribute` 构造器绑定或组件上的 Bean Validation 注解在当前 Spring 版本实测不生效、
     且无绕行方案时，`OrderRequest` 可降级为「带 `@BindParam` 无效的普通类 + 别名 setter」
     （即回退到现状 `page_size` 别名方案），并在本文档记录原因；
   - 实体（`Order`）、值对象（`OrderQuery`/`OrderCriteria`）、VO（`OrderItemVO`/`OrderPageResp`）
     不涉及 Web 绑定，不存在已知问题，**一律 record，无例外**。

## 五、索引规划（已随 V8 迁移脚本 `V8__add_order_query_indexes.sql` 落地，接入真实 MyBatis 后直接生效）

**索引取舍原则**：

1. 按「等值列在前、范围/排序列在后」的最左前缀原则设计联合索引。
2. **单列索引必须有足够选择性**：典型查询条件需能筛掉大部分行
   （经验值：命中比例 < 10%~20%），否则优化器会直接放弃索引走全表扫描，
   索引沦为摆设，还白付写入维护成本。
3. **低基数列（渠道、币种等枚举列）不单独建索引**：`currency` 的 CNY 占约 90%、
   `sales_channel` 的 WEB 占约 40%，单列过滤区分度远不达标。它们的正确用法是作为
   **联合索引的等值前缀**参与组合过滤（组合后选择性达标），或作为其他索引筛完后的回表过滤条件。
4. 前后通配的模糊 LIKE（如 `customer_name`）无法使用普通 B+ 树索引，不为其建索引。

**索引清单**：

| 索引 | 支撑的查询 |
|---|---|
| `idx_orders_status_created (order_status, created_at)` | 状态筛选 + 时间排序/范围（最高频组合） |
| `idx_orders_amount (total_amount)` | 金额范围 + 金额排序 |
| `idx_orders_created_at (created_at)` | 默认时间倒序翻页 |
| `idx_orders_phone (customer_phone)` | 手机号**精确等值**查询（11 位手机号接近唯一，选择性极高，B+ 树等值命中） |
| `uk_orders_order_no`（V1 已存在的唯一约束，无需新建） | `order_no` 前缀 LIKE `EF2026-%` 与精确等值均可走该 B+ 树唯一索引 |
| `sales_channel` / `currency` 不单独建索引 | 低基数枚举列，单列选择性不达标；查询时作为其他索引筛完后的过滤条件即可。若将来「渠道 + 状态」组合查询成为高频场景，再评估加 `(sales_channel, order_status, created_at)` 联合索引 |
| `customer_name` 不建索引 | 模糊 LIKE 走不了普通索引（此行仅指 `customer_name`；`customer_phone` 是精确等值，走 `idx_orders_phone`，与 LIKE 无关），数据量大再考虑前缀索引或全文索引 |
| `shipping_province` 不建索引 | 16 省均匀分布，单省约 6.25%，低于选择性阈值；且本期不开放省份筛选 |

## 六、对外接口形态（最终效果）

```
GET /api/v1/orders?order_status=PAID,SHIPPED&sales_channel=WEB
    &currency=CNY&customer_name=张&order_no=EF2026-&customer_phone=13800123456
    &total_amount_min=100&total_amount_max=5000
    &created_at_begin=2026-01-01T00:00:00&created_at_end=2026-07-01T00:00:00
    &sort_by=total_amount&sort_order=desc&page=1&page_size=20
```

（`customer_name=张` 等非 ASCII 参数实际传输时需 URL 编码，此处为可读性直书。）

响应仍是统一 Envelope（由 `ApiResponseAdvice` 自动包装）包裹 `OrderPageResp`，
其中 `OrderPageResp` **新增 `sort_by`/`sort_order` 字段回显实际生效的排序**（默认值展开后的结果，
如未传排序参数时回显 `"created_at"`/`"desc"`），作为确定契约而非可选项——
前端据此对齐当前排序状态，实现顺序（第八节）含对应前端改动。

## 七、空值防御与健壮性设计

目标：**任何入参组合（含全空、缺字段、空白串、空集合）都不产生 NPE / 500，
只产生正确的结果或 400 `VALIDATION_ERROR`**。各层契约如下：

1. **DTO 层（OrderRequest record）**：
   - 筛选字段一律 `String` + 包装类型分页字段，`null` 是唯一合法的「未传」表达；
     **禁止用空串 / `0` 作哨兵值**。
   - 紧凑构造器为分页参数兜底默认值（见第四节示例）；record 组件的不可变性天然杜绝
     「绑定后中途被置 null」的时序问题。
2. **归一化层（OrderService）**：
   - 所有字符串参数先经 `trimToNull` 处理：空白串与 `null` 等价，统一在入口收敛；
   - IN 多值参数逗号拆分后**过滤空 token**（`"PAID,,SHIPPED"` 等价 `"PAID,SHIPPED"`），
     拆分结果为空集合则**视为未传**——这同时防住了 MyBatis `<foreach>` 对空集合生成
     `IN ()` 非法 SQL 的问题；
   - 枚举解析逐个比对白名单，遇到未识别值立即抛 `BusinessException(VALIDATION_ERROR)`，
     绝不静默忽略（静默忽略会让「拼错的枚举」表现成「查出了全部数据」）；
   - 数值 / 时间解析用 try-catch 包裹，`NumberFormatException` / `DateTimeParseException`
     统一转 `BusinessException(VALIDATION_ERROR)`；
   - 区间校验只在两端都非 null 时进行（单端区间合法），`min ≤ max`、`begin < end`。
3. **查询对象层（OrderQuery / OrderCriteria record）**：
   - 构造时集合字段一律 `List.copyOf(...)`（空集合表示「无条件」，同时拒绝 null 与 null 元素）；
   - 所有条件访问器返回 `null` / 空集合即「无此条件」，Mapper 层据此跳过，
     不存在第三种「未初始化」状态；
   - 可空组件以 `org.springframework.lang.@Nullable` 显式标注，让调用方与 IDE 都可见契约。
4. **Mapper 层**：
   - InMemory 实现：Stream 过滤链对每个条件先判空再过滤（`Objects.isNull` 跳过）；
     字符串比较一律 `Objects.equals`（杜绝 `a.equals(b)` 的反模式）；
     **金额比较用 `BigDecimal.compareTo` 而非 `equals`**（`19.90` 与 `19.9` 用 equals 不相等，
     是 DECIMAL 场景经典陷阱）；
   - MyBatis 实现：动态 SQL 每个条件用 `<if test="query.criteria.xxx != null">` 守卫，
     `<foreach>` 前加 `size() > 0` 判断；排序片段只来自 `SortField` 枚举映射列名。
5. **排序层**：`sort_by`/`sort_order` 为 null / 空白 -> 默认 `created_at` + `desc`；
   只传 `sort_by` -> 用字段默认方向兜底；`sort_order` 脱离 `sort_by` 单独出现 -> 400；
   解析失败 -> 400（见第三节）。
6. **响应层**：`total == 0` 时 `items` 返回空列表而非 `null`（Jackson 序列化 `[]`，
   前端 `[].map` 不炸）；`totalPages` 计算前断言 `pageSize > 0`
   （默认值兜底 + `@Min(1)` 校验双保险，防御除零）。
7. **实体层**：V6 新增列（`order_status`/`sales_channel`/`customer_phone`/`currency`/
   `shipping_province`）在库中**可空**，`Order` record 对应组件允许 `null`，
   VO 映射与序列化保留 `null`（前端契约 `customer_name: string | null` 已是可空形态），
   禁止「实体 null -> VO 拿默认值」的隐式转换。
8. **静态与测试保障**：
   - record 组件、构造器入参等关键边界用 `Objects.requireNonNull` 快速失败；
   - 单测必须覆盖的空值用例：全条件为 null、全条件为空白串、IN 参数为空集合
     （`order_status=`）、区间只传单端、`sort_by`/`sort_order` 为空白、`page_size` 缺省走默认值。

## 八、实现顺序建议

1. **扩展数据承载类型**：`Order` record 与 `OrderItemVO` 补 `customerPhone` 组件
   （对齐 V6 列）；`InMemoryOrderMapperImpl` 的 Mock 数据补齐手机号等字段，
   取值口径与 `seed-demo-data.sql` 对齐。
2. **`OrderRequest` 重构为 record + `@BindParam`**：按第二节协议补全部筛选字段与 `sort_by`/`sort_order`
   参数，Bean Validation 注解标注在 record 组件上，紧凑构造器兜底分页默认值，
   删除 `page_size` 别名 getter/setter；同步改造 `OrderController` 与既有单测。
3. **新建 `OrderQuery` / `OrderCriteria` record** 与操作符 / `SortField` 排序枚举
   （含白名单解析与 `ids` 集合字段）。
4. **改造 `OrderMapper` 接口签名**：`countTotal()` -> `countByCriteria(OrderCriteria)`，
   `selectPage(offset, limit)` -> `selectPage(OrderQuery)`（排序语义内聚到查询对象，
   不再由实现方各自约定）。
5. **`OrderService` 实现归一化与语义校验**：按第七节第 2 条完成全部空值防御逻辑。
6. **`InMemoryOrderMapperImpl` 按新契约实现过滤/排序**（含 tie-breaker、`BigDecimal.compareTo`），
   补单测（含第七节第 8 条的全部空值用例）。
7. **响应与前端同步**：`OrderPageResp` 增加 `sort_by`/`sort_order` 回显字段；
   前端 `OrderPage` / `OrderQuery` 类型与 `api.ts` 参数序列化同步扩展
   （时间参数用 dayjs 本地格式，见第二节时间契约），补前端单测。
8. **接入真实 MyBatis（已完成）**：V8 索引迁移已由 Flyway 落地；`@Mapper` + `resources/mapper/OrderMapper.xml`
   动态 SQL（`<where>` 守卫 + `<foreach>` + SortField 白名单 ORDER BY，列别名驼峰配合构造器自动映射）；
   `OrderMapperAlignmentTest` 以同数据逐用例比对内存实现与 MyBatis 实现的 count/selectPage。
9. **导出模块实现时**：导出任务创建接口复用 `OrderCriteria` 做 request snapshot
   （不含分页字段），「勾选导出」经 `ids` 字段精确取数。
