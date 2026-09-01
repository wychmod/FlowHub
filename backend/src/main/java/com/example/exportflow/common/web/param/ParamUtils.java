package com.example.exportflow.common.web.param;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * HTTP 入参归一化工具：空白折叠、多值拆分、枚举解析。
 * <p>
 * 所有方法为静态纯函数，解析失败返回 null/空集合，由调用方决定报错方式。
 */
public final class ParamUtils {

    private ParamUtils() {
    }

    /**
     * 字符串空白归一：null/空串/纯空白统一返回 null。
     *
     * @param raw 原始字符串
     * @return trim 后非空返回原值，否则返回 null
     */
    public static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 逗号多值拆分：trim → 按逗号拆分 → 去空 token → 去重。
     *
     * @param raw 原始逗号分隔串
     * @return 不可变列表；未传或拆分后为空返回空列表
     */
    public static List<String> splitMultiValue(String raw) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return List.of();
        }
        return Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * 枚举大小写不敏感解析，未识别返回 null。
     *
     * @param raw      原始字符串
     * @param enumType 目标枚举类
     * @param <T>      枚举类型
     * @return 对应枚举常量，无法解析返回 null
     */
    public static <T extends Enum<T>> T enumFromName(String raw, Class<T> enumType) {
        if (trimToNull(raw) == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
