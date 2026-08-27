package com.example.exportflow.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 订单分页查询入参（输入 DTO）。
 *
 * <p>承载分页参数并带上界校验注解；实际校验由 Controller 层对 {@code @RequestParam}
 * 统一触发（失败返回 400 + VALIDATION_ERROR），本 DTO 的注解作为自文档与防御性约束。
 */
public record OrderQuery(
        @Min(value = 1, message = "page 必须大于等于 1") int page,
        @Min(value = 1, message = "page_size 必须在 1-100 之间")
        @Max(value = 100, message = "page_size 必须在 1-100 之间") int pageSize) {
}