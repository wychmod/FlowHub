package com.example.exportflow.order.query;

import java.util.List;

/**
 * 订单筛选枚举白名单（订单查询与导出快照共用，取值定义以 docs/order-query-design.md 为准）。
 */
public final class OrderFilterWhitelist {

    public static final List<String> STATUSES =
            List.of("PENDING", "PAID", "SHIPPED", "COMPLETED", "CANCELED");
    public static final List<String> SALES_CHANNELS = List.of("WEB", "APP", "STORE", "PARTNER");
    public static final List<String> CURRENCIES = List.of("CNY", "USD", "EUR", "HKD");

    private OrderFilterWhitelist() {
    }
}
