package com.example.flowhub.order.query;

import java.util.Objects;

/**
 * 订单分页查询值对象 = {@link OrderCriteria} + 分页参数。
 * <p>
 * page 从 1 开始；ORDER BY 末尾必须追加 id 作 tie-breaker。
 */
public record OrderQuery(OrderCriteria criteria, int page, int pageSize) {

    public OrderQuery {
        Objects.requireNonNull(criteria, "criteria 不能为空");
        if (page < 1) {
            throw new IllegalArgumentException("page 必须大于等于 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize 必须大于等于 1");
        }
    }

    /** 跳过行数 = (page - 1) * pageSize。 */
    public long offset() {
        return (page - 1L) * pageSize;
    }
}
