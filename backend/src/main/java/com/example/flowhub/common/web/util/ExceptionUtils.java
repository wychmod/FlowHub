package com.example.flowhub.common.web.util;

/**
 * 异常描述工具。
 */
public final class ExceptionUtils {

    private ExceptionUtils() {
    }

    /** 取异常可读描述：message 空白时回退异常类名，保证日志/落库的失败原因永不为空。 */
    public static String messageOrTypeName(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
