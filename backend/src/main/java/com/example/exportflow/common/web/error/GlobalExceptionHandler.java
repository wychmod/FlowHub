package com.example.exportflow.common.web.error;

import com.example.exportflow.common.web.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 全局异常处理：把异常统一转换为错误 Envelope（be-td.md 4.2 / 4.11）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：按错误码映射的 HTTP 状态返回。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(ApiResponse.failure(ex.errorCode().name(), ex.getMessage()));
    }

    /** Query 参数校验失败（@Min/@Max 等注解触发）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse(ErrorCode.VALIDATION_ERROR.defaultMessage());
        return badRequest(message);
    }

    /** Query 参数类型不匹配，如 page=abc。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数 " + ex.getName() + " 类型不正确");
    }

    /** 兜底异常：记录含 trace_id 的日志，返回对前端安全的提示。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.failure(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ApiResponse.failure(ErrorCode.VALIDATION_ERROR.name(), message));
    }
}
