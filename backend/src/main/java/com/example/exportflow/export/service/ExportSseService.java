package com.example.exportflow.export.service;

import com.example.exportflow.export.entity.ExportJobEntity;
import com.example.exportflow.export.event.ExportJobChanged;
import com.example.exportflow.export.event.ExportJobEventPayload;
import com.example.exportflow.export.mapper.ExportJobMapper;
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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导出 SSE 服务（第 16 章）：进程内连接表 + 尽力广播，事实源是 MySQL。
 * <p>
 * 发送前重读 Job（不信任事件携带的状态）；事件 id = jobId:version 供前端版本栅栏拒绝迟到旧事件；
 * 单个连接发送失败只移除该连接，不影响其他连接与任务执行。端点契约见 docs/export-sse-design.md 3.1。
 */
@Service
public class ExportSseService {

    private static final Logger log = LoggerFactory.getLogger(ExportSseService.class);

    /** 事件名（契约见 docs/export-sse-design.md 3.2，对齐 be-td.md 4.10 的 4 类事件）。 */
    public static final String EVENT_JOB_PROGRESS = "job.progress";
    public static final String EVENT_JOB_SUCCEEDED = "job.succeeded";
    public static final String EVENT_JOB_FAILED = "job.failed";
    public static final String EVENT_HEARTBEAT = "heartbeat";

    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";

    private final Map<String, SseEmitter> connections = new ConcurrentHashMap<>();
    private final ExportJobMapper exportJobMapper;

    public ExportSseService(ExportJobMapper exportJobMapper) {
        this.exportJobMapper = exportJobMapper;
    }

    /** 建立连接：登记断连回调并先发一条心跳（心跳只证明连接活着，不代表任务推进）。 */
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

    /** 定时心跳：维持连接可观察性（间隔可配，测试置大静默）。 */
    @Scheduled(fixedRateString = "${export.sse.heartbeat-ms:15000}")
    public void broadcastHeartbeat() {
        connections.forEach((connectionId, emitter) -> sendQuietly(connectionId, emitter, heartbeatEvent()));
    }

    /** 任务变化监听：DB 事实提交后（或无事务的进度路径）重读 Job 再广播，杜绝伪状态广播。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void jobChanged(ExportJobChanged changed) {
        // 尽力通知绝不向提交方抛异常：AFTER_COMMIT 阶段的异常会穿透回发布者——
        // 成功事务提交后的广播失败若外泄，会误触发执行体对已登记文件的补偿删除
        try {
            ExportJobEntity job = exportJobMapper.selectById(changed.jobId());
            if (job == null) {
                return;
            }
            broadcast(eventName(job.status()), job.id() + ":" + job.version(), ExportJobEventPayload.from(job));
        } catch (Exception ex) {
            log.warn("sse_broadcast_failed job_id={} reason={}", changed.jobId(), ex.toString());
        }
    }

    /** 供测试注入受控连接（生产入口为 connect()）。 */
    void attach(String connectionId, SseEmitter emitter) {
        connections.put(connectionId, emitter);
    }

    /** 心跳负载：仅发生时间，前端只更新活跃时间不改任务缓存。 */
    private SseEmitter.SseEventBuilder heartbeatEvent() {
        return SseEmitter.event()
                .name(EVENT_HEARTBEAT)
                .data(new HeartbeatPayload(LocalDateTime.now()), MediaType.APPLICATION_JSON);
    }

    private static SseEmitter.SseEventBuilder jobEvent(String eventName, String eventId, ExportJobEventPayload payload) {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventName).id(eventId);
        return event.data(payload, MediaType.APPLICATION_JSON);
    }

    private void broadcast(String eventName, String eventId, ExportJobEventPayload payload) {
        connections.forEach((connectionId, emitter) ->
                sendQuietly(connectionId, emitter, jobEvent(eventName, eventId, payload)));
    }

    /** 发送失败只移除坏连接并尝试优雅关闭，不向调用方抛异常（尽力通知，不是事件账本）。 */
    private void sendQuietly(String connectionId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ex) {
            connections.remove(connectionId);
            try {
                emitter.complete();
            } catch (Exception completeFailure) {
                // 连接已不可用，关闭失败无需处理
            }
            log.debug("sse_send_failed connection_id={} reason={}", connectionId, ex.toString());
        }
    }

    private static String eventName(String status) {
        return switch (status) {
            case STATUS_SUCCEEDED -> EVENT_JOB_SUCCEEDED;
            case STATUS_FAILED -> EVENT_JOB_FAILED;
            default -> EVENT_JOB_PROGRESS;
        };
    }

    /** 心跳负载 record。 */
    private record HeartbeatPayload(@JsonProperty("occurred_at") LocalDateTime occurredAt) {
    }
}
