package com.example.exportflow.order.service;

import com.example.exportflow.order.dto.OrderPageResp;
import com.example.exportflow.order.dto.OrderRequest;
import com.example.exportflow.order.entity.Order;
import com.example.exportflow.order.mapper.OrderMapper;
import com.example.exportflow.order.vo.OrderItemVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订单查询服务。
 * <p>
 * 本层承接 Controller，负责分页换算、实体到视图对象的映射，持久化细节委托给 {@link OrderMapper}。
 * 当前运行期由 {@code InMemoryOrderMapper} 提供 Mock 数据。
 */
@Service
public class OrderService {

    private final OrderMapper orderMapper;

    public OrderService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 分页查询订单。
     *
     * @param request 订单分页请求，page 从 1 开始，pageSize 1-100
     * @return 订单分页结果
     */
    public OrderPageResp listOrders(OrderRequest request) {
        long total = orderMapper.countTotal();
        long totalPages = (total + request.getPageSize() - 1L) / request.getPageSize();
        long offset = (request.getPage() - 1L) * request.getPageSize();

        List<OrderItemVO> items = orderMapper.selectPage(offset, request.getPageSize()).stream()
                .map(OrderService::toVO)
                .toList();

        return new OrderPageResp(items, request.getPage(), request.getPageSize(), total, totalPages);
    }

    /**
     * 订单实体转列表视图对象。
     *
     * @param order 订单实体
     * @return 订单列表项视图
     */
    private static OrderItemVO toVO(Order order) {
        return new OrderItemVO(
                order.id(),
                order.orderNo(),
                order.orderStatus(),
                order.salesChannel(),
                order.customerName(),
                order.totalAmount(),
                order.currency(),
                order.createdAt());
    }
}
