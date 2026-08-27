package com.example.exportflow.order.mapper;

import com.example.exportflow.order.entity.Order;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 订单 Mapper 的内存 Mock 实现（骨架期占位）。
 *
 * <p>在接入 MySQL 前，用启动时生成的固定数据模拟持久层，保证订单接口可调用、分页正确。
 * 字段循环取值逻辑刻意与后续 SQL 生成脚本保持一致，便于无感切换为真实实现。
 */
@Repository
public class InMemoryOrderMapper implements OrderMapper {

    /** Mock 订单总数，先打通链路，后续切换为 11 万条演示数据。 */
    static final int MOCK_TOTAL = 57;

    private static final List<String> STATUSES = List.of("PENDING", "PAID", "SHIPPED", "COMPLETED", "CANCELED");
    private static final List<String> CHANNELS = List.of("WEB", "APP", "STORE", "PARTNER");
    private static final List<String> CUSTOMERS = List.of("张三", "李四", "王五", "赵六", "陈七", "周八", "吴九", "郑十");

    private final List<Order> mockOrders = buildMockOrders();

    @Override
    public long countTotal() {
        return mockOrders.size();
    }

    @Override
    public List<Order> selectPage(long offset, int limit) {
        int fromIndex = (int) Math.min(offset, mockOrders.size());
        int toIndex = Math.min(fromIndex + limit, mockOrders.size());
        return List.copyOf(mockOrders.subList(fromIndex, toIndex));
    }

    private static List<Order> buildMockOrders() {
        LocalDateTime baseTime = LocalDateTime.of(2026, 8, 1, 8, 30, 0);
        return IntStream.rangeClosed(1, MOCK_TOTAL)
                .mapToObj(i -> new Order(
                        (long) i,
                        "PERF-%06d".formatted(i),
                        STATUSES.get(i % STATUSES.size()),
                        CHANNELS.get(i % CHANNELS.size()),
                        CUSTOMERS.get(i % CUSTOMERS.size()),
                        BigDecimal.valueOf(19.9 + i * 17.53).setScale(2, RoundingMode.HALF_UP),
                        "CNY",
                        baseTime.plusMinutes(37L * i)))
                .toList();
    }
}