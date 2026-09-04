package com.example.exportflow.export.command;

/**
 * 导出列白名单（与前端 EXPORT_COLUMN_OPTIONS 9 列对齐，枚举定义顺序即列输出契约顺序）。
 * <p>
 * 常量名须与列 key 的大写形式一致，供 enumFromName 大小写不敏感解析。
 */
public enum ExportColumn {

    ORDER_NO("order_no"),
    ORDER_STATUS("order_status"),
    SALES_CHANNEL("sales_channel"),
    CUSTOMER_NAME("customer_name"),
    CUSTOMER_PHONE("customer_phone"),
    TOTAL_AMOUNT("total_amount"),
    CURRENCY("currency"),
    SHIPPING_PROVINCE("shipping_province"),
    CREATED_AT("created_at");

    private final String key;

    ExportColumn(String key) {
        this.key = key;
    }

    /** 列 key（与前端契约一致的小写 snake_case）。 */
    public String key() {
        return key;
    }
}
