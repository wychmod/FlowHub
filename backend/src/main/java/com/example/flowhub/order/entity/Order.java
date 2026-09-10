package com.example.flowhub.order.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体，对应 orders 表。
 * <p>
 * 驼峰命名，通过 MyBatis map-underscore-to-camel-case + 构造器自动映射。
 * 除 id/orderNo/totalAmount/createdAt 外其余字段可空。
 */
public record Order(
        Long id,
        String orderNo,
        String orderStatus,
        String salesChannel,
        String customerName,
        String customerPhone,
        String shippingProvince,
        BigDecimal totalAmount,
        String currency,
        LocalDateTime createdAt) {
}
