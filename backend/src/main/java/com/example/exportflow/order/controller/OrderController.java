package com.example.exportflow.order.controller;

import com.example.exportflow.common.web.api.ApiResponse;
import com.example.exportflow.order.dto.OrderPageResp;
import com.example.exportflow.order.dto.OrderRequest;
import com.example.exportflow.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单查询接口。
 * <p>
 * 复杂查询入参统一封装为 {@link OrderRequest}，避免控制器层散落多个 {@code @RequestParam}。
 * 默认值与校验规则都收敛在请求 DTO 内，后续新增筛选条件时只需要扩展该对象。
 * <p>{@code /api/v1} 前缀由 {@code ApiWebMvcConfiguration} 统一追加，这里只声明相对路径 {@code /orders}。
 */
@RestController
@RequestMapping("/orders")
@Validated
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 查询订单分页数据。
     *
     * @param request 订单查询请求，承载分页参数和默认值
     * @return 统一响应封装的订单分页结果
     */
    @GetMapping
    public ApiResponse<OrderPageResp> listOrders(@Valid @ModelAttribute OrderRequest request) {
        return ApiResponse.success(orderService.listOrders(request));
    }
}
