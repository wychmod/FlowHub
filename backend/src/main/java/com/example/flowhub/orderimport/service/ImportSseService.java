package com.example.flowhub.orderimport.service;

import com.example.flowhub.orderimport.entity.ImportJobEntity;
import com.example.flowhub.orderimport.event.ImportJobChanged;
import com.example.flowhub.orderimport.event.ImportJobEventPayload;
import com.example.flowhub.orderimport.mapper.ImportJobMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导入 SSE 服务：进程内连接表 + 尽力广播，事实源是 MySQL。
 * <p>
 * 发送前重读 Job（不信任事件携带的状态）；事件 id = jobId:version 供前端版本栅栏拒绝迟到旧事件；
 * 单个连接发送失败只移除该连接，不影响其他连接与任务执行。
 */
@Service
public class ImportSseService {

    private static final Logger log = LoggerFactory.getLogger(ImportSseService.class);

    /** 事件名（契约 §5.6 的 5 类事件）。 */
    public static final String EVENT_IMPORT_PROGRESS = "import.progress";
    public static final String EVENT_IMPORT_SUCCEEDED = "import.succeeded";
    public static final String EVENT_IMPORT_PARTIAL = "import.partial";
    public static final String EVENT_IMPORT_FAILED = "import.failed";
    public static final String EVENT_HEARTBEAT = "heartbeat";

    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_FAILED = "FAILED";

    private final Map<String, SseEmitter> connections = new ConcurrentHashMap<>();
    private final ImportJobMapper importJobMapper;

    public ImportSseService(ImportJobMapper importJobMapper) {
        this.importJobMapper = importJobMapper;
    }

    /** 建立连接：登记断连回调并先发一条心跳。 */
    public SseEmitter connect() {
        String connectionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> connections.remove(connectionId));
        emitter.onTimeout(() -> connections.remove(connectionId));
        emitter.onError(e -> connections.remove(connectionId));
        connections.put(connectionId, emitter);
        sendQuietly(connectionId, emitter, heartbeatEvent());
        return emitter;
    }

    /** 定时心跳：间隔可配，测试置大静默。 */
    @Scheduled(fixedRateString = "${import.sse.heartbeat-ms:15000}")
    public void broadcastHeartbeat() {
        connections.forEach((connectionId, emitter) -> sendQuietly(connectionId, emitter, heartbeatEvent()));
    }

    /** 导入任务变化监听：DB 事实提交后重读 Job 再广播，杜绝伪状态广播；绝不穿透异常。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void importChanged(ImportJobChanged changed) {
        try {
            ImportJobEntity job = importJobMapper.selectById(changed.jobId());
            if (job == null) {
                return;
            }
            broadcast(eventName(job.status()), job.id() + ":" + job.version(), ImportJobEventPayload.from(job));
        } catch (Exception ex) {
            log.warn("import_sse_broadcast_failed job_id={} reason={}", changed.jobId(), ex.toString());
        }
    }

    /** 供测试注入受控连接。 */
    void attach(String connectionId, SseEmitter emitter) {
        connections.put(connectionId, emitter);
    }

    private SseEmitter.SseEventBuilder heartbeatEvent() {
        return SseEmitter.event()
                .name(EVENT_HEARTBEAT)
                .data(new HeartbeatPayload(LocalDateTime.now()), MediaType.APPLICATION_JSON);
    }

    private void broadcast(String eventName, String eventId, ImportJobEventPayload payload) {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventName).id(eventId);
        event.data(payload, MediaType.APPLICATION_JSON);
        connections.forEach((connectionId, emitter) -> sendQuietly(connectionId, emitter, event));
    }

    /** 发送一条事件；失败视为坏连接，移除后优雅关闭（广播静默降级，不让单个连接拖垮广播）。 */
    private void sendQuietly(String connectionId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (Exception ex) {
            closeQuietly(connectionId, emitter);
            log.debug("import_sse_send_failed connection_id={} reason={}", connectionId, ex.toString());
        }
    }

    /** 从连接表移除并优雅 complete；关闭失败忽略（连接已不可用，无需处理）。 */
    private void closeQuietly(String connectionId, SseEmitter emitter) {
        connections.remove(connectionId);
        try {
            emitter.complete();
        } catch (Exception completeFailure) {
            // 连接已不可用，关闭失败无需处理
        }
    }

    private static String eventName(String status) {
        return switch (status) {
            case STATUS_SUCCEEDED -> EVENT_IMPORT_SUCCEEDED;
            case STATUS_PARTIAL -> EVENT_IMPORT_PARTIAL;
            case STATUS_FAILED -> EVENT_IMPORT_FAILED;
            default -> EVENT_IMPORT_PROGRESS;
        };
    }

    /** 心跳负载 record。 */
    private record HeartbeatPayload(@JsonProperty("occurred_at") LocalDateTime occurredAt) {
    }
}