package com.example.flowhub.export.dto;

import com.example.flowhub.order.query.SortDirection;
import com.example.flowhub.order.query.SortField;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 筛选导出快照请求体（JSON 绑定，字段名与前端 ExportFilterSnapshot 一一对应）。
 * <p>
 * 字段以 String/原始 List 原样接收，解析与白名单校验由 Service 归一化阶段完成。
 */
public record ExportFilterSnapshotRequest(

        // ==================== 筛选 ====================

        @JsonProperty("order_status")
        List<String> orderStatus,

        @JsonProperty("sales_channel")
        List<String> salesChannel,

        @JsonProperty("currency")
        List<String> currency,

        @JsonProperty("customer_name")
        @Size(max = 128, message = "customer_name 过长")
        String customerName,

        @JsonProperty("order_no")
        @Size(max = 64, message = "order_no 过长")
        String orderNo,

        @JsonProperty("customer_phone")
        @Size(max = 32, message = "customer_phone 过长")
        String customerPhone,

        @JsonProperty("total_amount_min")
        @Size(max = 20, message = "total_amount_min 过长")
        String totalAmountMin,

        @JsonProperty("total_amount_max")
        @Size(max = 20, message = "total_amount_max 过长")
        String totalAmountMax,

        @JsonProperty("created_at_begin")
        @Size(max = 32, message = "created_at_begin 过长")
        String createdAtBegin,

        @JsonProperty("created_at_end")
        @Size(max = 32, message = "created_at_end 过长")
        String createdAtEnd,

        // ==================== 排序 ====================

        @JsonProperty("sort_by")
        @Size(max = 64, message = "sort_by 过长")
        @Pattern(regexp = SortField.NAMES_PATTERN,
                message = "sort_by 仅支持 created_at/total_amount/order_no/id")
        String sortBy,

        @JsonProperty("sort_order")
        @Size(max = 16, message = "sort_order 过长")
        @Pattern(regexp = SortDirection.NAMES_PATTERN,
                message = "sort_order 仅支持 asc/desc")
        String sortOrder) {
}
