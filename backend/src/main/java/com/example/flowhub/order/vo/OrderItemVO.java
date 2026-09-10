package com.example.flowhub.order.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单列表行，字段与 be-td.md 4.3 响应示例一致（snake_case）。
 * <p>可空列（customer_name/customer_phone 等）序列化保留 null，
 * 前端契约为 {@code string | null}，禁止实体 null 到 VO 的隐式默认值转换。
 */
public record OrderItemVO(
        Long id,

        @JsonProperty("order_no")
        String orderNo,

        @JsonProperty("order_status")
        String orderStatus,

        @JsonProperty("sales_channel")
        String salesChannel,

        @JsonProperty("customer_name")
        String customerName,

        @JsonProperty("customer_phone")
        String customerPhone,

        @JsonProperty("shipping_province")
        String shippingProvince,

        @JsonProperty("total_amount")
        BigDecimal totalAmount,

        String currency,

        @JsonProperty("created_at")
        LocalDateTime createdAt) {
}
