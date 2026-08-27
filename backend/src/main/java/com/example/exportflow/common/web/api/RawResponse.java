package com.example.exportflow.common.web.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口返回原始响应，跳过 {@link ApiResponseAdvice} 的统一 Envelope 包装。
 *
 * <p>可标注在控制器类、方法或返回类型上，用于流式、文件下载等需要保持原生
 * HTTP 响应（如 {@code Resource}、SSE）的场景，避免被包装为 {@code ApiResponse}。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RawResponse {
}