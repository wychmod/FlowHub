# 订单导入 vs 导出：技术差异与 SAX 读取方案解析

## 1. 文档定位

本文对照 `code`，说明订单导入与导出**「骨架相同、血肉不同」**的具体差异，并深入解析导入读取端唯一的不同技术——**POI XSSF SAX 事件模型流式读**，及其在 `-Xmx512m` 下导入 10 万行不 OOM 的内存原理。

- 前置：`docs/order-import-design.md`（订单导入功能详细设计）、`docs/export-sxssf-writer-notes.md`（SXSSF 写端对照）。
- 约定：名词「骨架」= 状态机流转、Outbox 发/接 MQ、进度推进、SSE 广播，**本文不讲**（与导入逐字复用）；「血肉」= 本文主题。

## 2. 差异总览

| 维度 | 导出（`export/`） | 导入（`orderimport/`） |
|---|---|---|
| 读写方向 | SXSSF **写**流（`ExcelExportWriter`） | POI SAX 事件模型**读**（`ExcelImportReader`） |
| 数据来源 | 条件筛选取数（Keyset 批查 `orders`） | 用户上传 xlsx 逐行解析 |
| 校验层级 | 无逐行校验（取数即真实数据） | **三层漏斗**：文件级→结构级（同步）→行级（异步） |
| 终态 | `SUCCEEDED / FAILED / EXPIRED` | `SUCCEEDED / PARTIAL / FAILED / EXPIRED`（多 **PARTIAL**） |
| 统计口径 | `processed_rows` 单计数 | `succeeded_rows` + `skipped_rows` **双计数** |
| 幂等 | 请求层幂等键 `Idempotency-Key` + request hash | **无幂等键**，幂等下沉到数据层唯一约束 |
| 表头 | 单一事实源（`ExportColumn`） | **委托**该事实源（`ImportColumn`→`ExportColumn`） |
| 可下载产物 | 导出文件 xlsx | 导出文件 + **错误报告** xlsx（PARTIAL 独有） |
| 入参校验 | DTO Bean Validation | 文件/结构（同步 400）+ 行级（异步） |

## 3. 核心技术换位：SAX 事件模型读

### 3.1 代码分层（`ExcelImportReader`）

```
ImportExecutionService（调用方）
   │ 传 lambda（每行回调）
   ▼
readRows(file, handler)        ← 公共入口①：只转发，不干活
   ▼
parse(file, sink)              ← 私有核心②：驱动 POI SAX 事件模型
   ▼
CollectingHandler + RowSink    ← 胶水层③：把 POI 零散"单元格回调"拼成"整行数组"
```

- **① `readRows`（L86-99)**：倒转控制入口。不返回数据，而是把调用方 lambda 转发成逐行回调 `onRow(excelRowNo, cells)`；由调用方攒批校验/入库，读端**内存恒定**。
- **② `parse`（L102-129）**：`OPCPackage.open` 打开 zip → `XSSFReader` + `ReadOnlySharedStringsTable` → `XSSFSheetXMLHandler` 注册进 SAX `XMLReader`，`parser.parse` 流式消化第一个 sheet 的 XML；只识别第一个 Sheet，`getSheetName` 依赖 `next()` 推进后取（时序坑）。
- **③ `CollectingHandler`（L146-211）**：POI `SheetContentsHandler` 适配。`cell(...)` 把 `cellReference`（如 `"C4"`）推导为列索引写入 `rowBuf`；`endRow` 判定「第 0 行表头 → `sink.head`、后续非空行 → `sink.row`」。空单元格不回调（保持 null），列位靠 reference 推导（空列不错位）。

### 3.2 为什么不 OOM：内存账本

先看朴素读法为何会爆——`WorkbookFactory.create(input)` 会构造完整 **XSSFWorkbook 对象图**：10 万行 × 9 列 = **90 万个 Cell 对象** + 样式/共享字符串可达引用，在 `-Xmx512m` 下几乎必 OOM。

本实现把「常驻内存在堆里的东西」砍到与**行数无关**：

| 内存项 | 本实现 | 规模 |
|---|---|---|
| 单元格值 | `DataFormatter` 即取即弃，不建 Cell 对象 | 常数（仅当前一格的 String） |
| 单行缓冲 | `rowBuf = new String[9]`，`endRow` 后即弃 | 常数（一行） |
| 调用方攒批 | 攒满 `batchSize=1000` 即 `flushBatch` 落库 | **≤ 1000 行** |
| 共享字符串表 | `ReadOnlySharedStringsTable` **压缩路径单次只读** | 与「不同字符串数」相关，与 9×行数无关 |
| 整个工作簿 | 从不实例化 Workbook | 0 |

关键点逐一：

1. **SAX 事件模型天然流式**：`parser.parse(sheetData)` 是从 `InputStream` 逐事件喂给 handler 的，处理完一个单元格即释放，不保留整表。
2. **不实例化 Workbook/XSSFWorkbook**：只读 sheet 的 XML 事件，绕开 POI 重建对象图过程。
3. **共享字符串表经压缩路径读、只读一次**：`ReadOnlySharedStringsTable(pkg)` 不触发完整模型加载；且它是「去重后的字符串」，10 万行里状态/渠道/币种等高度复用，规模远小于行数。
4. **批量入库把调用方堆也堵住**：`ImportExecutionService.runJob` 用 `final List<PendRow> batch` 攒批，满 `batchSize` 就 `flushBatch` 清空——即便读端全摊开，下游堆仍被批大小钉住。

**结论**：堆占用 ≈ 共享字符串表 + 单行缓冲 + 攒批（≤1000 行） + ZIP 读缓冲，**与 total_rows 无关**，故 10 万行在 512MB 下安全。`order-import-design.md §13` 的验收基准「`-Xmx512m` 导入 10 万行不 OOM」就是这个算法的推论（集成测试覆盖小体积 + 渐近行为，大体积性能采样本仓库未落地，见 §11）。

## 4. 三层校验漏斗：成本梯度

| 层级 | 触发线程 | 动作 | 失败 |
|---|---|---|---|
| 文件级 | 受理期 HTTP | 后缀 `.xlsx` / 前 2 字节 `PK` / 大小 ≤ max-size | 同步 400，不落盘不建任务 |
| 结构级 | 受理期 HTTP | Sheet 名 / 表头 / 空文件 / 行数上限 | 同步 400 |
| 行级 | 执行期异步 | 枚举/金额/时间/公式注入/去重/冲突 | 错行跳过 → PARTIAL |

越靠前的越便宜，同步秒回用户；贵的行级校验甩给异步执行体，避免解析阻塞 HTTP 线程。

## 5. 新终态 PARTIAL 与双计数

- `skipped==0 → SUCCEEDED`；有跳过行先生成错误报告再 `markPartial`（PARTIAL 部分成功，任务不失败）。
- `succeeded_rows` / `skipped_rows` 双计数随进度单调推进。
- **FAILED 严格收窄**给执行/基础设施异常；行级错误绝不产生 FAILED。
- **PARTIAL 不可重试**（重试仅 FAILED→PENDING），错误行修正后作为新任务重传。

## 6. 幂等下沉到数据层（与导出相反）

- 导出：请求层 `Idempotency-Key` 防重复受理（重复成本高）。
- 导入：**无幂等键**（重复上传=两个独立任务，成本只是多跑一个空任务），幂等由**数据去重**承担：文件内 `HashSet` 查重 → `selectExistingOrderNos` 冲突预查（IN 命中即跳过）→ `orders.order_no` 唯一约束作最终防线，`DuplicateKeyException` 逐行降级转跳过，**绝不产生重复订单**。

## 7. 表头单一事实源

`ImportColumn` 的 key 委托 `ExportColumn`、中文表头委托 `ExcelExportWriter.titleOf`——表头只在导出侧维护一份，导入自动跟随，互逆不漂移。结构校验因此可严格：Sheet 名等值 `ExcelExportWriter.SHEET_NAME`（「订单数据」）、表头**恰好 9 列**（`headerColumnCount != columns.size()` 拒绝多/缺列）、名称/顺序逐列比对并指认第一个错位列。

## 8. 错误报告产物（PARTIAL 独有）

- 仅 PARTIAL 生成错误报告 xlsx：Excel 行号 / 订单号 / 错误列 / 原因；生成时同步统计 Top N 写 `error_summary` JSON。
- 有界缓冲（上限 `import.error-report-max-rows` 默认 5000）：超限截断展示、计数如实统计。
- 落受控 `importRoot/<UTC 日期>/<jobNo>/errors-attempt-N.xlsx`，DB 失败补偿删除；下载仅 PARTIAL 且登记路径放行。

## 9. 值得注意的坑

1. **文件生命周期是「上传→读取→清理」**而非导出的「写出→发布→下载」：上传原件落 `importRoot` 后即执行唯一输入，DB 失败须补偿删除已落盘文件防孤儿临时文件。
2. **行号对齐**：Excel 内部 0 基 vs 用户眼 1 基，错误报告回指的 excelRowNo 必须 `rowIndex + 1`，错一位用户无法定位。
3. **PARTIAL 语义守住「不失败」**：任何路径不得把带错行任务置 FAILED。
4. **SAX 时序坑**：`getSheetName` 依赖 `next()` 推进；跨 Sheet 输入流必须在最后一格后关闭，POI 对跨引用会抛异常，已归并为 `IOException` 由受理层映射文件损坏。

## 10. 仓库对照

| 导出 | 导入 |
|---|---|
| `ExcelExportWriter` + `WorkbookSession`（SXSSF） | `ExcelImportReader`（SAX）+ `ImportTemplateWriter`/`ImportErrorReportWriter`（SXSSF） |
| `ExportFileService`（受控 exportRoot） | `ImportFileService`（受控 importRoot） |
| `ExportJobMaintenanceService` | `ImportMaintenanceService` |
| — | `ImportRowValidator` / `ImportOrderMapper`（冲突预查+批量入库） |

## 11. 未实现 / 边界（与全局一致）

- 大体积性能采集脚本（带令牌/PID 校验）本仓库未落地：`-Xmx512m` 导入 10 万行的性能基准是算法推论的**设计目标**，非实测产出。
- 鉴权/权限、对象存储、多实例部署：同导出侧，属全局已知未实现能力。
- 错误行增量导入（仅重传错误行）：明确不做（见 `order-import-design.md §12`）。