package com.example.exportflow.order.query;

import com.example.exportflow.common.web.param.ParamUtils;

/**
 * 排序字段白名单枚举（见 docs/order-query-design.md 第三节）。
 * <p>
 * {@code ORDER BY} 无法参数化，列名只能来自本枚举的映射；任何不在白名单里的排序值
 * 一律 400，绝不字符串拼接进 SQL（防注入是硬约束）。
 */
public enum SortField {

    /** 下单时间，业务主排序键，索引友好。 */
    CREATED_AT("created_at", SortDirection.DESC),

    /** 订单总金额。 */
    TOTAL_AMOUNT("total_amount", SortDirection.DESC),

    /** 订单号，字典序唯一键，可作稳定排序。 */
    ORDER_NO("order_no", SortDirection.ASC),

    /** 内部兜底键 / tie-breaker。 */
    ID("id", SortDirection.ASC);

    /** 数据库列名（仅限白名单映射，禁止由用户输入拼接）。 */
    private final String column;

    /** 未显式传方向时该字段的默认方向。 */
    private final SortDirection defaultDirection;

    SortField(String column, SortDirection defaultDirection) {
        this.column = column;
        this.defaultDirection = defaultDirection;
    }

    /**
     * 数据库列名。
     *
     * @return orders 表列名
     */
    public String column() {
        return column;
    }

    /**
     * 该字段的默认排序方向（sort 只传字段名时使用）。
     *
     * @return 默认方向
     */
    public SortDirection defaultDirection() {
        return defaultDirection;
    }

    /**
     * 大小写不敏感解析排序字段（通用解析委托 {@link ParamUtils#enumFromName}）。
     *
     * @param name 字段名字符串
     * @return 对应枚举；未命中白名单返回 null，由调用方决定抛 400
     */
    public static SortField fromName(String name) {
        return ParamUtils.enumFromName(name, SortField.class);
    }
}
