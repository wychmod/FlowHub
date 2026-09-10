package com.example.exportflow.orderimport.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 导入订单行（经行级校验后待入库的订单值对象；order_status 与 status 同值双写）。 */
public record ImportOrderRow(
        String orderNo,
        String orderStatus,
        String salesChannel,
        String customerName,
        String customerPhone,
        BigDecimal totalAmount,
        String currency,
        String shippingProvince,
        LocalDateTime createdAt) {
}