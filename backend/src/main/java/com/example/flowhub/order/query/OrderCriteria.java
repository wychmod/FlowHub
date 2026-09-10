package com.example.flowhub.order.query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单筛选条件值对象（service → mapper 查询契约），不含分页。
 * <p>
 * 集合字段恒非 null（空集合 = 无该条件），标量字段 null = 无该条件。
 *
 * @param ids            订单 ID 集合（IN），页面查询恒为空
 * @param excludedIds    排除的订单 ID 集合（NOT IN），仅导出反选使用
 * @param statuses       订单状态（IN）
 * @param salesChannels  销售渠道（IN）
 * @param currencies     币种（IN）
 * @param customerName   客户姓名（LIKE）
 * @param orderNo        订单号（PREFIX）
 * @param customerPhone  客户手机号（EQ）
 * @param amountMin      金额下界（含）
 * @param amountMax      金额上界（含）
 * @param createdAtBegin 下单时间下界（含）
 * @param createdAtEnd   下单时间上界（不含）
 * @param sortField      排序字段
 * @param sortDirection  排序方向
 */
public record OrderCriteria(
        List<Long> ids,
        List<Long> excludedIds,
        List<String> statuses,
        List<String> salesChannels,
        List<String> currencies,
        String customerName,
        String orderNo,
        String customerPhone,
        BigDecimal amountMin,
        BigDecimal amountMax,
        LocalDateTime createdAtBegin,
        LocalDateTime createdAtEnd,
        SortField sortField,
        SortDirection sortDirection) {

    public OrderCriteria {
        ids = ids == null ? List.of() : List.copyOf(ids);
        excludedIds = excludedIds == null ? List.of() : List.copyOf(excludedIds);
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        salesChannels = salesChannels == null ? List.of() : List.copyOf(salesChannels);
        currencies = currencies == null ? List.of() : List.copyOf(currencies);
    }

    /** 是否无任何筛选条件（不含排序与排除集合）。 */
    public boolean isEmpty() {
        return ids.isEmpty() && excludedIds.isEmpty() && statuses.isEmpty() && salesChannels.isEmpty()
                && currencies.isEmpty()
                && customerName == null && orderNo == null && customerPhone == null
                && amountMin == null && amountMax == null
                && createdAtBegin == null && createdAtEnd == null;
    }
}
