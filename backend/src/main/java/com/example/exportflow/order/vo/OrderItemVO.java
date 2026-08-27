package com.example.exportflow.order.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单列表行，字段与 be-td.md 4.3 响应示例一致（snake_case）。 */
public record OrderItemVO(
        Long id,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("order_status") String orderStatus,
        @JsonProperty("sales_channel") String salesChannel,
        @JsonProperty("customer_name") String customerName,
        @JsonProperty("total_amount") BigDecimal totalAmount,
        String currency,
        @JsonProperty("created_at") LocalDateTime createdAt) {
}
