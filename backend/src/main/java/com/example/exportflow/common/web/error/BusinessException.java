package com.example.exportflow.common.web.error;

/** 业务异常，由 {@link GlobalExceptionHandler} 统一转换为错误 Envelope。 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
