package com.example.exportflow.order.dto;

import com.example.exportflow.order.vo.OrderItemVO;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 订单分页响应，见 be-td.md 4.3 与 docs/order-query-design.md 第六节。
 * <p>
 * {@code sort_by}/{@code sort_order} 回显实际生效的排序（默认值展开后的结果，
 * 如未传时回显 {@code "created_at"}/{@code "desc"}），作为确定契约供前端对齐当前排序状态。
 */
public record OrderPageResp(
        List<OrderItemVO> items,
        int page,

        @JsonProperty("page_size")
        int pageSize,

        long total,

        @JsonProperty("total_pages")
        long totalPages,

        @JsonProperty("sort_by")
        String sortBy,

        @JsonProperty("sort_order")
        String sortOrder) {
}
