package com.example.flowhub.order.query;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrderCriteria 空值契约单测：
 * 集合字段 null 归一为空集合（空集合 = 无条件）、List.copyOf 防篡改并拒绝 null 元素、
 * isEmpty 只看筛选条件不看排序。
 */
class OrderCriteriaTest {

    @Test
    void nullCollectionsNormalizeToEmpty() {
        OrderCriteria criteria = new OrderCriteria(
                null, null, null, null, null, null, null,
                null, null, null, null, null, SortField.CREATED_AT, SortDirection.DESC);

        assertThat(criteria.ids()).isEmpty();
        assertThat(criteria.excludedIds()).isEmpty();
        assertThat(criteria.statuses()).isEmpty();
        assertThat(criteria.salesChannels()).isEmpty();
        assertThat(criteria.currencies()).isEmpty();
        assertThat(criteria.isEmpty()).isTrue();
    }

    @Test
    void anyConditionMakesItNotEmpty() {
        OrderCriteria byName = new OrderCriteria(
                null, null, null, null, null, "张", null,
                null, null, null, null, null, SortField.CREATED_AT, SortDirection.DESC);
        OrderCriteria byRange = new OrderCriteria(
                null, null, null, null, null, null, null,
                null, new BigDecimal("100"), null, null, null, SortField.CREATED_AT, SortDirection.DESC);
        OrderCriteria byTime = new OrderCriteria(
                null, null, null, null, null, null, null,
                null, null, null, LocalDateTime.of(2026, 1, 1, 0, 0), null,
                SortField.CREATED_AT, SortDirection.DESC);
        OrderCriteria byExcluded = new OrderCriteria(
                null, List.of(1L), null, null, null, null, null,
                null, null, null, null, null, SortField.CREATED_AT, SortDirection.DESC);

        assertThat(byName.isEmpty()).isFalse();
        assertThat(byRange.isEmpty()).isFalse();
        assertThat(byTime.isEmpty()).isFalse();
        assertThat(byExcluded.isEmpty()).isFalse();
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        List<String> statuses = new ArrayList<>();
        statuses.add("PAID");
        OrderCriteria criteria = new OrderCriteria(
                null, null, statuses, null, null, null, null,
                null, null, null, null, null, SortField.CREATED_AT, SortDirection.DESC);

        // 构造后修改源集合不影响值对象（不可变语义）
        statuses.add("HACKED");
        assertThat(criteria.statuses()).containsExactly("PAID");
        assertThatThrownBy(() -> criteria.statuses().add("SHIPPED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullCollectionElementsAreRejected() {
        // List.copyOf 拒绝 null 元素，快速失败而非留下隐患
        assertThatThrownBy(() -> new OrderCriteria(
                null, null, List.of("PAID", null), null, null, null, null,
                null, null, null, null, null, SortField.CREATED_AT, SortDirection.DESC))
                .isInstanceOf(NullPointerException.class);
    }
}
