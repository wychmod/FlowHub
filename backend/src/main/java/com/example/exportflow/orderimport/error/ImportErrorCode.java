package com.example.exportflow.orderimport.error;

import com.example.exportflow.common.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 导入模块业务错误码（docs/order-import-design.md §5）。
 * <p>
 * DTO 结构错误统一走 {@link com.example.exportflow.common.web.error.CommonErrorCode#VALIDATION_ERROR}，
 * 不与本枚举混用；结构级校验失败也归本枚举（既然与重新创建任务强相关）。
 */
public enum ImportErrorCode implements ErrorCode {

    IMPORT_FILE_TOO_LARGE("IMPORT_FILE_TOO_LARGE", "上传文件超过 10MB 大小上限", HttpStatus.BAD_REQUEST),
    IMPORT_FORMAT_NOT_SUPPORTED("IMPORT_FORMAT_NOT_SUPPORTED", "仅支持 .xlsx 格式，请使用导出文件或模板", HttpStatus.BAD_REQUEST),
    IMPORT_FILE_CORRUPTED("IMPORT_FILE_CORRUPTED", "文件已损坏或不是合法的 .xlsx（ZIP）文件", HttpStatus.BAD_REQUEST),
    IMPORT_TEMPLATE_MISMATCH("IMPORT_TEMPLATE_MISMATCH", "模板不匹配：表头必须与导出格式完全一致", HttpStatus.BAD_REQUEST),
    IMPORT_EMPTY_FILE("IMPORT_EMPTY_FILE", "文件不含任何数据行（仅有表头）", HttpStatus.BAD_REQUEST),
    IMPORT_TOO_MANY_ROWS("IMPORT_TOO_MANY_ROWS", "数据行数超过导入上限", HttpStatus.BAD_REQUEST),
    IMPORT_JOB_NOT_FOUND("IMPORT_JOB_NOT_FOUND", "导入任务不存在", HttpStatus.NOT_FOUND),
    IMPORT_JOB_NOT_RETRYABLE("IMPORT_JOB_NOT_RETRYABLE", "导入任务不可重试（仅失败任务可重试，且总执行次数未达上限）", HttpStatus.CONFLICT),
    IMPORT_ERROR_REPORT_NOT_AVAILABLE("IMPORT_ERROR_REPORT_NOT_AVAILABLE", "导入任务无错误报告（仅部分成功任务可下载）", HttpStatus.CONFLICT),
    IMPORT_PATH_INVALID("IMPORT_PATH_INVALID", "导入文件路径不合法", HttpStatus.NOT_FOUND),
    IMPORT_FILE_MISSING("IMPORT_FILE_MISSING", "导入文件不存在或已被清理", HttpStatus.NOT_FOUND);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ImportErrorCode(String code, String message, HttpStatus httpStatus) {
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