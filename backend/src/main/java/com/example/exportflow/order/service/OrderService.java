package com.example.exportflow.order.service;

import com.example.exportflow.common.web.error.BusinessException;
import com.example.exportflow.common.web.error.CommonErrorCode;
import com.example.exportflow.common.web.param.ParamUtils;
import com.example.exportflow.order.dto.OrderPageResp;
import com.example.exportflow.order.dto.OrderRequest;
import com.example.exportflow.order.entity.Order;
import com.example.exportflow.order.mapper.OrderMapper;
import com.example.exportflow.order.query.OrderCriteria;
import com.example.exportflow.order.query.OrderQuery;
import com.example.exportflow.order.query.SortDirection;
import com.example.exportflow.order.query.SortField;
import com.example.exportflow.order.vo.OrderItemVO;
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

    private static final List<String> STATUS_WHITELIST =
            List.of("PENDING", "PAID", "SHIPPED", "COMPLETED", "CANCELED");
    private static final List<String> CHANNEL_WHITELIST = List.of("WEB", "APP", "STORE", "PARTNER");
    private static final List<String> CURRENCY_WHITELIST = List.of("CNY", "USD", "EUR", "HKD");

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
        List<String> statuses = ParamUtils.parseMultiEnum(request.orderStatus(), STATUS_WHITELIST, "order_status");
        List<String> salesChannels = ParamUtils.parseMultiEnum(
                request.salesChannel(), CHANNEL_WHITELIST, "sales_channel");
        List<String> currencies = ParamUtils.parseMultiEnum(request.currency(), CURRENCY_WHITELIST, "currency");

        String customerName = ParamUtils.trimToNull(request.customerName());
        String orderNo = ParamUtils.trimToNull(request.orderNo());
        String customerPhone = ParamUtils.parsePhone(request.customerPhone(), "customer_phone");

        BigDecimal amountMin = ParamUtils.parseDecimal(request.totalAmountMin(), "total_amount_min");
        BigDecimal amountMax = ParamUtils.parseDecimal(request.totalAmountMax(), "total_amount_max");
        if (amountMin != null && amountMax != null && amountMin.compareTo(amountMax) > 0) {
            throw validation("total_amount_min 不能大于 total_amount_max");
        }

        LocalDateTime createdAtBegin = ParamUtils.parseDateTime(request.createdAtBegin(), "created_at_begin");
        LocalDateTime createdAtEnd = ParamUtils.parseDateTime(request.createdAtEnd(), "created_at_end");
        if (createdAtBegin != null && createdAtEnd != null && !createdAtBegin.isBefore(createdAtEnd)) {
            throw validation("created_at_begin 必须早于 created_at_end");
        }

        SortSpec sort = parseSort(request.sortBy(), request.sortOrder());

        OrderCriteria criteria = new OrderCriteria(
                List.of(), statuses, salesChannels, currencies,
                customerName, orderNo, customerPhone,
                amountMin, amountMax, createdAtBegin, createdAtEnd,
                sort.field(), sort.direction());
        return new OrderQuery(criteria, request.page(), request.pageSize());
    }

    /**
     * 排序解析：均缺省用 created_at+desc；只传 sort_by 用字段默认方向；
     * 只传 sort_order 抛 400。
     */
    private static SortSpec parseSort(String rawField, String rawDirection) {
        String normalizedField = ParamUtils.trimToNull(rawField);
        String normalizedDirection = ParamUtils.trimToNull(rawDirection);
        if (normalizedField == null && normalizedDirection != null) {
            throw validation("sort_order 不能脱离 sort_by 单独使用");
        }
        if (normalizedField == null) {
            return new SortSpec(SortField.CREATED_AT, SortField.CREATED_AT.defaultDirection());
        }
        SortField field = SortField.fromName(normalizedField);
        if (field == null) {
            throw validation("不支持的排序字段：" + normalizedField);
        }
        SortDirection direction = field.defaultDirection();
        if (normalizedDirection != null) {
            direction = SortDirection.fromName(normalizedDirection);
            if (direction == null) {
                throw validation("不支持的排序方向：" + normalizedDirection);
            }
        }
        return new SortSpec(field, direction);
    }

    private record SortSpec(SortField field, SortDirection direction) {
    }

    private static BusinessException validation(String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_ERROR, message);
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
