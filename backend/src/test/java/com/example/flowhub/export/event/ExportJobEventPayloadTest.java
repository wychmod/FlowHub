package com.example.flowhub.export.event;

import com.example.flowhub.export.entity.ExportJobEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 事件 payload 契约单测：99% 封顶、SUCCEEDED 才 100、
 * 进度事件省略 error 字段（NON_NULL）、失败终态显式携带错误信息。
 */
class ExportJobEventPayloadTest {

    private static ExportJobEntity job(String status, long version, long processed, long total, String errorCode) {
        return new ExportJobEntity(42L, "EXP20260907-TEST", status, 100L, "k", "h",
                total, "{}", "[]", "[]", "demo.xlsx",
                version, processed, 0, errorCode, errorCode == null ? null : "生成失败",
                null, null, null, null,
                LocalDateTime.of(2026, 9, 7, 0, 0), LocalDateTime.of(2026, 9, 7, 1, 0));
    }

    // ==================== 99% 封顶规则 ====================

    @Test
    void runningPercentCappedAt99() {
        assertThat(ExportJobEventPayload.progressPercent("RUNNING", 1000, 1000)).isEqualTo(99);
    }

    @Test
    void succeededPercentIs100EvenIfNotAllRows() {
        assertThat(ExportJobEventPayload.progressPercent("SUCCEEDED", 500, 1000)).isEqualTo(100);
    }

    @Test
    void zeroTotalYieldsZeroPercent() {
        assertThat(ExportJobEventPayload.progressPercent("RUNNING", 0, 0)).isZero();
    }

    // ==================== 字段存在性即协议 ====================

    @Test
    void progressPayloadOmitsErrorFields() {
        ExportJobEventPayload payload = ExportJobEventPayload.from(job("RUNNING", 3L, 600, 1250, null));

        assertThat(payload.jobId()).isEqualTo("42"); // 契约示例：job_id 为字符串
        assertThat(payload.jobVersion()).isEqualTo(3L);
        assertThat(payload.progressPercent()).isEqualTo(48); // 600*100/1250
        assertThat(payload.downloadable()).isFalse();
        assertThat(payload.errorCode()).isNull();
        assertThat(payload.errorMessage()).isNull();
    }

    @Test
    void failedPayloadCarriesErrorExplicitly() {
        ExportJobEventPayload payload = ExportJobEventPayload.from(
                job("FAILED", 4L, 300, 1250, "FILE_GENERATION_FAILED"));

        assertThat(payload.errorCode()).isEqualTo("FILE_GENERATION_FAILED");
        assertThat(payload.errorMessage()).isNotNull();
        assertThat(payload.downloadable()).isFalse();
        assertThat(payload.progressPercent()).isEqualTo(24);
    }

    @Test
    void succeededPayloadIsDownloadableAnd100() {
        ExportJobEventPayload payload = ExportJobEventPayload.from(job("SUCCEEDED", 9L, 1250, 1250, null));

        assertThat(payload.downloadable()).isTrue();
        assertThat(payload.progressPercent()).isEqualTo(100);
        assertThat(payload.errorCode()).isNull();
    }
}
