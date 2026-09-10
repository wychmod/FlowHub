package com.example.flowhub.common.web.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ExceptionUtils#messageOrTypeName} 语义：message 优先，空白/缺失回退异常类名。 */
class ExceptionUtilsTest {

    @Test
    void messageOrTypeName_有消息时原样返回() {
        assertThat(ExceptionUtils.messageOrTypeName(new IllegalStateException("boom")))
                .isEqualTo("boom");
    }

    @Test
    void messageOrTypeName_无消息回退类名() {
        assertThat(ExceptionUtils.messageOrTypeName(new NullPointerException()))
                .isEqualTo("NullPointerException");
    }

    @Test
    void messageOrTypeName_空白消息回退类名() {
        assertThat(ExceptionUtils.messageOrTypeName(new IOException("   ")))
                .isEqualTo("IOException");
        assertThat(ExceptionUtils.messageOrTypeName(new IOException("")))
                .isEqualTo("IOException");
    }
}
