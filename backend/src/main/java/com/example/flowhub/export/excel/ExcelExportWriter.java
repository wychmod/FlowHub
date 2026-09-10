package com.example.flowhub.export.excel;

import com.example.flowhub.export.entity.ExportOrderRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * SXSSF 流式 Excel 生成器。
 * <p>
 * 对外只暴露三步：{@link #open(Path, List)} 打开会话 → {@link WorkbookSession#writeBatch} 逐批写入
 * → {@link WorkbookSession#close} 收尾成文件。POI 细节全部收在内部 WorkBookSession，调用方不用碰 POI。
 * <p>
 * 核心记忆模型：内存恒定。SXSSF 只在内存保留最近 {@link #ROW_WINDOW} 行，更早的行刷到 POI 临时磁盘文件；
 * 样式（CellStyle）做成单例复用避免每个单元格新建。close 的三步 write → close → dispose 缺一步，
 * 分别对应「文件不完整」或「磁盘临时文件泄漏」两类问题。
 */
@Component
public class ExcelExportWriter {

    /** SXSSF 滑动窗口：内存只保留最近 100 行对象。它与数据库批次 1000（另一个类里的）是两个独立的资源旋钮。 */
    static final int ROW_WINDOW = 100;

    /** 生成的 Sheet 名（导出文件与导入模板共用同一表名，导入结构级校验按它识别数据 Sheet）。 */
    public static final String SHEET_NAME = "订单数据";
    /** 下单时间列的固定展示格式。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 全部列定义（key → 标题/列宽/是否金额/写入函数），静态建一次全类复用。 */
    private static final Map<String, ColumnSpec> COLUMNS = buildColumns();

    /**
     * 打开一个写入会话。
     * <p>
     * 入参 requestedColumns 是调用方选列的 key（snake_case，如 order_no）；传 null/空则默认全部 9 列。
     * 这里做「列白名单二次复核」：任何一个列 key 不在 COLUMNS 里就直接抛异常——
     * 这是在创建任何文件之前拦截未知列，防御内部某处绕过了上游的列校验。
     */
    public WorkbookSession open(Path target, List<String> requestedColumns) throws IOException {
        // 请求列为空 → 用全部列；否则把请求列复制成不可变 List（拒绝后续被修改）
        List<String> keys = requestedColumns == null || requestedColumns.isEmpty()
                ? new ArrayList<>(COLUMNS.keySet())
                : List.copyOf(requestedColumns);
        // 逐个校验列 key 必须存在于列定义表，防未知列
        for (String key : keys) {
            if (!COLUMNS.containsKey(key)) {
                throw new IllegalArgumentException("不支持的导出列：" + key);
            }
        }
        // 校验通过才真正创建会话（此时文件才被创建）
        return new WorkbookSession(target, keys);
    }

    /**
     * 公式注入防护。
     * <p>
     * Excel 里以 = + - @ 开头的单元格会被当作公式执行（=1+1 会算出结果，
     * 恶意输入甚至能调外部命令）。这里给这类文本开头补一个单引号，
     * 让表格软件把它当普通文本显示、不当作公式执行。
     */
    public static String safeText(String value) {
        if (value == null || value.isEmpty()) {
            return value; // 空值不处理
        }
        return switch (value.charAt(0)) {  // 只看第一个字符
            case '=', '+', '-', '@' -> "'" + value;  // 危险开头 → 前补单引号
            default -> value;                          // 正常开头 → 原样返回
        };
    }

    /**
     * 列 key → 中文表头标题（表头文案单一事实源，导入模板生成与结构级表头比对复用）。
     * 未知列 key 抛出，防调用方拿错误标题静默落盘。
     */
    public static String titleOf(String key) {
        ColumnSpec spec = COLUMNS.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("不支持的导出列：" + key);
        }
        return spec.title();
    }

    /**
     * 构建列定义表。
     * <p>
     * 每一列存：标题、列宽、是否金额样式、一个「怎么把业务行写成单元格」的函数（BiConsumer）。
     * 用 LinkedHashMap 保证默认列顺序稳定（插入顺序）。这是把「列长什么样」集中管理的地方，
     * 新增/调整列只改这里。
     */
    private static Map<String, ColumnSpec> buildColumns() {
        Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        // 文本列：一律套 safeText 防注入；列宽以字符数为单位
        columns.put("order_no", new ColumnSpec("订单号", 22, false,
                (row, cell) -> cell.setCellValue(safeText(row.orderNo()))));
        columns.put("order_status", new ColumnSpec("订单状态", 12, false,
                (row, cell) -> cell.setCellValue(safeText(row.orderStatus()))));
        columns.put("sales_channel", new ColumnSpec("销售渠道", 12, false,
                (row, cell) -> cell.setCellValue(safeText(row.salesChannel()))));
        columns.put("customer_name", new ColumnSpec("客户姓名", 14, false,
                (row, cell) -> cell.setCellValue(safeText(row.customerName()))));
        columns.put("customer_phone", new ColumnSpec("客户手机号", 16, false,
                (row, cell) -> cell.setCellValue(safeText(row.customerPhone()))));
        // 金额列：numeric=true，写成数值型并跳过 safeText（数字没有注入风险），空值则跳过不写单元格
        columns.put("total_amount", new ColumnSpec("订单金额", 14, true,
                (row, cell) -> {
                    if (row.totalAmount() != null) {
                        cell.setCellValue(row.totalAmount().doubleValue());
                    }
                }));
        columns.put("currency", new ColumnSpec("币种", 10, false,
                (row, cell) -> cell.setCellValue(safeText(row.currency()))));
        columns.put("shipping_province", new ColumnSpec("收货省份", 14, false,
                (row, cell) -> cell.setCellValue(safeText(row.shippingProvince()))));
        // 时间列：非空才写成固定格式的字符串
        columns.put("created_at", new ColumnSpec("下单时间", 22, false,
                (row, cell) -> {
                    if (row.createdAt() != null) {
                        cell.setCellValue(row.createdAt().format(TIME_FORMAT));
                    }
                }));
        return columns;
    }

    /**
     * 单列的定义（record 自动生成构造器与访问器）。
     * <p>
     * 四个字段：title=表头文字；width=列宽(字符数)；numeric=是否金额列（决定是否套金额样式）；
     * writer=写入函数，入参是业务行 ExportOrderRow 和单元格 Cell，负责把该列的值写进单元格。
     */
    private record ColumnSpec(String title, int width, boolean numeric, BiConsumer<ExportOrderRow, Cell> writer) {
    }

    /**
     * Excel 写入会话：一个会话 = 一个 Workbook = 一个写者，内部持有一整张"订单数据"表。
     * <p>
     * 并发边界由抢占层保证（同一任务同一时刻只有一个执行者），POI 本身不是协调工具。
     * <p>
     * 必须用 try-with-resources 使用。close 会完成 write → 关流 → close → dispose 的完整收敛：
     * 任一异常保留为首异常，其余异常挂为 suppressed，且无论成败 dispose 都会执行（保证临时文件被清掉）。
     */
    public static final class WorkbookSession implements AutoCloseable {

        private final Path target;            // 输出文件的目标路径（业务临时文件，发布时原子移动成正式文件）
        private final List<String> columnKeys; // 本次实际要写的列 key 列表（顺序即列顺序）
        private final SXSSFWorkbook workbook;  // 流式工作簿（内存只留最近 ROW_WINDOW 行）
        private final SXSSFSheet sheet;        // 当前唯一的 Sheet
        private final OutputStream output;     // 直接指向目标文件的输出流
        private final CellStyle amountStyle;   // 金额列复用的单元格样式（单例，避免逐格新建）
        private int rowCount;                  // 已写入的数据行数（不含表头）

        private WorkbookSession(Path target, List<String> columnKeys) throws IOException {
            this.target = target;
            this.columnKeys = columnKeys;
            Files.createDirectories(target.getParent()); // 目录可能不存在，先建好
            this.output = Files.newOutputStream(target); // 打开指向目标文件的输出流（此刻文件被创建）
            this.workbook = new SXSSFWorkbook(ROW_WINDOW); // 关键：流式工作簿，窗口=100 行
            workbook.setCompressTempFiles(true);          // 刷到磁盘的临时文件压缩，省空间
            this.sheet = workbook.createSheet(SHEET_NAME); // 建一张"订单数据"表
            this.amountStyle = createAmountStyle();        // 建好金额样式单例
            writeHeader();                                 // 先把第 0 行表头写进去
        }

        /**
         * 写入一个数据库批次（由调用方传入一批业务行）。
         * <p>
         * 逐行建 Excel Row，再按列定义逐列建 Cell 并调用对应写入函数。
         * 方法返回即代表这一批已真实进入 Workbook（后续 close 时统一落盘）。
         */
        public void writeBatch(List<ExportOrderRow> batch) {
            for (ExportOrderRow row : batch) {
                // 数据从第 1 行开始：rowCount+1 而不是 rowCount，因为第 0 行已经被表头占了
                Row excelRow = sheet.createRow(rowCount + 1);
                for (int i = 0; i < columnKeys.size(); i++) {
                    // 按列写：i 同时是"列的索引"和"单元格所在列号"
                    ColumnSpec spec = COLUMNS.get(columnKeys.get(i));
                    Cell cell = excelRow.createCell(i);
                    spec.writer().accept(row, cell); // 调用该列的写入函数，把业务值写进单元格
                    if (spec.numeric()) {
                        cell.setCellStyle(amountStyle); // 金额列套统一格式（两位小数）
                    }
                }
                rowCount++; // 写完一行，行数 +1
            }
        }

        /** 已写入的数据行数（不含表头），供调用方判断是否收到不足一批的末尾行。 */
        public int rowCount() {
            return rowCount;
        }

        /** 业务临时文件路径（此时内容已完整写入、但尚未发布为正式文件，发布时做原子移动）。 */
        public Path target() {
            return target;
        }

        /**
         * 收尾：把内存/临时文件里的内容真正写成一个完整 Excel 文件。
         * <p>
         * 顺序有讲究：先 workbook.write(output)（把数据刷进输出流/目标文件）→ 关流 → workbook.close()。
         * 任何一步抛异常都被捕获；首异常保留，后续步骤的异常追加为 suppressed，最终统一抛首异常。
         * 最后无论成败都调用 dispose() 删掉 SXSSF 的磁盘临时文件，防止泄漏。
         */
        @Override
        public void close() throws IOException {
            // 1. 核心：把 Workbook 全部内容写入目标输出流（此时文件才真正成形）
            IOException failure = null;
            try {
                workbook.write(output);
            } catch (IOException ex) {
                failure = ex;
            }
            // 2. 关输出流（刷新并释放文件句柄）
            failure = closeStep(failure, () -> output.close());
            // 3. 关工作簿
            failure = closeStep(failure, () -> workbook.close());
            // 4. 尽力清理 SXSSF 临时文件；dispose 返回 false 仅表示部分清理失败（不影响主体结果）
            workbook.dispose();
            // 若有失败先抛异常，让调用方知道文件没写完整
            if (failure != null) {
                throw failure;
            }
        }

        /** 受检异常的 lambda 接口，供 closeStep 复用（一行一句关闭动作）。 */
        private interface ThrowingClose {
            void close() throws IOException;
        }

        /**
         * 执行一步关闭动作并把异常汇聚到 failure 上：第一步异常作为首异常保留，
         * 后续步骤的异常追加为 suppressed。这样一行关闭失败不会中断后续清理步骤，
         * 尽量把资源都关干净。
         */
        private static IOException closeStep(IOException failure, ThrowingClose step) {
            try {
                step.close();
            } catch (IOException ex) {
                if (failure == null) {
                    return ex;       // 这是第一个异常 → 作为首异常返回
                }
                failure.addSuppressed(ex); // 已有首异常 → 追加为 suppressed
            }
            return failure;
        }

        /**
         * 创建金额列样式：数值格式为两位小数。整个会话只建这一次，所有金额单元格共享。
         */
        private CellStyle createAmountStyle() {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            return style;
        }

        /**
         * 写第 0 行表头（标题），并设好每列宽度。
         * 额外还有两个用户体验设置：冻结首行（让你滚动时表头始终停在顶部）、
         * 给表头加自动筛选下拉（方便用户本地按列过滤/排序）。
         */
        private void writeHeader() {
            Row header = sheet.createRow(0);
            for (int i = 0; i < columnKeys.size(); i++) {
                ColumnSpec spec = COLUMNS.get(columnKeys.get(i));
                header.createCell(i).setCellValue(spec.title());
                // 列宽单位是 256 的倍数（POI 约定），所以用 width * 256
                sheet.setColumnWidth(i, spec.width() * 256);
            }
            // 冻结首行：滚动时第 0 行（表头）固定不动
            sheet.createFreezePane(0, 1);
            // 给表头加自动筛选（区域为第 0 行的所有列）
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, columnKeys.size() - 1));
        }
    }
}