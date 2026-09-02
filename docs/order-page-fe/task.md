# 订单列表页（筛选 + 排序 + 勾选 + 导出入口）Tasks

> 状态：**已批准**（2026-09-03；阶段三）。上游：已批准的 `spec.md`（F1–F19 / AC1–AC17）与 `plan.md`（架构、接口签名、技术决策以其为准）。
> 工程约束：仓库已配置前端 `PostToolUse` hook——每次改动 `frontend/` 文件自动执行 `npm test && npm run build`，
> 任一失败即标记本次改动；因此**每个任务的验证默认包含 hook 通过**，下文仅额外列出定向验证命令与期望结果。

## 文件清单

| 操作 | 文件 | 职责 |
|---|---|---|
| 新建 | `frontend/src/features/orders/constants.ts` | 枚举选项、Tag 颜色、默认排序/分页常量 |
| 新建 | `frontend/src/features/orders/selection.ts` | 勾选上限规则纯函数 |
| 新建 | `frontend/src/features/orders/selection.test.ts` | 上限规则单测 |
| 新建 | `frontend/src/features/orders/filters.ts` | 草稿/已提交条件类型 + 映射与快照纯函数 |
| 新建 | `frontend/src/features/orders/filters.test.ts` | 映射与快照单测 |
| 修改 | `frontend/src/features/orders/api.ts` | 导出 `formatLocalDateTime`，`appendTime` 复用 |
| 修改 | `frontend/src/api/exportApi.ts` | 新增 `createExportJob`、`EXPORT_COLUMN_OPTIONS` 与相关类型 |
| 新建 | `frontend/src/api/exportApi.test.ts` | 创建请求单测 |
| 新建 | `frontend/src/features/orders/components/OrderFilterForm.tsx` | 筛选草稿表单 |
| 新建 | `frontend/src/features/orders/components/ExportModal.tsx` | 导出配置弹窗 |
| 重构 | `frontend/src/features/orders/OrderListPage.tsx` | 状态分层 + 迁移规则 + 组件装配 |
| 修改 | `frontend/src/App.tsx` | `OrderListPage` 传入 `onNavigate` |
| 修改 | `frontend/src/main.tsx` | `ConfigProvider` 内补 antd `App` 包装 |
| 修改 | `AGENTS.md`（必要时 `README.md`） | 实现后同步前端结构描述 |

## T1: 常量模块

**文件：** `frontend/src/features/orders/constants.ts`
**依赖：** 无（仅引用 `api.ts` 既有枚举类型）
**步骤：**
1. 定义 `ORDER_STATUS_OPTIONS` / `SALES_CHANNEL_OPTIONS` / `CURRENCY_OPTIONS`（`{ value, label }[]`，中文 label 取 PRD 9.1/9.2：待支付/已支付/已发货/已完成/已取消；Web 商城/移动 App/线下门店/合作渠道；人民币/美元/欧元/港币）。
2. 迁移 `OrderListPage` 的 `STATUS_COLOR` 至本模块，类型收紧为 `Record<OrderStatus, string>`。
3. 定义 `DEFAULT_SORT`（`{ field: 'created_at', direction: 'desc' }`）、`DEFAULT_PAGE_SIZE = 10`、`PAGE_SIZE_OPTIONS = [10, 20, 30, 50]`。
4. `OrderListPage` 暂不动（T9 统一重构），本任务只新增文件。

**验证：** hook 通过（纯数据模块，无新单测）。

## T2: 勾选上限纯函数

**文件：** `frontend/src/features/orders/selection.ts` + `selection.test.ts`
**依赖：** 无
**步骤：**
1. `MAX_SELECTED_ORDERS = 1000`。
2. `applySelectionChange(current, next)`：`next.length > MAX` 时返回 `{ ids: [...current], blocked: true }`（整体拒绝）；否则 `{ ids: [...next], blocked: false }`。
3. 单测：上限内接受且保序；恰好 1000 接受；1001 拒绝并原样返回 `current`；`next` 为空数组接受（页内取消勾选）。

**验证：** `npx vitest run src/features/orders/selection.test.ts` 全绿。

## T3: 筛选类型与草稿映射

**文件：** `frontend/src/features/orders/filters.ts` + `filters.test.ts`
**依赖：** 无（类型引用 `api.ts` 枚举）
**步骤：**
1. 定义 `OrderFilterFormValues` / `SubmittedFilter` / `SubmittedSort`（签名见 plan.md「核心数据结构」）。
2. 实现 `formValuesToFilter(values)`：字符串 trim 后空串折叠为 `undefined`；空数组折叠为 `undefined`；金额 `null`/`undefined` 折叠、数值保留（单端合法）；`createdAtRange` 为 `null` 或含 `null` 时折叠，有效时拆为 `createdAtBegin`/`createdAtEnd`（保持 Dayjs）。
3. 单测：全空表单 → `{}`；带空白串/空数组 → 对应字段不出现在结果；仅填金额下界；完整时间区间；区间含 `null` 端。

**验证：** `npx vitest run src/features/orders/filters.test.ts` 全绿。

## T4: 时间格式事实源导出

**文件：** `frontend/src/features/orders/api.ts`
**依赖：** 无
**步骤：**
1. 新增导出 `formatLocalDateTime(value: Dayjs): string`（`'YYYY-MM-DDTHH:mm:ss'`，注释注明后端时间契约：禁止 toISOString）。
2. 私有函数 `appendTime` 改为内部调用它，行为不变。

**验证：** 既有 `api.test.ts` 全绿（序列化行为不变）；hook 通过。

## T5: 导出创建 API

**文件：** `frontend/src/api/exportApi.ts` + `exportApi.test.ts`
**依赖：** 无（`api/http.ts` 既有）
**步骤：**
1. 类型：`ExportColumnKey`（9 列联合）、`ExportFilterSnapshot`、`CreateExportJobPayload`（selection 判别联合）、`ExportJobCreated`（`job_id: number`，联调风险见 plan.md）。
2. `EXPORT_COLUMN_OPTIONS`：9 项 `{ key, label, defaultSelected }`，顺序与默认勾选按 PRD 7.3.2（默认 6 列：订单号/订单状态/销售渠道/订单金额/币种/下单时间）。
3. `createExportJob(payload, idempotencyKey)`：`requestJson('/api/v1/export-jobs', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(payload) })`。
4. 单测（stub fetch，延续 `api.test.ts` 风格）：断言 URL 与方法；`Idempotency-Key` 头与 `Content-Type`；`SELECTED_IDS` 与 `FILTER` 两种请求体形态（快照字段 snake_case、多值为数组）；成功解包 Envelope 的 `data`。

**验证：** `npx vitest run src/api/exportApi.test.ts` 全绿。

## T6: 筛选快照构建

**文件：** `frontend/src/features/orders/filters.ts`（增补）+ `filters.test.ts`（增补）
**依赖：** T3（类型）、T4（`formatLocalDateTime`）、T5（`ExportFilterSnapshot`）
**步骤：**
1. 实现 `buildFilterSnapshot(filter, sort)`：驼峰 → snake_case；多值为数组；时间经 `formatLocalDateTime` 输出字符串；未设置字段不出现在结果对象；`sort_by`/`sort_order` 恒包含。
2. 单测：空 filter → 仅含排序两字段；完整 filter → 全字段 snake_case（数组/字符串/数值各自断言）。

**验证：** `npx vitest run src/features/orders/filters.test.ts` 全绿。

## T7: 筛选表单组件

**文件：** `frontend/src/features/orders/components/OrderFilterForm.tsx`
**依赖：** T1（枚举选项）、T3（类型）
**步骤：**
1. Props：`{ form: FormInstance<OrderFilterFormValues>; onSubmit(values); onReset() }`（签名见 plan.md）。
2. `Form` + `Row/Col`（`gutter` 16）4 列网格：首行常显 订单号/订单状态/销售渠道/下单时间区间；次行 客户姓名/客户手机号/币种/金额区间，随「展开/收起」条件渲染（antd Form 字段 `preserve` 默认 true，收起不丢草稿）。
3. 控件与约束：订单号 `Input maxLength 64`（addon/说明「前缀匹配」）；姓名 `maxLength 128`；手机号校验（空放行，非空 11 位数字）；三个多选 `Select mode="multiple"`（`maxTagCount="responsive"`）；金额两个 `InputNumber`（`min=0`、`precision=2`）跨字段校验 min ≤ max；`RangePicker showTime` + presets（今天/昨天/最近 7 天/最近 30 天），校验 begin < end（同刻相等拒绝）。
4. 操作区：`查询`（primary，`htmlType="submit"`）、`重置`（触发 `onReset`，不清草稿之外的状态——状态清理在页面层）、`展开/收起`（`Button type="link"`）。

**验证：** hook 通过（类型检查 + 构建；无渲染测试）。

## T8: 导出配置弹窗组件

**文件：** `frontend/src/features/orders/components/ExportModal.tsx`
**依赖：** T5（白名单与类型）、T6（`buildFilterSnapshot`）
**步骤：**
1. Props 按 plan.md：`{ open, mode, selectedIds, filter, sort, filterTotal?, submitting, error, onCancel, onSubmit }`。
2. 内部 `Form`：导出列 `Checkbox.Group`（options 取 `EXPORT_COLUMN_OPTIONS`，顺序渲染）+ 文件名 `Input maxLength 100 showCount`（placeholder 说明留空由后端按时间生成默认名）。
3. `open` 变 true 时 `form.resetFields()` 恢复默认（6 列、文件名空）——用 `useEffect` 监听 `open`，不依赖 Modal 销毁语义。
4. 范围只读展示：`SELECTED_IDS` → `已选订单 N 条`；`FILTER` → `当前筛选结果 N 条`（N 取 `filterTotal`，无值显示 `—`）。
5. 「全选 / 恢复默认」`Button type="link"`（PRD 7.3.3）；列校验：至少 1 项（空数组视为未选，validator）。
6. 提交：`onSubmit` 组装 payload——`SELECTED_IDS` 用 `selectedIds`；`FILTER` 用 `buildFilterSnapshot(filter, sort)`。
7. `submitting` 时确认按钮 `loading`、取消按钮禁用、`maskClosable=false`、`keyboard=false`；`error` 非 null 时顶部 `Alert type="error"` 展示 `error.message` + trace_id。

**验证：** hook 通过。

## T9: 页面重构（一）：状态骨架与查询数据流

**文件：** `frontend/src/features/orders/OrderListPage.tsx`
**依赖：** T1、T3、T4、T7
**步骤：**
1. 一次性声明全部页面状态：`form`（`Form.useForm`）、`filter`（`SubmittedFilter`，初始 `{}`）、`sort`（初始 `DEFAULT_SORT`）、`page`/`pageSize`、`selectedIds`（初始 `[]`）、`exportModal`（初始 `null`）——后两者本任务只声明，T10/T11 使用。
2. `useQuery({ queryKey: ['orders', filter, sort, page, pageSize], queryFn: () => listOrders({ ...filter, sort, page, pageSize }) })`。
3. `lastDataRef` + `useEffect`（data 非 undefined 时更新）+ `displayData = data ?? lastDataRef.current`。
4. 表格列重构：新增 客户手机号 列、币种独立成列；状态/渠道/币种用 `constants` 中文标签渲染（状态仍用 Tag + `STATUS_COLOR`）；`customer_name`/`customer_phone` 为 null 时渲染 `—`；金额右对齐两位小数。
5. 排序：订单号/金额/下单时间三列 `sorter: true`；受控 `sortOrder` 由 `sort` 计算；统一 `Table.onChange` 按 `extra.action` 分派——`sort` 时映射 `sorter`（`order` 为 `ascend/descend` → 对应方向；取消/undefined → `DEFAULT_SORT`）并 `setPage(1)`；`paginate` 时仅更新 `page`/`pageSize`（改 pageSize 回第 1 页）；删除原 `pagination.onChange`。
6. 排序回显校正：`useEffect` 在 `data` 变化时以 `data.sort_by`/`sort_order` 校正 `sort`（幂等防御）。
7. `OrderFilterForm` 装配：`onSubmit` → `setFilter(formValuesToFilter(values))` + `setPage(1)`；`onReset` → `form.resetFields()` + `setFilter({})` + `setPage(1)` + `setSelectedIds([])`（pageSize 不动）。
8. 错误 `Alert`（`isError` 时）：保留现有文案风格，追加「重新加载」按钮（`refetch`）。

**验证：** hook 通过；`npm run dev` 手动冒烟——默认加载、筛选查询、重置、翻页后筛选参数保留（网络面板）、三列排序循环与回显、刷新按钮。

## T10: 页面重构（二）：勾选与已选提示

**文件：** `frontend/src/features/orders/OrderListPage.tsx`（增补）
**依赖：** T2、T9
**步骤：**
1. `rowSelection`：`type: 'checkbox'`、`selectedRowKeys: selectedIds`、`preserveSelectedRowKeys: true`、`onChange` 经 `applySelectionChange`（`blocked` 时 `message.warning('最多选择 1000 条')` 并保持原集合，否则 `setSelectedIds`）。
2. `message` 取自 `AntdApp.useApp()`（T12 之前若 `App` 包装未就位，可暂以 `App.useApp()` 直接使用——组件树届时已在 antd `App` 内；若 hook 报上下文错误则先做 T12 再回来验证）。
3. 工具栏（表格上方、筛选表单下方）：左侧勾选后显示 `已选择 N 条`（`Alert type="info"` 或 `Space`）+ `清空选择`；右侧 `刷新`（保留现有）。

**验证：** hook 通过；手动冒烟——跨页勾选计数、翻回原页勾选保留、清空选择。

## T11: 页面重构（三）：导出入口全链路

**文件：** `frontend/src/features/orders/OrderListPage.tsx`（增补）
**依赖：** T8、T10
**步骤：**
1. `useMutation({ mutationFn: (payload) => createExportJob(payload, crypto.randomUUID()) })`——幂等键在 `mutationFn` 内生成，每次点击新 Key。
2. `onSuccess(job)`：`setExportModal(null)` + `setSelectedIds([])` + `modal.success({ title: '导出任务已创建', content: job_no 与预计行数 total_rows, okText: '查看任务', onOk: () => onNavigate('exports'), cancelText: '留在本页' })`。
3. `onError`：不改动任何状态（弹窗、勾选保留），`mutation.error` 传入弹窗渲染。
4. 工具栏右侧新增 `导出已选`（`selectedIds.length === 0` 时 `disabled`）与 `导出筛选结果`（`disabled` 条件：`displayData?.total === 0`）按钮，点击 `setExportModal({ mode })`。
5. 渲染 `ExportModal`：`selectedIds`、`filter`、`sort`、`filterTotal = displayData?.total`、`submitting = mutation.isPending`、`error = mutation.error ?? null`、`onCancel = () => setExportModal(null)`、`onSubmit = (payload) => mutation.mutate(payload)`。

**验证：** hook 通过；手动冒烟（后端未就绪时走失败路径）——未勾选时导出已选禁用；弹窗范围文案与数量正确；提交后按钮 loading；失败 Alert 含错误信息；勾选在失败后保留、成功后清空（成功路径待后端就绪联调）。

## T12: 应用接线

**文件：** `frontend/src/main.tsx`、`frontend/src/App.tsx`
**依赖：** T9–T11
**步骤：**
1. `main.tsx`：`ConfigProvider` 内补 `<AntdApp>`（`import { App as AntdApp } from 'antd'`），包裹根组件。
2. `App.tsx`：`<OrderListPage onNavigate={setPage} />`。

**验证：** hook 通过；手动冒烟——页面切换正常、`message`/`modal` 正常渲染（无上下文警告）。

## T13: 全量验证与冒烟预演

**文件：** 无新增（验证任务）
**依赖：** T12
**步骤：**
1. `npm test` 全绿；`npm run build` 通过。
2. 按 spec 验收标准做手动冒烟预演（后端 + seed 数据就绪）：AC1–AC12、AC15–AC16 逐条过一遍；AC13/AC14 的成功路径记录为「待后端联调」。
3. 发现的问题就地修复；涉及设计偏差的回到对应文档阶段（变更控制）。

**验证：** 上述命令与冒烟全部通过；遗留问题记录清单。

## T14: 文档同步

**文件：** `AGENTS.md`（必要时 `README.md`）
**依赖：** T13
**步骤：**
1. `AGENTS.md` 前端结构节：更新 `features/orders/` 描述（`filters.ts`/`selection.ts`/`constants.ts`/`components/`、筛选草稿/已提交分离、导出入口）。
2. `README.md` 若含前端功能描述则同步。
3. 注明导出创建依赖后端接口未实现、契约分歧处置见 `docs/order-page-fe/plan.md` 风险节。

**验证：** 文档描述与实际代码结构一致（人工核对）。

## 执行顺序

```
阶段 A（纯逻辑与 API，可按序快速推进）
  T1 → T2 → T3 → T4 → T5 → T6
阶段 B（组件）
  T7（依赖 T1/T3）   T8（依赖 T5/T6）
阶段 C（页面装配，严格串行）
  T9（依赖 T1/T3/T4/T7）→ T10（依赖 T2/T9）→ T11（依赖 T8/T10）
阶段 D（接线与收尾）
  T12（依赖 T9–T11）→ T13 → T14
```

约束说明：T10 的 `message` 依赖 antd `App` 上下文，若在 T12 之前冒烟受阻，可临时先执行 T12 的第 1 步（仅 `main.tsx` 包装），不影响任务边界。
