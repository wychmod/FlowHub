package com.example.exportflow.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 订单分页查询请求。
 * <p>
 * 控制器层对复杂查询入参优先封装成 Request DTO，便于统一承载默认值、校验规则和后续扩展字段。
 * 这里保留 {@code page_size} 的 JavaBean 别名，兼容前端当前的下划线参数名。
 */
public class OrderRequest {

    @Min(value = 1, message = "page 必须大于等于 1")
    private int page = 1;

    @Min(value = 1, message = "page_size 必须在 1-100 之间")
    @Max(value = 100, message = "page_size 必须在 1-100 之间")
    private int pageSize = 20;

    /**
     * 当前页码，默认 1。
     *
     * @return 页码
     */
    public int getPage() {
        return page;
    }

    /**
     * 设置当前页码。
     *
     * @param page 页码
     */
    public void setPage(int page) {
        this.page = page;
    }

    /**
     * 每页条数，默认 20。
     *
     * @return 每页条数
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * 设置每页条数。
     *
     * @param pageSize 每页条数
     */
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * page_size 别名 getter，用于兼容 snake_case 请求参数。
     *
     * @return 每页条数
     */
    public int getPage_size() {
        return pageSize;
    }

    /**
     * page_size 别名 setter，用于兼容 snake_case 请求参数。
     *
     * @param pageSize 每页条数
     */
    public void setPage_size(int pageSize) {
        this.pageSize = pageSize;
    }
}
