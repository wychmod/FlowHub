package com.example.exportflow.order.query;

/**
 * 筛选操作符枚举，每个 {@link OrderCriteria} 字段对应一种操作符。
 */
public enum FilterOperator {

    /** 精确等值。 */
    EQ,

    /** 多值 IN。 */
    IN,

    /** 前后通配模糊匹配。 */
    LIKE,

    /** 前缀匹配。 */
    PREFIX,

    /** 区间过滤。 */
    RANGE
}
