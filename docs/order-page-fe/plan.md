# 订单列表页（筛选 + 排序 + 勾选 + 导出入口）Plan

> 状态：**已批准**（2026-09-03；阶段二）。上游：已批准的 `spec.md`（功能编号 F1–F19、验收 AC1–AC17 以其为准）。
> 技术栈基线：React 18 + TypeScript + antd 6.5 + @tanstack/react-query 5 + dayjs + Vitest（node 环境）。

## 架构概览

页面按「三种状态、三个归属」分层，是对 spec 核心状态原则的直接落地：

```
┌─ OrderListPage（页面编排层，唯一状态持有者）───────────────────────────┐
│                                                                        │
│  草稿        antd Form 实例（useForm，由 OrderFilterForm 渲染）          │
│  已提交语义   filter / sort / page / pageSize 四个 useState              │
│  本地 UI     selectedIds（勾选集合）、exportModal（弹窗开关与模式）        │
│                                                                        │
│  服务端数据   useQuery(['orders', filter, sort, page, pageSize])          │
│              + 最近成功数据兜底（displayData，满足 F18）                  │
│  创建变更     useMutation(createExportJob)，成功清勾选关弹窗（F16/F17）     │
└────────────────────────────────────────────────────────────────────────┘
        │ 渲染                          │ 提交/回调
        ▼                               ▼
  OrderFilterForm（草稿表单）     ExportModal（导出配置弹窗）
  Table + 工具栏 + 错误 Alert     api/exportApi.ts（创建接口）
```

要点：

- **草稿只存在于 antd Form**：页面不镜像草稿状态，`onFinish` 时一次性经纯函数转换为已提交条件。
- **已提交语义是四个独立 useState**：查询/重置/换页/排序四条迁移规则各自只碰自己该碰的字段（见「模块交互」）。
- **服务端数据只来自 useQuery**：列表、总数、实际生效排序全部取自响应；本地任何状态不伪造数据。
- **纯逻辑全部下沉可测模块**：`filters.ts` / `selection.ts` 承载状态迁移与请求构建的可测部分（N5）。

## 核心数据结构

```ts
// ========== features/orders/filters.ts ==========

/** 筛选草稿：antd Form 持有的表单值形态（时间保持 Dayjs，序列化延迟到出口）。 */
export interface OrderFilterFormValues {
  orderNo?: string;
  customerName?: string;
  customerPhone?: string;
  orderStatus?: OrderStatus[];
  salesChannel?: SalesChannel[];
  currency?: Currency[];
  totalAmountMin?: number | null;
  totalAmountMax?: number | null;
  /** RangePicker 值：[begin, end] 或 null。 */
  createdAtRange?: [Dayjs | null, Dayjs | null] | null;
}

/** 已提交筛选条件：点「查询」后生效，驱动列表请求与筛选导出范围；不含分页与排序。 */
export interface SubmittedFilter {
  orderStatus?: OrderStatus[];
  salesChannel?: SalesChannel[];
  currency?: Currency[];
  orderNo?: string;
  customerName?: string;
  customerPhone?: string;
  totalAmountMin?: number;
  totalAmountMax?: number;
  createdAtBegin?: Dayjs;
  createdAtEnd?: Dayjs;
}

/** 已提交排序：永远有值（初始与「取消排序」都是默认 created_at desc）。 */
export interface SubmittedSort {
  field: OrderSortField;
  direction: OrderSortDirection;
}

/** 草稿 → 已提交条件：字符串 trim、空串/空数组/空区间折叠为 undefined（对齐后端「空 = 未传」契约）。 */
export function formValuesToFilter(values: OrderFilterFormValues): SubmittedFilter;

/** 已提交条件 + 排序 → 筛选导出快照（字段名与查询契约一致，多值为数组，时间为本地格式字符串）。 */
export function buildFilterSnapshot(filter: SubmittedFilter, sort: SubmittedSort): ExportFilterSnapshot;

// ========== features/orders/selection.ts ==========

export const MAX_SELECTED_ORDERS = 1000;

/**
 * 勾选集合迁移：antd onChange 给出变更后的完整 key 列表，超上限时整体拒绝（保留原集合）。
 * 返回 blocked=true 时由调用方提示「最多选择 1000 条」。
 */
export function applySelectionChange(
  current: readonly number[],
  next: readonly number[],
): { ids: number[]; blocked: boolean };

// ========== features/orders/constants.ts ==========

/** 枚举选项（value 为后端枚举名，label 为中文展示，PRD 9.1/9.2）。 */
export const ORDER_STATUS_OPTIONS: readonly { value: OrderStatus; label: string }[];
export const SALES_CHANNEL_OPTIONS: readonly { value: SalesChannel; label: string }[];
export const CURRENCY_OPTIONS: readonly { value: Currency; label: string }[];
/** 状态 Tag 颜色（自现 OrderListPage 迁入）。 */
export const STATUS_COLOR: Record<OrderStatus, string>;
/** 默认排序与分页常量。 */
export const DEFAULT_SORT: SubmittedSort;        // { field: 'created_at', direction: 'desc' }
export const DEFAULT_PAGE_SIZE: 10;
export const PAGE_SIZE_OPTIONS: readonly number[]; // [10, 20, 30, 50]

// ========== api/exportApi.ts（新增部分） ==========

/** 导出列 key（后端白名单，PRD 7.3.2）。 */
export type ExportColumnKey =
  | 'order_no' | 'order_status' | 'sales_channel' | 'customer_name' | 'customer_phone'
  | 'total_amount' | 'currency' | 'shipping_province' | 'created_at';

/** 导出列白名单：顺序即默认表头顺序，Excel 表头文案与默认勾选属于契约的一部分。 */
export const EXPORT_COLUMN_OPTIONS: readonly {
  key: ExportColumnKey;
  label: string;
  defaultSelected: boolean;
}[];

/** 筛选导出快照：字段名与订单查询契约一致（order-query-design.md 第八节第 9 步口径）。 */
export interface ExportFilterSnapshot {
  order_status?: OrderStatus[];
  sales_channel?: SalesChannel[];
  currency?: Currency[];
  customer_name?: string;
  order_no?: string;
  customer_phone?: string;
  total_amount_min?: number;
  total_amount_max?: number;
  created_at_begin?: string;
  created_at_end?: string;
  sort_by?: OrderSortField;
  sort_order?: OrderSortDirection;
}

/** 创建导出任务请求体（be-td.md 4.5，两种模式的 selection 判别联合）。 */
export type CreateExportJobPayload = {
  selection:
    | { mode: 'SELECTED_IDS'; order_ids: number[] }
    | { mode: 'FILTER'; filter: ExportFilterSnapshot };
  columns: ExportColumnKey[];
  file_name?: string;
};

/** 创建成功响应（HTTP 202 的 data）。 */
export interface ExportJobCreated {
  job_id: number;
  job_no: string;
  status: ExportJobStatus;
  total_rows: number;
}

/** 创建导出任务：每次调用由调用方生成新幂等键（每次明确点击 = 新 Key，PRD 7.4.2）。 */
export function createExportJob(
  payload: CreateExportJobPayload,
  idempotencyKey: string,
): Promise<ExportJobCreated>;

// ========== features/orders/api.ts（微调） ==========

/** 本地时间格式（无时区后缀）——查询参数与导出快照共用的唯一时间格式事实源。 */
export function formatLocalDateTime(value: Dayjs): string;
```

## 模块设计

### OrderListPage（重构）

**职责：** 页面编排与全部状态持有；spec 状态迁移规则的唯一落点。
**对外接口：** `OrderListPageProps { onNavigate: (key: PageKey) => void }`（创建成功「查看任务」跳转用，App.tsx 传入 `setPage`）。
**依赖：** `filters.ts`、`selection.ts`、`constants.ts`、`components/*`、`api.ts`、`api/exportApi.ts`、`api/http.ts`。

状态与迁移（对照 spec 状态表逐条实现）：

| 迁移 | 实现 |
|---|---|
| 点查询 | `form.onFinish(values)` → `setFilter(formValuesToFilter(values))` + `setPage(1)`（sort 不动） |
| 点重置 | `form.resetFields()` + `setFilter({})` + `setPage(1)` + `setSelectedIds([])`（pageSize 不动） |
| 换页/改每页条数 | `Table.onChange(action='paginate')` → 仅 `setPage`/`setPageSize`（改 pageSize 时回第 1 页） |
| 表头排序 | `Table.onChange(action='sort')` → `setSort(sorter 映射，取消时 DEFAULT_SORT)` + `setPage(1)` |
| 勾选 | `rowSelection.onChange` → `applySelectionChange`（blocked 时 `message.warning` 并保持原集合） |
| 创建成功 | `mutation.onSuccess` → 关弹窗 + `setSelectedIds([])` + `modal.success`（含「查看任务」→ `onNavigate('exports')`） |
| 创建失败 | `mutation.onError` → 不改任何状态，`mutation.error` 传入弹窗渲染 |

**统一用 `Table.onChange` 处理分页与排序**（`extra.action` 区分 `paginate`/`sort`），不再单独传 `pagination.onChange`，避免两个回调重复触发。

**服务端数据与兜底：**

```ts
const { data, error, isError, isFetching, refetch } = useQuery({
  queryKey: ['orders', filter, sort, page, pageSize],
  queryFn: () => listOrders({ ...filter, sort, page, pageSize }),
});

// 最近成功数据兜底：覆盖 pending（平滑换页）与 error（F18 保留上次结果）两种 data 缺失场景
const lastDataRef = useRef<OrderPage | undefined>(undefined);
useEffect(() => { if (data !== undefined) lastDataRef.current = data; }, [data]);
const displayData = data ?? lastDataRef.current;
```

> 依据（TanStack Query v5 源码行为）：`placeholderData` 仅在 `status === 'pending'` 时生效，
> 新 queryKey 出错时 `data` 为 `undefined`——故不使用 `keepPreviousData`，改用本机制统一兜底。

**排序回显对齐（F8）：** `useEffect` 在 `data` 变化时以 `data.sort_by/sort_order` 校正本地 `sort`（幂等防御，正常情况下天然一致）。

**受控排序指示：** 各排序列 `sortOrder` 由 `sort` 计算（`sort.field === 列字段 ? ascend/descend : undefined`），列 `sorter: true` + 默认 `sortDirections: ['ascend','descend']`（点击循环 升→降→取消，antd 6 默认行为即 spec F8 三态）。

### OrderFilterForm（新建，`components/OrderFilterForm.tsx`）

**职责：** 渲染 8 项筛选控件与「查询/重置/展开/收起」，承载草稿（antd Form 非受控）。
**对外接口：**

```ts
interface OrderFilterFormProps {
  form: FormInstance<OrderFilterFormValues>; // 页面创建并传入（重置需要 resetFields）
  onSubmit: (values: OrderFilterFormValues) => void;
  onReset: () => void;
}
```

**依赖：** `constants.ts`（枚举选项）、`filters.ts`（类型）。

控件选型（全部 antd）：

| 字段 | 控件 | 要点 |
|---|---|---|
| 订单号 | `Input` | `maxLength` 64（后端契约）；说明文案「前缀匹配」 |
| 客户姓名 | `Input` | `maxLength` 128 |
| 客户手机号 | `Input` | 校验：空值放行，非空须 11 位数字（F5） |
| 订单状态/渠道/币种 | `Select mode="multiple"` | options 来自枚举常量（中文 label）；`maxTagCount="responsive"` |
| 金额区间 | 两个 `InputNumber` + `~` 分隔 | `min=0`、`precision=2`；跨字段校验 min ≤ max（两端都填时，F5） |
| 下单时间 | `RangePicker showTime` | `presets`（今天/昨天/最近 7 天/最近 30 天）；校验 begin < end（同刻相等也拒绝） |

布局：`Form` + `Row/Col`（`gutter` 16）4 列网格（`span` 6，时间列可放宽到 8）；
首行常显（订单号、状态、渠道、时间区间），次行（姓名、手机号、币种、金额区间）随「展开/收起」
条件渲染——antd Form 字段 `preserve` 默认 true，收起再展开草稿值不丢。
操作按钮：`查询`（primary，htmlType submit）、`重置`、`展开/收起`（`Link` 按钮）。

### ExportModal（新建，`components/ExportModal.tsx`）

**职责：** 导出范围只读展示、导出列与文件名配置、提交/取消与失败错误展示。
**对外接口：**

```ts
type ExportMode = 'SELECTED_IDS' | 'FILTER';

interface ExportModalProps {
  open: boolean;
  mode: ExportMode;
  selectedIds: readonly number[]; // SELECTED_IDS：范围数量与 payload 数据源
  filter: SubmittedFilter;        // FILTER：快照数据源
  sort: SubmittedSort;
  filterTotal?: number;           // FILTER：范围行数展示（当前列表 total）
  submitting: boolean;
  error: ApiError | null;         // 创建失败信息（弹窗内 Alert 展示，含 trace_id）
  onCancel: () => void;
  onSubmit: (payload: CreateExportJobPayload) => void;
}
```

**依赖：** `api/exportApi.ts`（白名单常量与类型）、`filters.ts`（快照构建）。

行为要点：

- 弹窗内自带 `Form`（导出列 `Checkbox.Group` + 文件名 `Input`）；`open` 变为 true 时
  `form.resetFields()` 恢复默认（默认 6 列、文件名留空），**不依赖 Modal 销毁重建语义**。
- 导出列：`EXPORT_COLUMN_OPTIONS` 顺序渲染，请求 `columns` 按白名单顺序输出已选项
  （顺序确定、可预期）；提供「全选 / 恢复默认」快捷 `Link`（PRD 7.3.3）。
- 文件名：可选，`maxLength` 100 + `showCount`；留空由后端按时间生成默认名（placeholder 说明）。
- 范围只读：`SELECTED_IDS` → `已选订单 N 条`；`FILTER` → `当前筛选结果 N 条`（N 取 `filterTotal`，无值显示 `—`）。
- 提交：列至少 1 项（validator），`onSubmit` 内组装 payload——
  `SELECTED_IDS` 用 `selectedIds`；`FILTER` 用 `buildFilterSnapshot(filter, sort)`（**取已提交条件，与草稿无关**）。
- `submitting` 时：确认按钮 `loading`、取消禁用、`maskClosable=false`（防双击与误关，F15）。
- 失败：顶部 `Alert type="error"` 展示 `error.message` + trace_id，表单内容与勾选不动（F17）。

### api/exportApi.ts（扩展）

**职责：** 导出任务领域 API（列表已有；新增创建）。
**对外接口：** `createExportJob` / `EXPORT_COLUMN_OPTIONS` / 相关类型（见核心数据结构）。
**依赖：** `api/http.ts`。

实现：`requestJson('/api/v1/export-jobs', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(payload) })`。

### features/orders/api.ts（微调）

导出 `formatLocalDateTime`，`appendTime` 内部改为复用它——查询参数与导出快照共用同一时间格式事实源（N3）。

### features/orders/selection.ts（新建）

**职责：** 勾选集合的上限规则（纯函数，可测）。跨页保留由 antd `preserveSelectedRowKeys` 承担。

### features/orders/constants.ts（新建）

**职责：** 枚举中文选项、Tag 颜色、默认排序/分页等展示常量的单一来源（F7）。

### App.tsx / main.tsx（修改）

- `App.tsx`：`<OrderListPage onNavigate={setPage} />`。
- `main.tsx`：`ConfigProvider` 内补 antd `<App>` 包装（`App as AntdApp` 避免与根组件重名），
  页面与弹窗经 `AntdApp.useApp()` 使用 `message` / `modal`（antd 6 推荐用法）。

## 模块交互（数据流）

```
用户输入 ──▶ OrderFilterForm（antd Form 草稿）
                │ 点查询 onFinish
                ▼ formValuesToFilter（trim / 空值折叠）
         SubmittedFilter ─────────────┐
         SubmittedSort ──(表头点击)───┤ queryKey
         page / pageSize ─(分页)─────┘
                ▼
         useQuery ──▶ listOrders ──▶ GET /api/v1/orders（多值逗号、时间本地格式）
                ▼
         OrderPage（react-query 缓存）──▶ displayData（最近成功兜底）
                ▼
         Table（排序指示 / 勾选 / 分页）+ 错误 Alert（isError，含重新加载）

勾选：rowSelection.onChange ──▶ applySelectionChange（1000 上限）──▶ selectedIds
                                              └─▶ 工具栏「已选择 N 条 / 清空选择 / 导出已选」

导出：工具栏按钮 ──▶ exportModal = { mode } ──▶ ExportModal
        │ 确认提交（新幂等键 crypto.randomUUID()）
        ▼
      useMutation ──▶ createExportJob ──▶ POST /api/v1/export-jobs（Idempotency-Key 头）
        ├─ onSuccess：关弹窗 + 清勾选 + modal.success（job_no / 预计行数，「查看任务」→ onNavigate）
        └─ onError：弹窗保留，mutation.error 渲染为 Alert，可再次提交（新 Key）
```

幂等键语义：前端不做网络层自动重试，每次用户明确点击（`mutate` 调用）生成新 UUID，
与 PRD 7.4.2「每次明确点击生成新 Key；网络超时自动重试才复用」一致。

## 文件组织

```
frontend/src/
├── main.tsx                                  # 修改：ConfigProvider 内补 antd App 包装
├── App.tsx                                   # 修改：OrderListPage 传入 onNavigate
├── api/
│   ├── exportApi.ts                          # 修改：新增 createExportJob / EXPORT_COLUMN_OPTIONS / 相关类型
│   └── exportApi.test.ts                     # 新建：创建请求的 URL/方法/头/请求体/解包单测
└── features/orders/
    ├── OrderListPage.tsx                     # 重构：状态分层 + 迁移规则 + 组件装配
    ├── api.ts                                # 微调：导出 formatLocalDateTime
    ├── api.test.ts                           # 既有（保持）
    ├── filters.ts                            # 新建：类型 + formValuesToFilter + buildFilterSnapshot
    ├── filters.test.ts                       # 新建：映射与快照纯函数单测
    ├── selection.ts                          # 新建：MAX_SELECTED_ORDERS + applySelectionChange
    ├── selection.test.ts                     # 新建：上限规则单测
    ├── constants.ts                          # 新建：枚举选项 / Tag 色 / 默认值
    └── components/
        ├── OrderFilterForm.tsx               # 新建：筛选草稿表单
        └── ExportModal.tsx                   # 新建：导出配置弹窗
```

表格本体（列定义、排序、勾选、分页）保留在 `OrderListPage` 内以模块级常量组织（延续现状，避免过度拆分）。

## 技术决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 草稿承载 | antd Form 非受控实例 | 免逐键受控渲染；`preserve` 保证展开/收起不丢草稿；重置用 `resetFields` |
| 已提交语义 | 页面内 4 个 useState（filter/sort/page/pageSize） | 四条迁移规则各自只改自己的字段，状态表可直接对照代码 |
| 服务端数据 | react-query useQuery，queryKey 含全部查询语义 | 缓存、加载态、错误语义内建；失败保留由兜底机制补齐 |
| 失败保留旧数据 | `data ?? lastDataRef` 兜底，不用 `keepPreviousData` | v5 源码证实 placeholderData 仅 pending 生效，新 key 出错 data 为 undefined；一个机制同时覆盖 pending 与 error |
| 分页/排序回调 | 统一 `Table.onChange`（`extra.action` 分派） | 避免与 `pagination.onChange` 重复触发 |
| 排序三态 | 列 `sorter: true` + antd 默认 `sortDirections` | antd 6 默认循环即 升→降→取消；「取消」映射回 `DEFAULT_SORT`（与后端不传排序的默认一致） |
| 跨页勾选 | `preserveSelectedRowKeys` + 受控 `selectedRowKeys` + 纯函数上限校验 | antd 原生跨页保留；1000 上限是业务规则，抽纯函数可测 |
| 超上限策略 | 整体拒绝（保留原集合）并提示 | 可预期、实现简单；部分添加会让用户误以为全部已选 |
| message/modal | antd `<App>` 包装 + `useApp()` | antd 6 推荐用法，静态方法无上下文 |
| 导出 API 归属 | `api/exportApi.ts` | fe-td.md 规则：跨 feature 领域 API 放 api/ 层（订单页与导出任务页共用） |
| 筛选快照字段 | 与查询契约同名（多值为数组） | order-query-design.md 定稿「快照复用 OrderCriteria」；be-td.md 4.5 示例的 `created_from/created_to` 为早期草案，见「风险」 |
| 导出列顺序 | 白名单固定顺序输出所选列 | 顺序确定可预期；点击顺序决定表头会造成「取消重勾移到末尾」的意外交互 |
| 幂等键 | 每次 `mutate` 生成新 UUID | 前端无自动重试场景；对齐 PRD 7.4.2 |
| 弹窗状态重置 | `open` 时 `form.resetFields()` | 不依赖 Modal 销毁重建语义（该 API 在 antd 6 有更名风险） |
| 测试策略 | 纯函数 + stub fetch（node 环境） | 延续现有基建，不引入 jsdom（spec「不做的事」） |

## spec 覆盖对照

| spec 需求 | 归属 |
|---|---|
| F1/F5 筛选输入与校验 | OrderFilterForm + filters.ts |
| F2/F3/F4 查询/重置/换页迁移 | OrderListPage 状态迁移表 |
| F6/F7 列展示与中文标签 | OrderListPage 列定义 + constants.ts |
| F8 表头排序与回显对齐 | OrderListPage（受控 sortOrder + 回显校正 effect） |
| F9 加载/空态/刷新 | OrderListPage（displayData + isFetching + refetch） |
| F10/F11 勾选与提示 | selection.ts + Table rowSelection + 工具栏 |
| F12–F17 导出入口与弹窗 | ExportModal + useMutation + onNavigate |
| F18 查询失败保留 | lastDataRef 兜底 + 错误 Alert |
| F19 创建接口未就绪兜底 | ApiError 统一错误提示（http.ts 既有能力） |
| N1–N6 | 组件全 antd / 网格布局 / formatLocalDateTime 唯一事实源 / trace_id（ApiError 既有）/ 纯函数单测 / 契约先行的错误兜底 |

## 风险与联调依赖

1. **`POST /api/v1/export-jobs` 后端未实现**：前端按契约先行，联调前创建必然失败（404），
   走 F19 错误提示路径；AC13/AC14 的真实验证依赖后端就绪，AC15（失败路径）可先行验证。
2. **契约字段名分歧**：be-td.md 4.5 示例（`created_from/created_to`、筛选单值）与
   order-query-design.md 定稿（查询契约字段、多值、含排序）不一致。本方案采用**定稿口径**
   （快照复用查询契约），后端实现导出创建接口时应以此对齐并同步修订 be-td.md 4.5。
3. **响应类型细节**：be-td.md 4.5 示例 `job_id` 为字符串、4.6 为数值，前端按 `number` 定义，联调时对齐。
4. **antd 6 API 演进**：`preserveSelectedRowKeys`/`sorter`/`App.useApp` 均已按 6.5.0 官方文档核实；
   实现中若遇 API 差异以官方文档为准回填本节。
