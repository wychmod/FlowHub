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
 * 统一响应包装：将控制器裸返回值自动包为 {@link ApiResponse}，并回写 trace_id 响应头。
 * <p>
 * 跳过已包装、流式/文件响应，以及标注 {@link RawResponse} 的接口。
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

    /** 文件下载、SSE 流、流式输出及 {@link RawResponse} 类型不包装。 */
    private boolean shouldSkipBody(Object body) {
        return body instanceof Resource
                || body instanceof SseEmitter
                || body instanceof StreamingResponseBody
                || body.getClass().isAnnotationPresent(RawResponse.class);
    }

    /** 判断响应内容类型是否为 JSON（含 vendor JSON 如 application/problem+json）。 */
    private boolean isJson(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        return MediaType.APPLICATION_JSON.includes(mediaType)
                || List.of(mediaType.getSubtype().split("\\+")).contains("json")
                || mediaType.getSubtype().endsWith("+json");
    }
}
