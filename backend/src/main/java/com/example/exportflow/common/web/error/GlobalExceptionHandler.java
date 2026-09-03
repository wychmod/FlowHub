package com.example.exportflow.common.web.error;

import com.example.exportflow.common.web.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：将业务异常、校验异常、兜底异常统一转换为错误 Envelope。
 * <p>
 * Bean Validation 失败时在 data.field_errors 输出「字段路径 → 文案」的对象映射。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常，按错误码映射 HTTP 状态。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        String message = ex.getMessage() != null ? ex.getMessage() : errorCode.message();
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.failure(errorCode.code(), message));
    }

    /** Query/Header 参数校验失败（@Min/@Max 等），field 路径取属性路径末段。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<FieldErrorData>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fieldErrors.putIfAbsent(field, violation.getMessage());
        });
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse(CommonErrorCode.VALIDATION_ERROR.message());
        return badRequest(message, fieldErrors);
    }

    /** 参数类型不匹配（如 page=abc）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(CommonErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ApiResponse.failure(CommonErrorCode.VALIDATION_ERROR.code(), "参数 " + ex.getName() + " 类型不正确"));
    }

    /** 请求体 JSON 解析失败（缺少 HttpMessageNotReadableException 处理时会落到 500 兜底）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(CommonErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ApiResponse.failure(CommonErrorCode.VALIDATION_ERROR.code(), "请求体不是合法的 JSON"));
    }

    /** 请求对象绑定/校验失败，收集全部字段错误与类级错误。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<FieldErrorData>> handleBindException(BindException ex) {
        return badRequest(CommonErrorCode.VALIDATION_ERROR.message(), toFieldErrors(ex.getBindingResult()));
    }

    /** 兜底异常：记录日志并返回 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR.code(), CommonErrorCode.INTERNAL_ERROR.message()));
    }

    /** 汇总字段错误（同字段多条以「; 」拼接）与类级错误（归入对象名键）。 */
    private static Map<String, String> toFieldErrors(BindingResult bindingResult) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        bindingResult.getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        bindingResult.getGlobalErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        return fieldErrors;
    }

    private ResponseEntity<ApiResponse<FieldErrorData>> badRequest(String message, Map<String, String> fieldErrors) {
        return ResponseEntity.status(CommonErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ApiResponse.failure(CommonErrorCode.VALIDATION_ERROR.code(), message, new FieldErrorData(fieldErrors)));
    }
}
