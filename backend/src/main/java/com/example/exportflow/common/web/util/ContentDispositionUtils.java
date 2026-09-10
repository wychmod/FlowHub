package com.example.exportflow.common.web.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * HTTP 响应头 Content-Disposition 组装工具（RFC 5987）：
 * ASCII 展示名写 filename=，非 ASCII 按百分号编码到 filename*=UTF-8''。
 * 导出/导入控制器共用，避免各自重复实现同一套编码。
 */
public final class ContentDispositionUtils {

    /** filename= 兜底的名须为安全 ASCII：仅可见字符且不含引号/反斜杠等转义位。 */
    private static final Pattern ASCII_DISPLAY_NAME = Pattern.compile("\\A[\\w. ()\\[\\]-]+\\z");

    private ContentDispositionUtils() {
    }

    /**
     * 组装 Content-Disposition（attachment）：非 ASCII 展示名按 RFC 5987 百分号编码进 filename*=UTF-8''。
     *
     * @param displayName   展示文件名（可能含非 ASCII）
     * @param asciiFallback 展示名为非安全 ASCII 时 filename= 用的兜底名（各模块传自己的业务默认值）
     */
    public static String attachment(String displayName, String asciiFallback) {
        String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
        String safeFallback = ASCII_DISPLAY_NAME.matcher(displayName).matches() ? displayName : asciiFallback;
        return "attachment; filename=\"" + safeFallback + "\"; filename*=UTF-8''" + encoded;
    }
}