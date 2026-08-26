package com.example.exportflow.common.web.trace;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** trace_id 读取/生成/校验工具，纯静态、无依赖，供 Filter、异步、日志等场景复用。 */
public final class TraceIdSupport {

    /** 透传请求头/响应头名称。 */
    public static final String HEADER_NAME = "X-Trace-Id";

    /**
     * MDC 键。与 application.yml 的日志 pattern（{@code %X{trace_id:-}}）保持一致。
     * 注意：与参照项目（project-export-flow）内部用的 {@code traceId} 不同，刻意保留当前项目的
     * {@code trace_id} 约定，避免连带修改日志配置与既有响应字段。
     */
    public static final String MDC_KEY = "trace_id";

    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9-]{16,64}");

    private TraceIdSupport() {
    }

    /** 生成一个新 trace_id（32 位十六进制，无连字符）。 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 当前线程的 trace_id；取不到时返回空串，保证响应字段始终存在。 */
    public static String currentTraceId() {
        String traceId = MDC.get(MDC_KEY);
        return traceId == null ? "" : traceId;
    }

    /** 优先返回当前线程的 trace_id，非法或缺失时新建一个（适用于异步/非请求上下文）。 */
    public static String currentOrCreate() {
        String current = MDC.get(MDC_KEY);
        return isValid(current) ? current : newTraceId();
    }

    /** trace_id 合法性校验：长度 16-64，仅允许字母、数字与连字符；空/null 视为非法。 */
    public static boolean isValid(String traceId) {
        return traceId != null && VALID_TRACE_ID.matcher(traceId).matches();
    }
}