package com.example.exportflow.order.mapper;

import com.example.exportflow.order.entity.Order;
import com.example.exportflow.order.query.OrderCriteria;
import com.example.exportflow.order.query.OrderQuery;
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

    /** 订单表当前最大 ID（空表返回 0），导出创建时用作一致性边界。 */
    long selectMaxId();
}
