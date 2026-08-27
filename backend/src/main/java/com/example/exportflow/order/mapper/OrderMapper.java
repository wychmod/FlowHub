package com.example.exportflow.order.mapper;

import com.example.exportflow.order.entity.Order;

import java.util.List;

/**
 * 订单数据访问接口。
 *
 * <p>接口隔离了持久化细节，Service 只依赖本接口。当前骨架接入了 MyBatis 依赖但
 * 尚无数据源，故运行期由 {@link InMemoryOrderMapper} 提供内存 Mock 实现保证接口可调用；
 * 引入 MySQL 后，将改为带 {@code @Mapper} 注解的 MyBatis 实现（见 be-td.md 5.1）。
 */
public interface OrderMapper {

    /** 订单总数，用于计算分页 total / total_pages。 */
    long countTotal();

    /**
     * 分页查询订单，按 id 升序返回。
     *
     * @param offset 跳过的行数（(page-1) * pageSize）
     * @param limit  每页条数
     */
    List<Order> selectPage(long offset, int limit);
}