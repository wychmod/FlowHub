package com.example.flowhub.order.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排序白名单枚举解析单测：字段/方向的大小写不敏感解析、白名单外返回 null、
 * 列名映射与默认方向。
 */
class SortFieldTest {

    @Test
    void fromNameIsCaseInsensitive() {
        assertThat(SortField.fromName("created_at")).isEqualTo(SortField.CREATED_AT);
        assertThat(SortField.fromName("CREATED_AT")).isEqualTo(SortField.CREATED_AT);
        assertThat(SortField.fromName(" Total_Amount ")).isEqualTo(SortField.TOTAL_AMOUNT);
        assertThat(SortField.fromName("Order_No")).isEqualTo(SortField.ORDER_NO);
        assertThat(SortField.fromName("id")).isEqualTo(SortField.ID);
    }

    @Test
    void fromNameReturnsNullOutsideWhitelist() {
        // 白名单外（含可排序性被否决的字段与 SQL 注入尝试）一律 null，由 Service 转 400
        assertThat(SortField.fromName("customer_name")).isNull();
        assertThat(SortField.fromName("status")).isNull();
        assertThat(SortField.fromName("1=1; DROP TABLE orders")).isNull();
        assertThat(SortField.fromName("")).isNull();
    }

    @Test
    void columnMappingMatchesSchema() {
        assertThat(SortField.CREATED_AT.column()).isEqualTo("created_at");
        assertThat(SortField.TOTAL_AMOUNT.column()).isEqualTo("total_amount");
        assertThat(SortField.ORDER_NO.column()).isEqualTo("order_no");
        assertThat(SortField.ID.column()).isEqualTo("id");
    }

    @Test
    void defaultDirectionPerField() {
        assertThat(SortField.CREATED_AT.defaultDirection()).isEqualTo(SortDirection.DESC);
        assertThat(SortField.TOTAL_AMOUNT.defaultDirection()).isEqualTo(SortDirection.DESC);
        assertThat(SortField.ORDER_NO.defaultDirection()).isEqualTo(SortDirection.ASC);
        assertThat(SortField.ID.defaultDirection()).isEqualTo(SortDirection.ASC);
    }

    @Test
    void directionParsing() {
        assertThat(SortDirection.fromName("asc")).isEqualTo(SortDirection.ASC);
        assertThat(SortDirection.fromName("DESC")).isEqualTo(SortDirection.DESC);
        assertThat(SortDirection.fromName(" desc ")).isEqualTo(SortDirection.DESC);
        assertThat(SortDirection.fromName("sideways")).isNull();
        assertThat(SortDirection.fromName("")).isNull();
    }
}
