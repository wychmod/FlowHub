package com.example.flowhub.common.web.upload;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.flowhub.common.web.trace.TraceIdSupport;

import java.io.IOException;
import java.io.InputStream;

/**
 * 超大请求体预拒过滤器：在进入 multipart 解析前按 Content-Length 拒绝并读光请求体再回 400。
 * <p>
 * 容器 multipart 闸拒绝时会中止解析并关闭连接，客户端「写不完 body 也收不到错误响应」
 * （浏览器表现为无限转圈）；先 drain 让客户端写完，才能拿到结构化 400。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // 紧随 TraceIdFilter，拒绝响应仍携带 trace_id
public class OversizeRequestBodyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OversizeRequestBodyFilter.class);

    private final long maxRequestBytes;

    public OversizeRequestBodyFilter(
            @Value("${spring.servlet.multipart.max-file-size:11MB}") DataSize maxFileSize) {
        this.maxRequestBytes = maxFileSize.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // chunked 传输（length<0）无长度可判，交由容器 multipart 闸兜底
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0 || contentLength <= maxRequestBytes) {
            chain.doFilter(request, response);
            return;
        }
        // 先读光请求体：客户端得以完整发送并读到 400，而非写一半被连接重置
        drainQuietly(request);
        log.warn("oversize_request_rejected method={} uri={} content_length={} limit={}",
                request.getMethod(), request.getRequestURI(), contentLength, maxRequestBytes);
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"VALIDATION_ERROR\",\"message\":\"上传文件过大，请控制在 10MB 以内\","
                + "\"data\":null,\"trace_id\":\"" + TraceIdSupport.currentOrCreate() + "\"}");
    }

    /** 读光请求体后丢弃；客户端已断开时静默（无法通知也无需响应）。 */
    private void drainQuietly(HttpServletRequest request) {
        byte[] buffer = new byte[8192];
        try (InputStream in = request.getInputStream()) {
            while (in.read(buffer) != -1) {
                // 丢弃
            }
        } catch (IOException ex) {
            log.debug("drain 请求体中断（客户端可能已断开）: {}", ex.toString());
        }
    }
}
