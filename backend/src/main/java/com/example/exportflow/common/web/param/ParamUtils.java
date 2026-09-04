package com.example.exportflow.common.web.param;

import com.example.exportflow.common.web.error.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * HTTP 入参归一化与解析公共工具。
 * <p>
 * 归一化方法（trimToNull/splitMultiValue/enumFromName）解析失败返回 null/空集合，
 * 由调用方决定报错方式；解析方法（parseDecimal/parseDateTime/parsePhone/parseMultiEnum）失败统一
 * 抛 {@link BusinessException}（VALIDATION_ERROR，HTTP 400）。
 */
public final class ParamUtils {

    /** 时间格式：yyyy-MM-dd'T'HH:mm:ss[.SSS]，不含时区。 */
    private static final DateTimeFormatter TIME_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true)
            .toFormatter();

    private static final Pattern PHONE_PATTERN = Pattern.compile("\\d{11}");

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

    /** 数值解析：null/空白返回 null，非法数值抛 400（文案含字段名）。 */
    public static BigDecimal parseDecimal(String raw, String field) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            throw BusinessException.validation(field + " 不是合法的数值：" + normalized);
        }
    }

    /** 时间解析（yyyy-MM-dd'T'HH:mm:ss[.SSS]，不含时区）：非法格式抛 400。 */
    public static LocalDateTime parseDateTime(String raw, String field) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(normalized, TIME_FORMATTER);
        } catch (DateTimeParseException ex) {
            throw BusinessException.validation(field + " 格式不合法（应为 yyyy-MM-dd'T'HH:mm:ss[.SSS]，不含时区）：" + normalized);
        }
    }

    /** 手机号解析：trim 后必须为 11 位数字，非法抛 400。 */
    public static String parsePhone(String raw, String field) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return null;
        }
        if (!PHONE_PATTERN.matcher(normalized).matches()) {
            throw BusinessException.validation(field + " 必须为 11 位数字");
        }
        return normalized;
    }

    /** 多值枚举解析：拆分 + 大写归一 + 去重，未命中白名单抛 400（whitelist 须为大写取值）。 */
    public static List<String> parseMultiEnum(String raw, List<String> whitelist, String field) {
        List<String> values = splitMultiValue(raw).stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        return validateWhitelist(values, whitelist, field);
    }

    /**
     * 多值枚举解析（集合入参，供 JSON 快照数组使用）：逐项 trim + 大写归一 + 去重 + 剔除空白项，
     * 未命中白名单抛 400。
     */
    public static List<String> parseMultiEnum(List<String> rawValues, List<String> whitelist, String field) {
        if (rawValues == null) {
            return List.of();
        }
        List<String> values = rawValues.stream()
                .map(ParamUtils::trimToNull)
                .filter(value -> value != null)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        return validateWhitelist(values, whitelist, field);
    }

    private static List<String> validateWhitelist(List<String> values, List<String> whitelist, String field) {
        for (String value : values) {
            if (!whitelist.contains(value)) {
                throw BusinessException.validation(field + " 含非法枚举值：" + value);
            }
        }
        return values;
    }

}
