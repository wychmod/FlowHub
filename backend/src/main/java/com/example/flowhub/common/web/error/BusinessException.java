package com.example.flowhub.common.web.error;

/**
 * 业务异常：携带错误码与可选详情，由 {@link GlobalExceptionHandler} 统一转换为 HTTP 响应。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    /**
     * @param errorCode 错误码（决定 HTTP 状态与兜底文案）
     * @param message   具体错误详情，null 时回落到错误码默认文案
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 快捷工厂：入参校验失败（VALIDATION_ERROR，HTTP 400），携带具体文案。 */
    public static BusinessException validation(String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_ERROR, message);
    }
}
