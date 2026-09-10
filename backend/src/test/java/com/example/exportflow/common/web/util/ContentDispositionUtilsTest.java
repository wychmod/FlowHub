package com.example.exportflow.common.web.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ContentDispositionUtils 响应头组装单测：ASCII 直用 filename= / 非 ASCII 走 filename* 并兜底 + 空格转 %20。 */
class ContentDispositionUtilsTest {

    @Test
    void asciiDisplayNameUsedAsFilename() {
        String header = ContentDispositionUtils.attachment("import-template.xlsx", "import.xlsx");
        assertThat(header).startsWith("attachment; filename=\"import-template.xlsx\"");
        assertThat(header).contains("filename*=UTF-8''");
    }

    @Test
    void nonAsciiDisplayNameFallsBackAndPercentEncodes() {
        String header = ContentDispositionUtils.attachment("订单导出.xlsx", "export.xlsx");
        assertThat(header).contains("filename=\"export.xlsx\"");
        assertThat(header).contains("filename*=UTF-8''");
        // 中文经 URLEncoder 产出百分号字节序列，不含保留字符或 '+'（RFC 5987 空格须 %20）
        assertThat(header).doesNotContain("+");
    }

    @Test
    void spaceIsPercentEncodedNotPlus() {
        String header = ContentDispositionUtils.attachment("my file.xlsx", "fallback.xlsx");
        assertThat(header).contains("filename*=UTF-8''my%20file.xlsx");
        assertThat(header).doesNotContain("+");
    }
}