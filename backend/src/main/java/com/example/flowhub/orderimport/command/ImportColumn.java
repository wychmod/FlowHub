package com.example.flowhub.orderimport.command;

import com.example.flowhub.export.command.ExportColumn;
import com.example.flowhub.export.excel.ExcelExportWriter;

import java.util.List;

/**
 * 导入列语义（单一事实源锚定导出）：列 key 委托 {@link ExportColumn}，中文表头委托
 * {@link ExcelExportWriter#titleOf}——导入侧不维护任何独立的列名/标题数据，
 * 导出列定义调整时导入自动跟随。枚举定义顺序即表头契约顺序。
 * <p>
 * 每条合法导入文件（模板手填 / 全 9 列导出文件改后传回）都严格按本序表达头，用于模板生成、
 * 结构级表头比对与行级校验逐列定位。
 */
public enum ImportColumn {

    ORDER_NO(ExportColumn.ORDER_NO),
    ORDER_STATUS(ExportColumn.ORDER_STATUS),
    SALES_CHANNEL(ExportColumn.SALES_CHANNEL),
    CUSTOMER_NAME(ExportColumn.CUSTOMER_NAME),
    CUSTOMER_PHONE(ExportColumn.CUSTOMER_PHONE),
    TOTAL_AMOUNT(ExportColumn.TOTAL_AMOUNT),
    CURRENCY(ExportColumn.CURRENCY),
    SHIPPING_PROVINCE(ExportColumn.SHIPPING_PROVINCE),
    CREATED_AT(ExportColumn.CREATED_AT);

    private final ExportColumn exportColumn;

    ImportColumn(ExportColumn exportColumn) {
        this.exportColumn = exportColumn;
    }

    /** 列 key（委托导出列白名单，与导出契约一致的小写 snake_case）。 */
    public String key() {
        return exportColumn.key();
    }

    /** 表头中文标题（委托导出列定义表，与 ExcelExportWriter 严格一致）。 */
    public String title() {
        return ExcelExportWriter.titleOf(exportColumn.key());
    }

    /** 全 9 列（枚举定义顺序即表头契约顺序）。 */
    public static List<ImportColumn> all() {
        return List.of(values());
    }
}
