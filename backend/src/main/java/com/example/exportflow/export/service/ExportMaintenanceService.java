package com.example.exportflow.export.service;

import com.example.exportflow.common.web.trace.MdcScope;
import com.example.exportflow.common.web.trace.TraceIdSupport;
import com.example.exportflow.export.entity.ExportJobEntity;
import com.example.exportflow.export.event.ExportJobChanged;
import com.example.exportflow.export.mapper.ExportJobAttemptMapper;
import com.example.exportflow.export.mapper.ExportJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出维护服务：启动恢复租约失效的 RUNNING 任务，定时清理过期文件与孤儿对账。
 * <p>
 * 调度只提供执行时机，可靠性来自条件更新与「删除成功才推进状态」的收敛纪律；
 * 全部动作幂等——重跑一轮不产生错误结论。恢复 ≠ 重试：恢复收敛状态留证据，重跑由人发起。
 */
@Service
public class ExportMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(ExportMaintenanceService.class);

    /** 恢复收敛的稳定错误码：不是业务失败，是服务中断遗留（供用户/运维决策是否重试）。 */
    public static final String ERROR_CODE_SERVICE_RESTARTED = "SERVICE_RESTARTED";

    /** 恢复收敛的 error_message（列宽 500 内）。 */
    private static final String RESTART_MESSAGE = "执行进程中断（服务重启或失联），任务按租约到期收敛，可人工重试";
    /** 孤儿文件宽限期：文件早于该时点才进入对账候选（刚创建的活跃文件不被误判）。 */
    private static final Duration ORPHAN_GRACE = Duration.ofHours(1);
    /** 过期清理单轮扫描上限（走 (status, expired_at) 索引）。 */
    private static final int EXPIRED_BATCH_LIMIT = 100;

    private final ExportJobMapper exportJobMapper;
    private final ExportJobAttemptMapper exportJobAttemptMapper;
    private final ExportFileService exportFileService;
    private final ExportProgressService exportProgressService;
    private final ApplicationEventPublisher events;

    public ExportMaintenanceService(ExportJobMapper exportJobMapper,
                                    ExportJobAttemptMapper exportJobAttemptMapper,
                                    ExportFileService exportFileService,
                                    ExportProgressService exportProgressService,
                                    ApplicationEventPublisher events) {
        this.exportJobMapper = exportJobMapper;
        this.exportJobAttemptMapper = exportJobAttemptMapper;
        this.exportFileService = exportFileService;
        this.exportProgressService = exportProgressService;
        this.events = events;
    }

    /** 应用就绪后执行一次恢复与对账：只收敛「租约已失效」的 RUNNING，不误伤仍活跃的执行。 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverRunningJobs() {
        try (MdcScope ignored = MdcScope.withTraceId(TraceIdSupport.currentOrCreate())) {
            recoverRunningJobsInScope();
            reconcileStaleFiles();
        }
    }

    /** 定时维护：过期成功文件下架 + 孤儿文件对账（cron 可配，测试置 "-" 静默）。 */
    @Scheduled(cron = "${export.cleanup-cron:0 0 * * * *}")
    public void scheduledMaintenance() {
        try (MdcScope ignored = MdcScope.withTraceId(TraceIdSupport.currentOrCreate())) {
            cleanupExpiredExports();
            reconcileStaleFiles();
        }
    }

    /** 恢复收敛：Attempt 先行、Job 收尾（同一租约失效前置条件，同事务回滚保证两表结论一致）。 */
    private void recoverRunningJobsInScope() {
        LocalDateTime now = LocalDateTime.now();
        exportJobAttemptMapper.recoverExpiredRunning(now, ERROR_CODE_SERVICE_RESTARTED, RESTART_MESSAGE);
        int recovered = exportJobMapper.recoverExpiredRunning(now, ERROR_CODE_SERVICE_RESTARTED, RESTART_MESSAGE);
        if (recovered > 0) {
            log.warn("export_jobs_recovered job_count={} error_code={} trace_id={}",
                    recovered, ERROR_CODE_SERVICE_RESTARTED, TraceIdSupport.currentTraceId());
        }
    }

    /**
     * 过期清理（删除即事实）：正式文件删除成功才推进 EXPIRED，失败/路径非法保持 SUCCEEDED 下一轮再试；
     * 条件更新 0 行（已被并发推进）不发布事件。
     */
    public void cleanupExpiredExports() {
        LocalDateTime now = LocalDateTime.now();
        List<ExportJobEntity> expired = exportJobMapper.findExpiredSuccess(now, EXPIRED_BATCH_LIMIT);
        for (ExportJobEntity job : expired) {
            Path file;
            try {
                // 后台任务不豁免路径边界：DB 记录可能被污染，解析失败保持原状
                file = exportFileService.resolvePersisted(job.filePath());
            } catch (IllegalArgumentException ex) {
                log.warn("export_expire_path_invalid job_id={} file_path={} reason={}",
                        job.id(), job.filePath(), ex.toString());
                continue;
            }
            if (exportFileService.deletePublished(file) && exportJobMapper.markExpired(job.id(), now) == 1) {
                exportProgressService.deleteProjection(job.id());
                events.publishEvent(new ExportJobChanged(job.id()));
                log.info("export_job_expired job_id={} file_path={} trace_id={}",
                        job.id(), job.filePath(), TraceIdSupport.currentTraceId());
            }
        }
    }

    /** 孤儿对账（三维审查）：时间宽限 + 活跃租约 + 数据库引用，全部通过才删除。 */
    public void reconcileStaleFiles() {
        Instant threshold = Instant.now().minus(ORPHAN_GRACE);
        int removed = 0;
        for (OrphanCandidate candidate : exportFileService.temporaryCandidatesOlderThan(threshold)) {
            // 临时文件无 DB 引用：活跃租约在岗即保留（长批次 Worker 可能正写它）
            if (exportJobAttemptMapper.countRunningWithValidLease(
                    candidate.jobId(), candidate.attemptNo(), LocalDateTime.now()) == 0) {
                exportFileService.deleteQuietly(candidate.absolutePath());
                removed++;
            }
        }
        for (OrphanCandidate candidate : exportFileService.finalCandidatesOlderThan(threshold)) {
            // 正式文件：失败 Attempt 的证据有引用即保留；未登记且无活跃执行（发布后崩溃窗口）才是孤儿
            boolean referenced = exportJobMapper.countByFilePath(candidate.relativePath()) > 0
                    || exportJobAttemptMapper.countByFilePath(candidate.relativePath()) > 0;
            boolean activeLease = exportJobAttemptMapper.countRunningWithValidLease(
                    candidate.jobId(), candidate.attemptNo(), LocalDateTime.now()) > 0;
            if (!referenced && !activeLease && exportFileService.deletePublished(candidate.absolutePath())) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("export_orphan_files_reconciled removed={} trace_id={}",
                    removed, TraceIdSupport.currentTraceId());
        }
    }
}
