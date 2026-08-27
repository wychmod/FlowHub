package com.example.exportflow.order.controller;

import com.example.exportflow.common.web.api.ApiResponse;
import com.example.exportflow.order.dto.OrderPageResp;
import com.example.exportflow.order.dto.OrderQuery;
import com.example.exportflow.order.service.OrderService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单查询接口，见 be-td.md 4.3。
 *
 * <p>MVP 完整版将支持筛选与排序参数；当前骨架仅保留分页参数以打通前后端链路。
 * 分页参数在 {@code @RequestParam} 上直接校验（失败返回 400 + VALIDATION_ERROR），
 * 再封装为输入 DTO {@link OrderQuery} 传递给 Service。
 *
 * <p>{@code /api/v1} 前缀由 {@code ApiWebMvcConfiguration} 按 {@code @RestController} 统一追加，
 * 此处仅声明相对路径 {@code /orders}。
 */
@RestController
@RequestMapping("/orders")
@Validated
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ApiResponse<OrderPageResp> listOrders(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 必须大于等于 1") int page,
            @RequestParam(name = "page_size", defaultValue = "20")
            @Min(value = 1, message = "page_size 必须在 1-100 之间")
            @Max(value = 100, message = "page_size 必须在 1-100 之间") int pageSize) {
        return ApiResponse.success(orderService.listOrders(new OrderQuery(page, pageSize)));
    }
}