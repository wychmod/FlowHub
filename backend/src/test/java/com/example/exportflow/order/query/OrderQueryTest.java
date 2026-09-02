package com.example.exportflow.order.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrderQuery 防御性校验与分页换算单测（docs/order-query-design.md 第四节、第七节）。
 */
class OrderQueryTest {

    private static OrderCriteria anyCriteria() {
        return new OrderCriteria(
                null, null, null, null, null, null, null,
                null, null, null, null, SortField.CREATED_AT, SortDirection.DESC);
    }

    @Test
    void rejectsNullCriteria() {
        assertThatThrownBy(() -> new OrderQuery(null, 1, 20))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("criteria");
    }

    @Test
    void rejectsPageBelowOne() {
        assertThatThrownBy(() -> new OrderQuery(anyCriteria(), 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
    }

    @Test
    void rejectsPageSizeBelowOne() {
        assertThatThrownBy(() -> new OrderQuery(anyCriteria(), 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize");
    }

    @Test
    void offsetSkipsRowsBeforeCurrentPage() {
        assertThat(new OrderQuery(anyCriteria(), 1, 20).offset()).isZero();
        assertThat(new OrderQuery(anyCriteria(), 3, 20).offset()).isEqualTo(40);
        assertThat(new OrderQuery(anyCriteria(), 2, 100).offset()).isEqualTo(100);
    }
}
