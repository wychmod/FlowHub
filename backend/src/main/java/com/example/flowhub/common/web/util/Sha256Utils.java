package com.example.flowhub.common.web.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 摘要工具（UTF-8 编码，输出小写 hex）。
 */
public final class Sha256Utils {

    private Sha256Utils() {
    }

    /** 计算字符串 SHA-256 摘要（UTF-8），返回 64 位小写 hex。 */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 算法不可用", ex);
        }
    }
}
