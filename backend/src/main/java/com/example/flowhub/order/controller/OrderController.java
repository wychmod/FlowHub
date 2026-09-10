package com.example.flowhub.order.controller;

import com.example.flowhub.order.dto.OrderPageResp;
import com.example.flowhub.order.dto.OrderRequest;
import com.example.flowhub.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单查询接口，入参统一封装为 {@link OrderRequest}（record + @BindParam）。
 */
@RestController
@RequestMapping("/orders")
@Validated
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 分页查询订单。 */
    @GetMapping
    public OrderPageResp listOrders(@Valid @ModelAttribute OrderRequest request) {
        return orderService.listOrders(request);
    }
}
