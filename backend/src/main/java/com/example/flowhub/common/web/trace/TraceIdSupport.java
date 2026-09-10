package com.example.flowhub.common.web.trace;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** trace_id 工具类：生成、读取、校验。 */
public final class TraceIdSupport {

    /** 透传请求头/响应头名称。 */
    public static final String HEADER_NAME = "X-Trace-Id";

    /** MDC 键，与日志 pattern 的 %X{trace_id:-} 对应。 */
    public static final String MDC_KEY = "trace_id";

    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9-]{16,64}");

    private TraceIdSupport() {
    }

    /** 生成新 trace_id（32 位十六进制）。 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 当前线程 trace_id，取不到返回空串。 */
    public static String currentTraceId() {
        String traceId = MDC.get(MDC_KEY);
        return traceId == null ? "" : traceId;
    }

    /** 优先取当前 trace_id，不存在或非法则新建。 */
    public static String currentOrCreate() {
        String current = MDC.get(MDC_KEY);
        return isValid(current) ? current : newTraceId();
    }

    /** trace_id 合法性校验：长度 16-64，字母数字连字符；空/null 为非法。 */
    public static boolean isValid(String traceId) {
        return traceId != null && VALID_TRACE_ID.matcher(traceId).matches();
    }
}
