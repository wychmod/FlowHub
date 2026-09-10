package com.example.flowhub.order.service;

import com.example.flowhub.common.web.error.BusinessException;
import com.example.flowhub.order.dto.OrderPageResp;
import com.example.flowhub.order.dto.OrderRequest;
import com.example.flowhub.order.mapper.TestOrderDataSeeder;
import com.example.flowhub.order.vo.OrderItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrderService 归一化与空值防御单测（用例清单见 docs/order-query-design.md 第七节第 8 条）：
 * 全条件为 null、空白串、空集合、区间单端、空白 sort_by/sort_order、各种非法格式。
 * <p>
 * 走真实 MyBatis + H2，由 TestOrderDataSeeder 预置 57 行确定性数据。
 */
@SpringBootTest
class OrderServiceTest {

    @Autowired
    private OrderService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        TestOrderDataSeeder.seed(jdbcTemplate);
    }

    /** 全部入参为 null：走默认值（page=1、pageSize=20、created_at,desc），等价无条件查询。 */
    @Test
    void allNullParamsFallBackToDefaults() {
        OrderPageResp resp = service.listOrders(builder().build());

        assertThat(resp.page()).isEqualTo(1);
        assertThat(resp.pageSize()).isEqualTo(20);
        assertThat(resp.total()).isEqualTo(57);
        assertThat(resp.totalPages()).isEqualTo(3);
        assertThat(resp.sortBy()).isEqualTo("created_at");
        assertThat(resp.sortOrder()).isEqualTo("desc");
        assertThat(resp.items()).hasSize(20);
        // 默认 created_at 倒序：id 越大时间越晚
        assertThat(resp.items().get(0).orderNo()).isEqualTo("EF2026-00000057");
    }

    /** 全部筛选字段为空白串：trimToNull 归一后等价未传。 */
    @Test
    void whitespaceParamsTreatedAsAbsent() {
        OrderRequest request = builder()
                .orderStatus("   ").salesChannel("  ").currency(" ")
                .customerName("   ").orderNo("  ").customerPhone("  ")
                .totalAmountMin("  ").totalAmountMax("  ")
                .createdAtBegin("  ").createdAtEnd("  ").sortBy("  ").sortOrder("  ")
                .build();

        OrderPageResp resp = service.listOrders(request);

        assertThat(resp.total()).isEqualTo(57);
        assertThat(resp.sortBy()).isEqualTo("created_at");
        assertThat(resp.sortOrder()).isEqualTo("desc");
    }

    /** IN 多值为空串 / 纯逗号：拆分过滤空 token 后为空集合，视为未传。 */
    @Test
    void emptyMultiValueTreatedAsAbsent() {
        OrderPageResp resp = service.listOrders(builder().orderStatus(" , , ").build());

        assertThat(resp.total()).isEqualTo(57);
    }

    /** 区间只传单端：另一端为 null 放行，不做跨端比较。 */
    @Test
    void singleEndRangeIsAllowed() {
        OrderPageResp byAmount = service.listOrders(builder().totalAmountMin("1000").build());
        assertThat(byAmount.total()).isEqualTo(41);

        OrderPageResp byTime = service.listOrders(builder().createdAtEnd("2025-09-02T00:00:00").build());
        assertThat(byTime.total()).isEqualTo(8);
    }

    /** 时间参数容许毫秒变体；边界含 begin、不含 end（左闭右开）。 */
    @Test
    void timeParsesMillisVariantAndHalfOpenBoundary() {
        OrderPageResp resp = service.listOrders(builder()
                .createdAtBegin("2025-09-01T00:00:00.000")
                .createdAtEnd("2025-09-02T00:00:00.000")
                .build());

        assertThat(resp.total()).isEqualTo(8);
    }

    /** 金额区间边界含等值（闭区间）：min=max=123.03 精确命中 n=2。 */
    @Test
    void amountRangeIsInclusive() {
        OrderPageResp resp = service.listOrders(builder()
                .totalAmountMin("123.03").totalAmountMax("123.03")
                .build());

        assertThat(resp.total()).isEqualTo(1);
        assertThat(resp.items().get(0).orderNo()).isEqualTo("EF2026-00000002");
    }

    /** 姓名模糊筛选。 */
    @Test
    void customerNameLikeFilter() {
        OrderPageResp resp = service.listOrders(builder().customerName("张").build());

        assertThat(resp.total()).isEqualTo(4);
        assertThat(resp.items()).allSatisfy(item ->
                assertThat(item.customerName()).contains("张"));
    }

    /** 渠道筛选。 */
    @Test
    void salesChannelFilter() {
        OrderPageResp resp = service.listOrders(builder().salesChannel("WEB").build());

        assertThat(resp.total()).isEqualTo(26);
    }

    /** 币种筛选。 */
    @Test
    void currencyFilter() {
        OrderPageResp resp = service.listOrders(builder().currency("USD").build());

        assertThat(resp.total()).isEqualTo(1);
        assertThat(resp.items().get(0).currency()).isEqualTo("USD");
    }

    /** sort_by 只传字段名：使用该字段的默认方向（order_no -> asc）。 */
    @Test
    void sortOnlyFieldUsesFieldDefaultDirection() {
        OrderPageResp resp = service.listOrders(builder().sortBy("order_no").build());

        assertThat(resp.sortBy()).isEqualTo("order_no");
        assertThat(resp.sortOrder()).isEqualTo("asc");
        assertThat(resp.items().get(0).orderNo()).isEqualTo("EF2026-00000001");
    }

    /** sort_by/sort_order 大小写不敏感、容忍空白。 */
    @Test
    void sortIsCaseInsensitiveAndTrimmed() {
        OrderPageResp resp = service.listOrders(builder()
                .sortBy(" TOTAL_AMOUNT ").sortOrder(" ASC ").build());

        assertThat(resp.sortBy()).isEqualTo("total_amount");
        assertThat(resp.sortOrder()).isEqualTo("asc");
        assertThat(resp.items().get(0).orderNo()).isEqualTo("EF2026-00000001");
    }

    /** 金额升序结果非降（验证排序语义与 BigDecimal.compareTo）。 */
    @Test
    void amountSortIsAscending() {
        OrderPageResp resp = service.listOrders(builder()
                .sortBy("total_amount").sortOrder("asc").build());

        assertThat(resp.items())
                .extracting(OrderItemVO::totalAmount)
                .isSorted();
    }

    /** 越界页返回空列表而非 null / 报错。 */
    @Test
    void pageBeyondRangeReturnsEmptyList() {
        OrderPageResp resp = service.listOrders(builder().page(999).build());

        assertThat(resp.total()).isEqualTo(57);
        assertThat(resp.items()).isEmpty();
    }

    @Test
    void rejectInvalidEnumValue() {
        assertThatThrownBy(() -> service.listOrders(builder().orderStatus("PAID,FOO").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("order_status")
                .extracting(ex -> ((BusinessException) ex).getErrorCode().code())
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void rejectInvalidPhone() {
        assertThatThrownBy(() -> service.listOrders(builder().customerPhone("12345678901x").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("customer_phone");
    }

    @Test
    void rejectNonNumericAmount() {
        assertThatThrownBy(() -> service.listOrders(builder().totalAmountMin("12.3.4").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("total_amount_min");
    }

    @Test
    void rejectInvalidTimeFormat() {
        assertThatThrownBy(() -> service.listOrders(builder().createdAtBegin("2026-01-01").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("created_at_begin");
    }

    @Test
    void rejectReversedAmountRange() {
        assertThatThrownBy(() -> service.listOrders(builder()
                .totalAmountMin("500").totalAmountMax("100").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("total_amount_min");
    }

    @Test
    void rejectEqualTimeRange() {
        assertThatThrownBy(() -> service.listOrders(builder()
                .createdAtBegin("2026-01-01T00:00:00").createdAtEnd("2026-01-01T00:00:00").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("created_at_begin");
    }

    @Test
    void rejectSortFieldOutsideWhitelist() {
        assertThatThrownBy(() -> service.listOrders(builder().sortBy("customer_name").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("排序字段");
    }

    @Test
    void rejectInvalidSortDirection() {
        assertThatThrownBy(() -> service.listOrders(builder().sortBy("id").sortOrder("sideways").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("排序方向");
    }

    /** sort_order 脱离 sort_by 单独出现：400。 */
    @Test
    void rejectSortOrderWithoutSortBy() {
        assertThatThrownBy(() -> service.listOrders(builder().sortOrder("asc").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sort_order");
    }

    // ==================== 测试辅助 ====================

    private static RequestBuilder builder() {
        return new RequestBuilder();
    }

    private static final class RequestBuilder {
        private Integer page;
        private String orderStatus;
        private String salesChannel;
        private String currency;
        private String customerName;
        private String orderNo;
        private String customerPhone;
        private String totalAmountMin;
        private String totalAmountMax;
        private String createdAtBegin;
        private String createdAtEnd;
        private String sortBy;
        private String sortOrder;

        RequestBuilder page(int page) {
            this.page = page;
            return this;
        }

        RequestBuilder orderStatus(String orderStatus) {
            this.orderStatus = orderStatus;
            return this;
        }

        RequestBuilder salesChannel(String salesChannel) {
            this.salesChannel = salesChannel;
            return this;
        }

        RequestBuilder currency(String currency) {
            this.currency = currency;
            return this;
        }

        RequestBuilder customerName(String customerName) {
            this.customerName = customerName;
            return this;
        }

        RequestBuilder orderNo(String orderNo) {
            this.orderNo = orderNo;
            return this;
        }

        RequestBuilder customerPhone(String customerPhone) {
            this.customerPhone = customerPhone;
            return this;
        }

        RequestBuilder totalAmountMin(String totalAmountMin) {
            this.totalAmountMin = totalAmountMin;
            return this;
        }

        RequestBuilder totalAmountMax(String totalAmountMax) {
            this.totalAmountMax = totalAmountMax;
            return this;
        }

        RequestBuilder createdAtBegin(String createdAtBegin) {
            this.createdAtBegin = createdAtBegin;
            return this;
        }

        RequestBuilder createdAtEnd(String createdAtEnd) {
            this.createdAtEnd = createdAtEnd;
            return this;
        }

        RequestBuilder sortBy(String sortBy) {
            this.sortBy = sortBy;
            return this;
        }

        RequestBuilder sortOrder(String sortOrder) {
            this.sortOrder = sortOrder;
            return this;
        }

        OrderRequest build() {
            return new OrderRequest(page, null, orderStatus, salesChannel, currency,
                    customerName, orderNo, customerPhone, totalAmountMin, totalAmountMax,
                    createdAtBegin, createdAtEnd, sortBy, sortOrder);
        }
    }
}
