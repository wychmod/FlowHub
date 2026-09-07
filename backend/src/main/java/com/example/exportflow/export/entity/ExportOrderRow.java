package com.example.exportflow.export.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 导出订单行投影（orders 表按 9 列白名单读取的批查结果），id 同时充当 Keyset 游标。
 */
public record ExportOrderRow(
        Long id,
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
