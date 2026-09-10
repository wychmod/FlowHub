package com.example.exportflow.orderimport.service;

import com.example.exportflow.common.web.util.ExceptionUtils;
import com.example.exportflow.orderimport.entity.ImportJobEntity;
import com.example.exportflow.orderimport.event.ImportJobChanged;
import com.example.exportflow.orderimport.event.ImportJobEventPayload;
import com.example.exportflow.orderimport.mapper.ImportJobMapper;
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
 * 导入进度服务（docs/order-import-design.md §7.4/B12）：MySQL 事实源推进 → 发布变化事件 → 尽力写 Redis 投影。
 * <p>
 * 推进成功证据是数据库条件更新结果（0 行 fail-fast）；Redis 投影可失败仅降级日志，事实源始终是 MySQL。
 */
@Service
public class ImportProgressService {

    private static final Logger log = LoggerFactory.getLogger(ImportProgressService.class);

    /** 投影 Key 前缀（Hash：status/processedRows/succeededRows/skippedRows/percent/updatedAt）。 */
    private static final String PROJECTION_KEY_PREFIX = "import:progress:";
    /** 投影 TTL：只限制缓存存活，不是文件保留期。 */
    private static final Duration PROJECTION_TTL = Duration.ofHours(48);

    private final ImportJobMapper importJobMapper;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final long leaseMinutes;

    public ImportProgressService(ImportJobMapper importJobMapper,
                                 StringRedisTemplate redis,
                                 ApplicationEventPublisher events,
                                 @Value("${import.execution.lease-minutes:5}") long leaseMinutes) {
        this.importJobMapper = importJobMapper;
        this.redis = redis;
        this.events = events;
        this.leaseMinutes = leaseMinutes;
    }

    /**
     * 推进进度：条件更新成功才发布事件与刷新投影；0 行 fail-fast 抛异常走失败收敛。
     *
     * @param processedRows 累计已处理行数
     * @param succeededRows 累计成功行数
     * @param skippedRows   累计跳过行数
     * @param totalRows     任务数据行总数（受理期确定，用于 percent）
     */
    public void report(long jobId, long processedRows, long succeededRows, long skippedRows, long totalRows) {
        LocalDateTime now = LocalDateTime.now();
        if (importJobMapper.updateProgress(jobId, (int) processedRows, (int) succeededRows, (int) skippedRows,
                now, now.plusMinutes(leaseMinutes)) != 1) {
            throw new IllegalStateException("进度推进失败（任务非 RUNNING 或进度回退），job_id=" + jobId);
        }
        events.publishEvent(new ImportJobChanged(jobId));
        cache(jobId, "RUNNING", processedRows, succeededRows, skippedRows, totalRows, now);
    }

    /** 按事实源重读并刷新投影（失败收敛/终态后调用；投影永远从 DB 重读，不信任调用方状态）。 */
    public void refreshProjection(long jobId) {
        ImportJobEntity job = importJobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        cache(job.id(), job.status(),
                job.processedRows() == null ? 0 : job.processedRows(),
                job.succeededRows() == null ? 0 : job.succeededRows(),
                job.skippedRows() == null ? 0 : job.skippedRows(),
                job.totalRows() == null ? 0 : job.totalRows(),
                LocalDateTime.now());
    }

    /** 删除任务进度投影（EXPIRED 收敛后调用；失败仅降级日志）。 */
    public void deleteProjection(long jobId) {
        try {
            redis.delete(PROJECTION_KEY_PREFIX + jobId);
        } catch (RuntimeException ex) {
            log.warn("redis_progress_delete_failed job_id={} reason={}",
                    jobId, ExceptionUtils.messageOrTypeName(ex));
        }
    }

    /** 尽力写投影：失败仅记录降级日志，绝不向调用方抛异常。 */
    private void cache(long jobId, String status, long processedRows, long succeededRows, long skippedRows,
                       long totalRows, LocalDateTime now) {
        try {
            String key = PROJECTION_KEY_PREFIX + jobId;
            redis.opsForHash().putAll(key, Map.of(
                    "status", status,
                    "processedRows", String.valueOf(processedRows),
                    "succeededRows", String.valueOf(succeededRows),
                    "skippedRows", String.valueOf(skippedRows),
                    "percent", String.valueOf(ImportJobEventPayload.progressPercent(status, processedRows, totalRows)),
                    "updatedAt", now.toString()));
            redis.expire(key, PROJECTION_TTL);
        } catch (RuntimeException ex) {
            log.warn("redis_progress_write_failed job_id={} status={} reason={}",
                    jobId, status, ExceptionUtils.messageOrTypeName(ex));
        }
    }
}