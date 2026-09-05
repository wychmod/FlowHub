package com.example.exportflow.export.mq;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * EXPORT_JOB_CREATED 消息契约（最小化）：只带执行定位信息，执行数据以库内 Job 为准，不复用 outbox payload。
 * <p>
 * 同一 outbox 事件重发携带同一 message_id，供下游观察重复投递。
 */
public record ExportJobMessage(
        @JsonProperty("schema_version") int schemaVersion,      // 消息封包格式版本，消费侧不识别即拒绝处理
        @JsonProperty("message_id") String messageId,           // 消息标识，供下游观察重复投递
        @JsonProperty("job_id") Long jobId,                     // 目标导出任务 ID（即 outbox 聚合根 ID）
        @JsonProperty("event_version") int eventVersion) {      // 事件语义版本（区别于封包格式版本）

    /** 消息封包 schema 版本（消费侧 isSupported 判定依据）。 */
    public static final int SCHEMA_VERSION = 1;

    /** EXPORT_JOB_CREATED 事件版本。 */
    public static final int EVENT_VERSION = 1;

    /** 由 outbox 事件 id 派生稳定 message_id：同事件重发同 ID（nameUUIDFromBytes 确定性生成）。 */
    public static String messageIdFor(long outboxEventId) {
        return UUID.nameUUIDFromBytes(("outbox:" + outboxEventId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 消费侧防御：不识别的 schema 版本拒绝处理。 */
    public boolean isSupported() {
        return schemaVersion == SCHEMA_VERSION;
    }
}
