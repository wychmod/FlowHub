package com.example.exportflow.common.web.trace;

import org.slf4j.MDC;

/**
 * MDC 作用域（AutoCloseable）：进入时写入 trace_id，退出时还原进入前的上下文。
 * <p>
 * 支持嵌套使用。
 */
public final class MdcScope implements AutoCloseable {

    private final String previousTraceId;

    private MdcScope(String traceId) {
        previousTraceId = MDC.get(TraceIdSupport.MDC_KEY);
        if (traceId == null) {
            MDC.remove(TraceIdSupport.MDC_KEY);
            return;
        }
        MDC.put(TraceIdSupport.MDC_KEY, traceId);
    }

    /** 以指定 trace_id 打开一个 MDC 作用域。 */
    public static MdcScope withTraceId(String traceId) {
        return new MdcScope(traceId);
    }

    /** 关闭作用域，恢复进入前的上下文。 */
    @Override
    public void close() {
        if (previousTraceId == null) {
            MDC.remove(TraceIdSupport.MDC_KEY);
            return;
        }
        MDC.put(TraceIdSupport.MDC_KEY, previousTraceId);
    }
}
