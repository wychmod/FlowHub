package com.example.exportflow.order.service;

import com.example.exportflow.order.dto.OrderPageResp;
import com.example.exportflow.order.dto.OrderQuery;
import com.example.exportflow.order.entity.Order;
import com.example.exportflow.order.mapper.OrderMapper;
import com.example.exportflow.order.vo.OrderItemVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订单查询服务。
 *
 * <p>本层承接 Controller，负责分页换算、实体到视图对象的映射，持久化细节委托给
 * {@link OrderMapper}。当前运行期由 {@code InMemoryOrderMapper} 提供 Mock 数据。
 */
@Service
public class OrderService {

    private final OrderMapper orderMapper;

    public OrderService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 分页查询订单，页码超出范围时返回空列表。
     *
     * @param query 分页查询入参（page 从 1 开始，pageSize 1-100）
     */
    public OrderPageResp listOrders(OrderQuery query) {
        long total = orderMapper.countTotal();
        long totalPages = (total + query.pageSize() - 1L) / query.pageSize();
        long offset = (query.page() - 1L) * query.pageSize();

        List<OrderItemVO> items = orderMapper.selectPage(offset, query.pageSize()).stream()
                .map(OrderService::toVO)
                .toList();

        return new OrderPageResp(items, query.page(), query.pageSize(), total, totalPages);
    }

    /** 订单实体转列表行视图对象。 */
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