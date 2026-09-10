package com.example.flowhub.common.web.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求 trace_id 过滤器：透传或生成 trace_id，写入 MDC 与响应头。
 * <p>
 * 优先级最高，确保整个请求链路都带 trace 上下文。
 */
@Component
@Order(Integer.MIN_VALUE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestTraceId = request.getHeader(TraceIdSupport.HEADER_NAME);
        String traceId = TraceIdSupport.isValid(requestTraceId) ? requestTraceId : TraceIdSupport.newTraceId();
        MdcScope scope = MdcScope.withTraceId(traceId);
        // 进入下游前先写响应头，避免 Spring 提前 flush 导致 setHeader 失效
        response.setHeader(TraceIdSupport.HEADER_NAME, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            scope.close();
        }
    }
}
