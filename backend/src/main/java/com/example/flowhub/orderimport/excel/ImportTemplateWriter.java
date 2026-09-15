package com.example.flowhub.orderimport.excel;

import com.example.flowhub.orderimport.command.ImportColumn;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 导入模板生成：纯表头 + 下拉约束 + 金额列预设格式 + 填写说明。
 * <p>
 * 模板为手持小文件，直接压到内存 byte[] 返回，不落 importRoot（避免污染受控目录、无需清理）。
 * 「订单数据」Sheet 只有表头一行（不放示例行——示例会被当作数据行导入）；表头 9 列名称/顺序
 * 经 {@link ImportColumn} 锚定导出格式——模板即「用户手填合法文件」的范式。
 */
@Component
public class ImportTemplateWriter {

    /** 下拉约束覆盖的数据行区间（Excel 第 2 行起）；超出范围的单元格可手动输入（下拉非硬约束）。 */
    private static final int DROPDOWN_FIRST_ROW = 2;   // 1 基 Excel 行号：第 1 行表头，第 2 行起数据
    private static final int DROPDOWN_LAST_ROW = 500;

    private static final List<String> ORDER_STATUSES = List.of("PENDING", "PAID", "SHIPPED", "COMPLETED", "CANCELED");
    private static final List<String> SALES_CHANNELS = List.of("WEB", "APP", "STORE", "PARTNER");
    private static final List<String> CURRENCIES = List.of("CNY", "USD", "EUR", "HKD");

    /** 已渲染的模板字节流缓存：模板是固定静态产物，首次调用惰性生成一次后复用，避免每次请求从 0 重建工作簿。 */
    private volatile byte[] cached;

    /** 渲染模板为字节流：Sheet「订单数据」+ Sheet「填写说明」。首次惰性生成后缓存，后续直接复用。
     * @return 模板文件的二进制内容（xlsx） */
    public byte[] render() throws IOException {
        byte[] bytes = cached;
        if (bytes == null) {
            // 并发首访可能重复生成：渲染确定性、无副作用、开销小，最终一致即可，无需加锁
            bytes = renderFresh();
            cached = bytes;
        }
        return bytes;
    }

    /** 真正渲染模板：每次调用新建一个 XSSFWorkbook 并写成 byte[]。 */
    private byte[] renderFresh() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet data = workbook.createSheet("订单数据");
            CellStyle headerStyle = headerStyle(workbook);
            List<ImportColumn> columns = ImportColumn.all();

            Row header = data.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                ImportColumn column = columns.get(i);
                header.createCell(i).setCellValue(column.title());
                header.getCell(i).setCellStyle(headerStyle);
                data.setColumnWidth(i, Math.min(24, columnWidth(column)) * 256);
            }
            data.createFreezePane(0, 1);
            // 金额列整列预设 0.00 数值格式（手填数字自动带两位小数展示，与导出格式互逆）
            data.setDefaultColumnStyle(ImportColumn.TOTAL_AMOUNT.ordinal(), amountStyle(workbook));

            addDropdowns(data, columns);

            writeInstructions(workbook);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                workbook.write(out);
                return out.toByteArray();
            }
        }
    }

    /** 表头样式：加粗 + 底色 + 边框（区别于示例行，提示用户该行勿改）。 */
    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    /** 金额列样式：0.00 数值格式（预设到整列，避免手填金额被 Excel 当常规文本）。 */
    private static CellStyle amountStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
        return style;
    }

    /** 枚举列加下拉约束（订单状态/销售渠道/币种）；时间列固定文本不建议下拉以保持决策 4 的文本语义。 */
    private static void addDropdowns(XSSFSheet sheet, List<ImportColumn> columns) {
        XSSFDataValidationHelper helper = new XSSFDataValidationHelper(sheet);
        for (int i = 0; i < columns.size(); i++) {
            List<String> allowed = allowed(columns.get(i));
            if (allowed == null) {
                continue;
            }
            var constraint = helper.createExplicitListConstraint(allowed.toArray(new String[0]));
            var address = new org.apache.poi.ss.util.CellRangeAddressList(
                    DROPDOWN_FIRST_ROW - 1, DROPDOWN_LAST_ROW - 1, i, i); // POI 用 0 基行号
            DataValidation validation = helper.createValidation(constraint, address);
            validation.setShowErrorBox(true);
            sheet.addValidationData(validation);
        }
    }

    private static List<String> allowed(ImportColumn column) {
        return switch (column) {
            case ORDER_STATUS -> ORDER_STATUSES;
            case SALES_CHANNEL -> SALES_CHANNELS;
            case CURRENCY -> CURRENCIES;
            default -> null;
        };
    }

    /** 列宽：文本列适中，订单号/下单时间稍宽（单位 = 字符数）。 */
    private static int columnWidth(ImportColumn column) {
        return switch (column) {
            case ORDER_NO, CREATED_AT, CUSTOMER_PHONE -> 22;
            default -> 14;
        };
    }

    /** 填写说明 Sheet：逐列必填性/格式/枚举取值/常见错误提示。 */
    private static void writeInstructions(XSSFWorkbook workbook) {
        Sheet guide = workbook.createSheet("填写说明");
        Row title = guide.createRow(0);
        title.createCell(0).setCellValue("填写说明（导入前请阅读）");
        String[][] rows = {
                {"列名", "是否必填", "格式 / 取值", "错误提示示例"},
                {"订单号", "必填", "文本，≤64 字符，文件内唯一", "订单号为空 / 订单号过长 / 订单号在文件内重复"},
                {"订单状态", "必填", "PENDING/PAID/SHIPPED/COMPLETED/CANCELED（不区分大小写）", "订单状态非法"},
                {"销售渠道", "必填", "WEB/APP/STORE/PARTNER（不区分大小写）", "销售渠道非法"},
                {"客户姓名", "必填", "文本，≤128 字符", "客户姓名为空 / 客户姓名过长"},
                {"客户手机号", "选填", "文本，≤32 字符", "客户手机号过长"},
                {"订单金额", "必填", "非负数字，最多 2 位小数", "订单金额必须为非负数字 / 最多 2 位小数"},
                {"币种", "必填", "CNY/USD/EUR/HKD（不区分大小写）", "币种非法"},
                {"收货省份", "选填", "文本，≤64 字符", "收货省份过长"},
                {"下单时间", "必填", "严格文本：yyyy-MM-dd HH:mm:ss", "下单时间须为 yyyy-MM-dd HH:mm:ss 文本"},
        };
        for (int i = 0; i < rows.length; i++) {
            Row row = guide.createRow(i + 1);
            for (int j = 0; j < rows[i].length; j++) {
                row.createCell(j).setCellValue(rows[i][j]);
            }
        }
        for (int i = 0; i < 4; i++) {
            guide.setColumnWidth(i, 30 * 256);
        }
        guide.createRow(rows.length + 2).createCell(0)
                .setCellValue("注意：有效行照常导入，错误行跳过并生成「错误报告」；订单金额为文本数字，下单时间务必按固定格式填写。");
    }
}