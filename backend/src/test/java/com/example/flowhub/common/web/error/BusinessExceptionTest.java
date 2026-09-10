package com.example.flowhub.common.web.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link BusinessException#validation} 工厂语义：固定 VALIDATION_ERROR 错误码并携带具体文案。 */
class BusinessExceptionTest {

    @Test
    void validation_固定VALIDATION_ERROR并携带文案() {
        BusinessException ex = BusinessException.validation("total_amount_min 不能大于 total_amount_max");
        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR);
        assertThat(ex.getMessage()).isEqualTo("total_amount_min 不能大于 total_amount_max");
    }
}
