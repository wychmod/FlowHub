package com.example.flowhub.export.error;

import com.example.flowhub.common.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 导出模块业务错误码（见 export-http-boundary-plan.md 第 5.3 节）。
 * <p>
 * DTO 结构错误统一走 {@link com.example.flowhub.common.web.error.CommonErrorCode#VALIDATION_ERROR}，
 * 不与本枚举混用。
 */
public enum ExportErrorCode implements ErrorCode {

    EXPORT_COLUMNS_INVALID("EXPORT_COLUMNS_INVALID", "导出列不合法", HttpStatus.BAD_REQUEST),
    EXPORT_SELECTED_EMPTY("EXPORT_SELECTED_EMPTY", "勾选导出至少选择一个订单", HttpStatus.BAD_REQUEST),
    EXPORT_SELECTED_TOO_MANY("EXPORT_SELECTED_TOO_MANY", "勾选导出订单数超过上限 1000", HttpStatus.BAD_REQUEST),
    EXPORT_SELECTION_EMPTY("EXPORT_SELECTION_EMPTY", "勾选的订单均不存在", HttpStatus.BAD_REQUEST),
    EXPORT_FILTER_ZERO_ROWS("EXPORT_FILTER_ZERO_ROWS", "筛选条件未命中任何订单", HttpStatus.BAD_REQUEST),
    EXPORT_FILTER_TOO_MANY_ROWS("EXPORT_FILTER_TOO_MANY_ROWS", "筛选命中订单数超过上限", HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "相同幂等键的请求内容不一致", HttpStatus.CONFLICT),
    EXPORT_JOB_NOT_FOUND("EXPORT_JOB_NOT_FOUND", "导出任务不存在", HttpStatus.NOT_FOUND),
    EXPORT_JOB_NOT_DOWNLOADABLE("EXPORT_JOB_NOT_DOWNLOADABLE", "导出任务尚未完成，无法下载", HttpStatus.CONFLICT),
    EXPORT_FILE_EXPIRED("EXPORT_FILE_EXPIRED", "导出文件已过期，请重新创建导出任务", HttpStatus.GONE),
    EXPORT_FILE_MISSING("EXPORT_FILE_MISSING", "导出文件不存在或已被清理，请重新创建导出任务", HttpStatus.NOT_FOUND),
    EXPORT_PATH_INVALID("EXPORT_PATH_INVALID", "导出文件路径不合法，请重新创建导出任务", HttpStatus.NOT_FOUND),
    EXPORT_JOB_NOT_RETRYABLE("EXPORT_JOB_NOT_RETRYABLE", "导出任务不可重试（仅失败任务可重试，且总执行次数未达上限）", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ExportErrorCode(String code, String message, HttpStatus httpStatus) {
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
