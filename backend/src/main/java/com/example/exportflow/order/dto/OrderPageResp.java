package com.example.exportflow.order.dto;

import com.example.exportflow.order.vo.OrderItemVO;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 订单分页响应，见 be-td.md 4.3。
 */
public record OrderPageResp(
        List<OrderItemVO> items,
        int page,
        @JsonProperty("page_size") int pageSize,
        long total,
        @JsonProperty("total_pages") long totalPages) {
}
