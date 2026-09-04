package com.example.exportflow.common.web.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Sha256Utils#sha256Hex} 语义：UTF-8 编码、64 位小写 hex、与 JDK MessageDigest 直算一致。 */
class Sha256UtilsTest {

    @Test
    void sha256Hex_标准向量() {
        assertThat(Sha256Utils.sha256Hex("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256Utils.sha256Hex("")).isEqualTo(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256Hex_中文按UTF8编码与JDK直算一致() throws Exception {
        String input = "导出任务-EXP20260904";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String expected = HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        assertThat(Sha256Utils.sha256Hex(input)).isEqualTo(expected).hasSize(64);
    }
}
