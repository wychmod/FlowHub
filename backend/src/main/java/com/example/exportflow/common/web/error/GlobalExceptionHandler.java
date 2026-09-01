package com.example.exportflow.common.web.error;

import com.example.exportflow.common.web.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：将业务异常、校验异常、兜底异常统一转换为错误 Envelope。
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

    /** Query 参数校验失败（@Min/@Max 等）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse(CommonErrorCode.VALIDATION_ERROR.message());
        return badRequest(message);
    }

    /** 参数类型不匹配（如 page=abc）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数 " + ex.getName() + " 类型不正确");
    }

    /** 请求对象绑定/校验失败。 */
    @ExceptionHandler({BindException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiResponse<Void>> handleBindException(Exception ex) {
        return badRequest(CommonErrorCode.VALIDATION_ERROR.message());
    }

    /** 兜底异常：记录日志并返回 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR.code(), CommonErrorCode.INTERNAL_ERROR.message()));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.status(CommonErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ApiResponse.failure(CommonErrorCode.VALIDATION_ERROR.code(), message));
    }
}
