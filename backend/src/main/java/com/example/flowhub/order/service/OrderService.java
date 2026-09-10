package com.example.flowhub.order.service;

import com.example.flowhub.common.web.error.BusinessException;
import com.example.flowhub.common.web.param.ParamUtils;
import com.example.flowhub.order.dto.OrderPageResp;
import com.example.flowhub.order.dto.OrderRequest;
import com.example.flowhub.order.entity.Order;
import com.example.flowhub.order.mapper.OrderMapper;
import com.example.flowhub.order.query.OrderCriteria;
import com.example.flowhub.order.query.OrderFilterWhitelist;
import com.example.flowhub.order.query.OrderQuery;
import com.example.flowhub.order.query.OrderSort;
import com.example.flowhub.order.vo.OrderItemVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 订单查询服务：入参归一化、语义校验、组装查询对象、委托 Mapper。
 */
@Service
public class OrderService {

    private final OrderMapper orderMapper;

    public OrderService(OrderMapper orderMapper) {
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper 不能为空");
    }

    /** 分页查询订单。 */
    public OrderPageResp listOrders(OrderRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        OrderQuery query = toQuery(request);
        OrderCriteria criteria = query.criteria();

        long total = orderMapper.countByCriteria(criteria);
        long totalPages = (total + query.pageSize() - 1L) / query.pageSize();
        List<OrderItemVO> items = orderMapper.selectPage(query).stream()
                .map(OrderService::toVO)
                .toList();

        return new OrderPageResp(items, query.page(), query.pageSize(), total, totalPages,
                criteria.sortField().column(),
                criteria.sortDirection().name().toLowerCase(Locale.ROOT));
    }

    /** 请求 DTO → 查询值对象。 */
    private OrderQuery toQuery(OrderRequest request) {
        List<String> statuses = ParamUtils.parseMultiEnum(
                request.orderStatus(), OrderFilterWhitelist.STATUSES, "order_status");
        List<String> salesChannels = ParamUtils.parseMultiEnum(
                request.salesChannel(), OrderFilterWhitelist.SALES_CHANNELS, "sales_channel");
        List<String> currencies = ParamUtils.parseMultiEnum(
                request.currency(), OrderFilterWhitelist.CURRENCIES, "currency");

        String customerName = ParamUtils.trimToNull(request.customerName());
        String orderNo = ParamUtils.trimToNull(request.orderNo());
        String customerPhone = ParamUtils.parsePhone(request.customerPhone(), "customer_phone");

        BigDecimal amountMin = ParamUtils.parseDecimal(request.totalAmountMin(), "total_amount_min");
        BigDecimal amountMax = ParamUtils.parseDecimal(request.totalAmountMax(), "total_amount_max");
        if (amountMin != null && amountMax != null && amountMin.compareTo(amountMax) > 0) {
            throw BusinessException.validation("total_amount_min 不能大于 total_amount_max");
        }

        LocalDateTime createdAtBegin = ParamUtils.parseDateTime(request.createdAtBegin(), "created_at_begin");
        LocalDateTime createdAtEnd = ParamUtils.parseDateTime(request.createdAtEnd(), "created_at_end");
        if (createdAtBegin != null && createdAtEnd != null && !createdAtBegin.isBefore(createdAtEnd)) {
            throw BusinessException.validation("created_at_begin 必须早于 created_at_end");
        }

        OrderSort sort = OrderSort.resolve(request.sortBy(), request.sortOrder());

        OrderCriteria criteria = new OrderCriteria(
                List.of(), List.of(), statuses, salesChannels, currencies,
                customerName, orderNo, customerPhone,
                amountMin, amountMax, createdAtBegin, createdAtEnd,
                sort.field(), sort.direction());
        return new OrderQuery(criteria, request.page(), request.pageSize());
    }

    private static OrderItemVO toVO(Order order) {
        return new OrderItemVO(
                order.id(),
                order.orderNo(),
                order.orderStatus(),
                order.salesChannel(),
                order.customerName(),
                order.customerPhone(),
                order.shippingProvince(),
                order.totalAmount(),
                order.currency(),
                order.createdAt());
    }
}
