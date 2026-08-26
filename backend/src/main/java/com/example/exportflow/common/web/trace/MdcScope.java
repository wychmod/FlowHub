package com.example.exportflow.common.web.trace;

import org.slf4j.MDC;

/** MDC 作用域管理器（AutoCloseable），负责写入并负责退出时回退到进入前的上下文。 */
public final class MdcScope implements AutoCloseable {

    private final String previousTraceId;

    private MdcScope(String traceId) {
        // 记录进入本作用域前的 trace_id，供 close() 时还原（支持嵌套/父子作用域）。
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

    /** 关闭作用域：恢复进入前的上下文；若此前没有 trace_id 则清除。 */
    @Override
    public void close() {
        if (previousTraceId == null) {
            MDC.remove(TraceIdSupport.MDC_KEY);
            return;
        }
        MDC.put(TraceIdSupport.MDC_KEY, previousTraceId);
    }
}