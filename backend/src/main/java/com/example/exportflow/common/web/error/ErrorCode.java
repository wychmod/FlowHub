package com.example.exportflow.common.web.error;

/** 业务错误码与 HTTP 状态映射，见 be-td.md 4.11（骨架版仅保留最常用项）。 */
public enum ErrorCode {

    VALIDATION_ERROR(400, "请求参数不合法"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务内部错误");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
