package com.example.exportflow.common.web.error;

import com.example.exportflow.common.web.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理。
 * <p>
 * 将业务异常、参数校验异常和兜底异常统一转换为错误 Envelope。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：按照错误码映射的 HTTP 状态返回。
     *
     * @param ex 业务异常
     * @return 统一错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.failure(errorCode.code(), errorCode.message()));
    }

    /**
     * Query 参数校验失败（例如 @Min/@Max）。
     *
     * @param ex 校验异常
     * @return 统一错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse(CommonErrorCode.VALIDATION_ERROR.message());
        return badRequest(message);
    }

    /**
     * Query 参数类型不匹配，例如 page=abc。
     *
     * @param ex 类型转换异常
     * @return 统一错误响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数 " + ex.getName() + " 类型不正确");
    }

    /**
     * 兜底异常：记录日志并返回内部错误。
     *
     * @param ex 未处理异常
     * @return 统一错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR.code(), CommonErrorCode.INTERNAL_ERROR.message()));
    }

    /**
     * 构造 400 类错误响应。
     *
     * @param message 错误提示
     * @return 统一错误响应
     */
    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.status(CommonErrorCode.VALIDATION_ERROR.httpStatus())
                .body(ApiResponse.failure(CommonErrorCode.VALIDATION_ERROR.code(), message));
    }
}
