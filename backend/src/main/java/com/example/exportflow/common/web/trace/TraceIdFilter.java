package com.example.exportflow.common.web.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为每个 HTTP 请求生成或透传 trace_id，并把 MDC 作用域铺到整个请求链路。
 *
 * <p>优先级最高（{@code @Order(Integer.MIN_VALUE)}）确保最先执行；MDC 的写入/还原委托给
 * {@link MdcScope}，保证请求结束（含异常）后上下文被还原。响应头由外层 finally 回写，
 * 即使业务抛异常也能把 trace_id 返回给调用方。
 */
@Component
@Order(Integer.MIN_VALUE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestTraceId = request.getHeader(TraceIdSupport.HEADER_NAME);
        // 上游透传的 id 非法则重新生成，避免脏数据污染日志链路。
        String traceId = TraceIdSupport.isValid(requestTraceId) ? requestTraceId : TraceIdSupport.newTraceId();
        MdcScope scope = MdcScope.withTraceId(traceId);
        // 进入下游处理前先写入响应头：此时响应尚未 committed，回写一定生效；
        // 若放到 doFilter 之后，Spring 在处理小响应时可能已 flush，setHeader 会被静默忽略。
        response.setHeader(TraceIdSupport.HEADER_NAME, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            scope.close();
        }
    }
}