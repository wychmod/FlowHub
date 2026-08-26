package com.example.exportflow.common.web.api;

import com.example.exportflow.common.web.trace.TraceIdSupport;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 统一响应 Envelope，格式见 be-td.md 4.2。
 *
 * <pre>{@code
 * {
 *   "code": "SUCCESS",
 *   "message": null,
 *   "data": { },
 *   "trace_id": "abc123"
 * }
 * }</pre>
 */
public record ApiResponse<T>(String code, String message, T data, @JsonProperty("trace_id") String traceId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", null, data, TraceIdSupport.currentTraceId());
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(code, message, null, TraceIdSupport.currentTraceId());
    }
}
