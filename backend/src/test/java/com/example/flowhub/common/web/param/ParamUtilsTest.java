package com.example.flowhub.common.web.param;

import com.example.flowhub.common.web.error.BusinessException;
import com.example.flowhub.common.web.error.CommonErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ParamUtils} 单元测试：覆盖空值契约（null / 空串 / 纯空白 / 空 token /
 * 重复 token）与枚举、数值、时间、手机号、多值枚举解析的正常、异常路径（见 docs/order-query-design.md 第七节）。
 */
class ParamUtilsTest {

    // ==================== trimToNull ====================

    @Test
    void trimToNull_null返回null() {
        assertThat(ParamUtils.trimToNull(null)).isNull();
    }

    @Test
    void trimToNull_空串与纯空白统一折叠为null() {
        assertThat(ParamUtils.trimToNull("")).isNull();
        assertThat(ParamUtils.trimToNull("   ")).isNull();
        assertThat(ParamUtils.trimToNull("\t\n ")).isNull();
    }

    @Test
    void trimToNull_去除首尾空白并保留中间内容() {
        assertThat(ParamUtils.trimToNull("  PAID, SHIPPED  ")).isEqualTo("PAID, SHIPPED");
        assertThat(ParamUtils.trimToNull("0")).isEqualTo("0");
    }

    // ==================== escapeLike ====================

    @Test
    void escapeLike_null原样返回() {
        assertThat(ParamUtils.escapeLike(null)).isNull();
    }

    @Test
    void escapeLike_转义反斜杠与通配符() {
        assertThat(ParamUtils.escapeLike("EF%2026")).isEqualTo("EF\\%2026");
        assertThat(ParamUtils.escapeLike("order_1")).isEqualTo("order\\_1");
        assertThat(ParamUtils.escapeLike("a\\b")).isEqualTo("a\\\\b");
        assertThat(ParamUtils.escapeLike("EF2026-001")).isEqualTo("EF2026-001");
    }

    // ==================== splitMultiValue ====================

    @Test
    void splitMultiValue_未传与空白输入返回空列表() {
        assertThat(ParamUtils.splitMultiValue(null)).isEmpty();
        assertThat(ParamUtils.splitMultiValue("")).isEmpty();
        assertThat(ParamUtils.splitMultiValue("   ")).isEmpty();
    }

    @Test
    void splitMultiValue_拆分trim并过滤空token() {
        assertThat(ParamUtils.splitMultiValue("PAID, SHIPPED,,COMPLETED")).isEqualTo(
                List.of("PAID", "SHIPPED", "COMPLETED"));
    }

    @Test
    void splitMultiValue_全为空token视为未传() {
        assertThat(ParamUtils.splitMultiValue(" , , ")).isEmpty();
    }

    @Test
    void splitMultiValue_仅做去重不做大小写归一() {
        // 大写化属于业务语义（白名单枚举），由调用方追加；本方法只去重
        assertThat(ParamUtils.splitMultiValue("pa,PA")).isEqualTo(List.of("pa", "PA"));
        assertThat(ParamUtils.splitMultiValue("PA,PA")).isEqualTo(List.of("PA"));
    }

    // ==================== enumFromName ====================

    @Test
    void enumFromName_大小写不敏感解析成功() {
        assertThat(ParamUtils.enumFromName("asc", SortDirectionForTest.class))
                .isEqualTo(SortDirectionForTest.ASC);
        assertThat(ParamUtils.enumFromName(" DESC ", SortDirectionForTest.class))
                .isEqualTo(SortDirectionForTest.DESC);
    }

    @Test
    void enumFromName_未识别值返回null() {
        assertThat(ParamUtils.enumFromName("invalid", SortDirectionForTest.class)).isNull();
        assertThat(ParamUtils.enumFromName("", SortDirectionForTest.class)).isNull();
        assertThat(ParamUtils.enumFromName("   ", SortDirectionForTest.class)).isNull();
        assertThat(ParamUtils.enumFromName(null, SortDirectionForTest.class)).isNull();
    }

    /** 测试用枚举：模拟真实白名单枚举（大写常量名）。 */
    private enum SortDirectionForTest {
        ASC, DESC
    }

    // ==================== parseDecimal ====================

    @Test
    void parseDecimal_null与空白返回null() {
        assertThat(ParamUtils.parseDecimal(null, "total_amount_min")).isNull();
        assertThat(ParamUtils.parseDecimal("   ", "total_amount_min")).isNull();
    }

    @Test
    void parseDecimal_合法值trim后解析() {
        assertThat(ParamUtils.parseDecimal("  12.50 ", "total_amount_min"))
                .isEqualByComparingTo(new BigDecimal("12.50"));
    }

    @Test
    void parseDecimal_非法数值抛400() {
        assertThatThrownBy(() -> ParamUtils.parseDecimal("abc", "total_amount_min"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getMessage()).contains("total_amount_min");
                });
    }

    // ==================== parseDateTime ====================

    @Test
    void parseDateTime_null与空白返回null() {
        assertThat(ParamUtils.parseDateTime(null, "created_at_begin")).isNull();
        assertThat(ParamUtils.parseDateTime("   ", "created_at_begin")).isNull();
    }

    @Test
    void parseDateTime_秒级与毫秒级均支持() {
        assertThat(ParamUtils.parseDateTime("2024-05-01T10:30:00", "created_at_begin"))
                .isEqualTo(LocalDateTime.of(2024, 5, 1, 10, 30, 0));
        assertThat(ParamUtils.parseDateTime("2024-05-01T10:30:00.123", "created_at_begin"))
                .isEqualTo(LocalDateTime.of(2024, 5, 1, 10, 30, 0, 123_000_000));
    }

    @Test
    void parseDateTime_非法格式抛400() {
        assertThatThrownBy(() -> ParamUtils.parseDateTime("2024-05-01 10:30:00", "created_at_begin"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getMessage()).contains("created_at_begin");
                });
    }

    // ==================== parsePhone ====================

    @Test
    void parsePhone_null与空白返回null() {
        assertThat(ParamUtils.parsePhone(null, "customer_phone")).isNull();
        assertThat(ParamUtils.parsePhone("   ", "customer_phone")).isNull();
    }

    @Test
    void parsePhone_合法11位数字trim后返回() {
        assertThat(ParamUtils.parsePhone("  13800138000 ", "customer_phone")).isEqualTo("13800138000");
    }

    @Test
    void parsePhone_位数或字符非法抛400() {
        assertThatThrownBy(() -> ParamUtils.parsePhone("1380013800", "customer_phone"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ParamUtils.parsePhone("138001380001", "customer_phone"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ParamUtils.parsePhone("138-0013-8000", "customer_phone"))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== parseMultiEnum ====================

    @Test
    void parseMultiEnum_未传与空白输入返回空列表() {
        assertThat(ParamUtils.parseMultiEnum((String) null, List.of("PAID"), "order_status")).isEmpty();
        assertThat(ParamUtils.parseMultiEnum("   ", List.of("PAID"), "order_status")).isEmpty();
        assertThat(ParamUtils.parseMultiEnum(" , ", List.of("PAID"), "order_status")).isEmpty();
    }

    @Test
    void parseMultiEnum_拆分大写归一并去重() {
        assertThat(ParamUtils.parseMultiEnum("paid, PENDING,,paid", List.of("PAID", "PENDING"), "order_status"))
                .isEqualTo(List.of("PAID", "PENDING"));
    }

    @Test
    void parseMultiEnum_未命中白名单抛400() {
        assertThatThrownBy(() -> ParamUtils.parseMultiEnum("PAID,REFUNDED", List.of("PAID"), "order_status"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getMessage()).contains("order_status", "REFUNDED");
                });
    }

    @Test
    void parseMultiEnumList_未传与空白项返回空列表() {
        assertThat(ParamUtils.parseMultiEnum((List<String>) null, List.of("PAID"), "order_status")).isEmpty();
        assertThat(ParamUtils.parseMultiEnum(List.of("  ", ""), List.of("PAID"), "order_status")).isEmpty();
    }

    @Test
    void parseMultiEnumList_大写归一剔空白并去重() {
        // Arrays.asList 允许 null 元素，模拟真实入参中的 null 项
        assertThat(ParamUtils.parseMultiEnum(Arrays.asList("paid", " PENDING", null, "paid"),
                List.of("PAID", "PENDING"), "order_status"))
                .isEqualTo(List.of("PAID", "PENDING"));
    }

    @Test
    void parseMultiEnumList_未命中白名单抛400() {
        assertThatThrownBy(() -> ParamUtils.parseMultiEnum(List.of("PAID", "FOO"), List.of("PAID"), "order_status"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getMessage()).contains("order_status", "FOO");
                });
    }
}
