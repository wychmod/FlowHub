package com.example.flowhub.order.dto;

import com.example.flowhub.order.query.SortDirection;
import com.example.flowhub.order.query.SortField;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.BindParam;

/**
 * 订单查询请求（record + @BindParam 构造器绑定）。
 * <p>
 * 筛选字段为 String 原样接收，null 表示未传；归一化与语义校验由 OrderService 完成。
 */
public record OrderRequest(

        // ==================== 分页 ====================

        @Min(value = 1, message = "page 必须大于等于 1")
        Integer page,

        @BindParam("page_size")
        @Min(value = 1, message = "page_size 必须在 1-100 之间")
        @Max(value = 100, message = "page_size 必须在 1-100 之间")
        Integer pageSize,

        // ==================== 筛选 ====================

        @BindParam("order_status")
        @Size(max = 64, message = "order_status 过长")
        String orderStatus,

        @BindParam("sales_channel")
        @Size(max = 64, message = "sales_channel 过长")
        String salesChannel,

        @BindParam("currency")
        @Size(max = 32, message = "currency 过长")
        String currency,

        @BindParam("customer_name")
        @Size(max = 128, message = "customer_name 过长")
        String customerName,

        @BindParam("order_no")
        @Size(max = 64, message = "order_no 过长")
        String orderNo,

        @BindParam("customer_phone")
        @Size(max = 32, message = "customer_phone 过长")
        String customerPhone,

        @BindParam("total_amount_min")
        @Size(max = 20, message = "total_amount_min 过长")
        String totalAmountMin,

        @BindParam("total_amount_max")
        @Size(max = 20, message = "total_amount_max 过长")
        String totalAmountMax,

        @BindParam("created_at_begin")
        @Size(max = 32, message = "created_at_begin 过长")
        String createdAtBegin,

        @BindParam("created_at_end")
        @Size(max = 32, message = "created_at_end 过长")
        String createdAtEnd,

        // ==================== 排序 ====================

        @BindParam("sort_by")
        @Size(max = 64, message = "sort_by 过长")
        @Pattern(regexp = SortField.NAMES_PATTERN,
                message = "sort_by 仅支持 created_at/total_amount/order_no/id")
        String sortBy,

        @BindParam("sort_order")
        @Size(max = 16, message = "sort_order 过长")
        @Pattern(regexp = SortDirection.NAMES_PATTERN,
                message = "sort_order 仅支持 asc/desc")
        String sortOrder) {

    /** 紧凑构造器：分页参数默认值兜底（page=1, pageSize=20）。 */
    public OrderRequest {
        if (page == null) {
            page = 1;
        }
        if (pageSize == null) {
            pageSize = 20;
        }
    }
}
