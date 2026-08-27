package com.example.exportflow.common.web.api;

import com.example.exportflow.common.web.trace.TraceIdSupport;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * 统一响应 Envelope 包装 Advice。
 *
 * <p>对返回裸对象的 {@code @RestController} 接口自动包装为 {@link ApiResponse}，
 * 复用现有「控制器返回原始结果、Advice 统一包装」的约定，避免每个控制器手工
 * 调用 {@link ApiResponse#success}。已包装的响应（{@code ApiResponse} 实例）、
 * 流式/文件响应（{@link Resource}、SSE 等）以及标注 {@link RawResponse} 的接口
 * 会被跳过，保持原生响应。
 *
 * <p>包装时还会回写响应头中的 {@code trace_id}，与 {@link TraceIdSupport} 保持一致。
 */
@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.getContainingClass().isAnnotationPresent(RestController.class)
                && !returnType.hasMethodAnnotation(RawResponse.class)
                && !returnType.getContainingClass().isAnnotationPresent(RawResponse.class)
                && !returnType.getParameterType().isAnnotationPresent(RawResponse.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || body instanceof ApiResponse<?> || shouldSkipBody(body) || !isJson(selectedContentType)) {
            return body;
        }
        response.getHeaders().set(TraceIdSupport.HEADER_NAME, TraceIdSupport.currentOrCreate());
        return ApiResponse.success(body);
    }

    /**
     * 判断是否为需要保持原始响应、不得包装的对象。
     *
     * <p>文件下载（{@link Resource}）、SSE 流与流式输出（{@link StreamingResponseBody}）
     * 均不是 JSON Envelope 的适用场景；标注 {@link RawResponse} 的类型也原样返回。
     *
     * @param body 待判断的响应体
     * @return true 表示应跳过包装
     */
    private boolean shouldSkipBody(Object body) {
        return body instanceof Resource
                || body instanceof SseEmitter
                || body instanceof StreamingResponseBody
                || body.getClass().isAnnotationPresent(RawResponse.class);
    }

    /**
     * 判断选定响应内容类型是否为 JSON。
     *
     * <p>兼容纯 JSON 与 vendor JSON（如 {@code application/problem+json}）。
     *
     * @param mediaType 内容类型，可能为 null
     * @return true 表示是 JSON 类型
     */
    private boolean isJson(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        return MediaType.APPLICATION_JSON.includes(mediaType)
                || List.of(mediaType.getSubtype().split("\\+")).contains("json")
                || mediaType.getSubtype().endsWith("+json");
    }
}