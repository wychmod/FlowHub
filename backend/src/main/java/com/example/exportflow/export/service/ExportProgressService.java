package com.example.exportflow.export.service;

import com.example.exportflow.common.web.util.ExceptionUtils;
import com.example.exportflow.export.entity.ExportJobEntity;
import com.example.exportflow.export.event.ExportJobChanged;
import com.example.exportflow.export.event.ExportJobEventPayload;
import com.example.exportflow.export.mapper.ExportJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 导出进度服务：MySQL 事实源推进 → 发布变化事件 → 尽力写 Redis 投影。
 * <p>
 * 进度推进的成功证据是数据库条件更新结果（0 行 fail-fast）；Redis 投影可失败，仅降级日志，
 * 绝不影响任务推进——事实源始终是 MySQL。
 */
@Service
public class ExportProgressService {

    private static final Logger log = LoggerFactory.getLogger(ExportProgressService.class);

    /** 投影 Key 前缀（Hash：status/processedRows/totalRows/percent/updatedAt）。 */
    private static final String PROJECTION_KEY_PREFIX = "export:progress:";

    /** 投影 TTL：只限制缓存存活，不是文件保留期，也不是 Job 业务过期时间。 */
    private static final Duration PROJECTION_TTL = Duration.ofHours(48);

    private final ExportJobMapper exportJobMapper;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final long leaseMinutes;

    public ExportProgressService(ExportJobMapper exportJobMapper,
                                 StringRedisTemplate redis,
                                 ApplicationEventPublisher events,
                                 @Value("${export.execution.lease-minutes:5}") long leaseMinutes) {
        this.exportJobMapper = exportJobMapper;
        this.redis = redis;
        this.events = events;
        this.leaseMinutes = leaseMinutes;
    }

    /**
     * 推进进度：条件更新成功（1 行）才发布变化事件与刷新投影；0 行 fail-fast 抛异常走失败收敛
     * （状态已被并发推进时带病继续写文件只会浪费资源且终态语义混乱）。
     */
    public void report(long jobId, long processedRows, long totalRows) {
        LocalDateTime now = LocalDateTime.now();
        if (exportJobMapper.updateProgress(jobId, processedRows, now, now.plusMinutes(leaseMinutes)) != 1) {
            throw new IllegalStateException("进度推进失败（任务非 RUNNING 或进度回退），job_id=" + jobId);
        }
        events.publishEvent(new ExportJobChanged(jobId));
        cache(jobId, "RUNNING", processedRows, totalRows, now);
    }

    /** 按事实源重读并刷新投影（失败收敛/终态后调用；投影永远从 DB 重读，不信任调用方状态）。 */
    public void refreshProjection(long jobId) {
        ExportJobEntity job = exportJobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        cache(job.id(), job.status(), job.processedRows() == null ? 0 : job.processedRows(),
                job.filterCount() == null ? 0 : job.filterCount(), LocalDateTime.now());
    }

    /** 删除任务进度投影（EXPIRED 收敛后调用；失败仅降级日志，不影响已完成的文件与状态收敛）。 */
    public void deleteProjection(long jobId) {
        try {
            redis.delete(PROJECTION_KEY_PREFIX + jobId);
        } catch (RuntimeException ex) {
            log.warn("redis_progress_delete_failed job_id={} reason={}",
                    jobId, ExceptionUtils.messageOrTypeName(ex));
        }
    }

    /** 尽力写投影：失败仅记录降级日志（redis_progress_write_failed），绝不向调用方抛异常。 */
    private void cache(long jobId, String status, long processedRows, long totalRows, LocalDateTime now) {
        try {
            String key = PROJECTION_KEY_PREFIX + jobId;
            redis.opsForHash().putAll(key, Map.of(
                    "status", status,
                    "processedRows", String.valueOf(processedRows),
                    "totalRows", String.valueOf(totalRows),
                    "percent", String.valueOf(ExportJobEventPayload.progressPercent(status, processedRows, totalRows)),
                    "updatedAt", now.toString()));
            redis.expire(key, PROJECTION_TTL);
        } catch (RuntimeException ex) {
            log.warn("redis_progress_write_failed job_id={} status={} reason={}",
                    jobId, status, ExceptionUtils.messageOrTypeName(ex));
        }
    }
}
