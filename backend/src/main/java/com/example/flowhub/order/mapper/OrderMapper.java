package com.example.flowhub.order.mapper;

import com.example.flowhub.order.entity.Order;
import com.example.flowhub.order.query.OrderCriteria;
import com.example.flowhub.order.query.OrderQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单数据访问接口（MyBatis 实现，SQL 见 resources/mapper/OrderMapper.xml）。
 * <p>
 * 实现契约：排序末尾追加 id 作 tie-breaker；null/空集合条件跳过。
 */
@Mapper
public interface OrderMapper {

    /** 按筛选条件统计订单总数。 */
    long countByCriteria(
            @Param("criteria")
            OrderCriteria criteria);

    /** 分页查询订单。 */
    List<Order> selectPage(OrderQuery query);
}
