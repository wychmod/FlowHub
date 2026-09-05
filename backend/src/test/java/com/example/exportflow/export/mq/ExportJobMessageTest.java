package com.example.exportflow.export.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExportJobMessage} 消息契约单测：schema 防御、message_id 稳定派生与 snake_case 序列化。
 */
class ExportJobMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void isSupportedTrueForCurrentSchema() {
        ExportJobMessage message = new ExportJobMessage(
                ExportJobMessage.SCHEMA_VERSION, ExportJobMessage.messageIdFor(1L), 42L, ExportJobMessage.EVENT_VERSION);
        assertThat(message.isSupported()).isTrue();
    }

    @Test
    void isSupportedFalseForUnknownSchema() {
        ExportJobMessage message = new ExportJobMessage(2, ExportJobMessage.messageIdFor(1L), 42L, 1);
        assertThat(message.isSupported()).isFalse();
    }

    @Test
    void messageIdStableForSameOutboxEvent() {
        String first = ExportJobMessage.messageIdFor(42L);
        assertThat(ExportJobMessage.messageIdFor(42L)).isEqualTo(first);
        assertThat(first).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(ExportJobMessage.messageIdFor(43L)).isNotEqualTo(first);
    }

    @Test
    void serializesSnakeCaseFields() throws Exception {
        ExportJobMessage message = new ExportJobMessage(1, "msg-1", 42L, 1);
        String json = objectMapper.writeValueAsString(message);
        assertThat(json)
                .contains("\"schema_version\":1")
                .contains("\"message_id\":\"msg-1\"")
                .contains("\"job_id\":42")
                .contains("\"event_version\":1");
    }
}
