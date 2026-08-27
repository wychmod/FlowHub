package com.example.exportflow.order.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体，对应 orders 表（be-td.md 3.2）。
 *
 * <p>字段采用驼峰命名，与数据库下划线列名在接入 MyBatis 后通过
 * {@code map-underscore-to-camel-case} 自动映射。当前骨架尚未引入数据源，
 * 由 {@code InMemoryOrderMapper} 提供同结构的内存构造数据。
 */
public record Order(
        Long id,
        String orderNo,
        String orderStatus,
        String salesChannel,
        String customerName,
        BigDecimal totalAmount,
        String currency,
        LocalDateTime createdAt) {
}