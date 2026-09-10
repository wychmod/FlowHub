package com.example.flowhub.order.query;

import com.example.flowhub.common.web.param.ParamUtils;

/**
 * 排序字段白名单枚举。
 * <p>
 * 列名只能来自本枚举映射，防止 SQL 注入。
 */
public enum SortField {

    /** 下单时间，默认降序。 */
    CREATED_AT("created_at", SortDirection.DESC),

    /** 订单总金额，默认降序。 */
    TOTAL_AMOUNT("total_amount", SortDirection.DESC),

    /** 订单号，默认升序。 */
    ORDER_NO("order_no", SortDirection.ASC),

    /** 主键，排序 tie-breaker。 */
    ID("id", SortDirection.ASC);

    /**
     * sort_by 合法取值正则，供 OrderRequest 的 {@code @Pattern} 引用。
     * <p>
     * 新增/修改排序字段时须同步更新。
     */
    public static final String NAMES_PATTERN = "\\s*(?i:created_at|total_amount|order_no|id)?\\s*";

    private final String column;
    private final SortDirection defaultDirection;

    SortField(String column, SortDirection defaultDirection) {
        this.column = column;
        this.defaultDirection = defaultDirection;
    }

    /** 数据库列名。 */
    public String column() {
        return column;
    }

    /** 默认排序方向。 */
    public SortDirection defaultDirection() {
        return defaultDirection;
    }

    /** 大小写不敏感解析，未命中返回 null。 */
    public static SortField fromName(String name) {
        return ParamUtils.enumFromName(name, SortField.class);
    }
}
