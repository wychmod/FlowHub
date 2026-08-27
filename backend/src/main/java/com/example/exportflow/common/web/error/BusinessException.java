package com.example.exportflow.common.web.error;

/**
 * 业务异常。
 * <p>
 * 业务层只需要抛出带错误码的异常，具体 HTTP 状态和返回文案由 {@link GlobalExceptionHandler} 统一转换。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
