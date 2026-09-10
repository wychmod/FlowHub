package com.example.flowhub.common.web.error;

import org.springframework.http.HttpStatus;

/**
 * 通用错误码。
 * <p>
 * 当前仓库保留最小集合，后续业务模块可继续按需新增各自的错误码枚举并实现 {@link ErrorCode}。
 */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_ERROR("VALIDATION_ERROR", "请求参数不合法", HttpStatus.BAD_REQUEST),
    NOT_FOUND("NOT_FOUND", "资源不存在", HttpStatus.NOT_FOUND),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    CommonErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
