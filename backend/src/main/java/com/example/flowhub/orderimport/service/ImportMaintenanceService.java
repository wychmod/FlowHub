package com.example.flowhub.orderimport.service;

import com.example.flowhub.common.web.trace.MdcScope;
import com.example.flowhub.common.web.trace.TraceIdSupport;
import com.example.flowhub.orderimport.entity.ImportJobEntity;
import com.example.flowhub.orderimport.event.ImportJobChanged;
import com.example.flowhub.orderimport.mapper.ImportJobAttemptMapper;
import com.example.flowhub.orderimport.mapper.ImportJobMapper;
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
 * 导入维护服务（docs/order-import-design.md §7.6/B16）：启动恢复租约失效的 RUNNING，定时清理过期文件与孤儿对账。
 * <p>
 * 与导出一致：调度只提供执行时机，可靠性来自条件更新与「删除成功才推进状态」的收敛纪律；全部动作幂等。
 */
@Service
public class ImportMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(ImportMaintenanceService.class);

    /** 恢复收敛的稳定错误码（非业务失败，是服务中断遗留，供用户决策是否重试）。 */
    public static final String ERROR_CODE_SERVICE_RESTARTED = "SERVICE_RESTARTED";
    /** 恢复收敛的 error_message（列宽 500 内）。 */
    private static final String RESTART_MESSAGE = "执行进程中断（服务重启或失联），任务按租约到期收敛，可人工重试";
    /** 孤儿文件宽限期：文件早于该时点才进入对账候选（刚创建的活跃文件不被误判）。 */
    private static final Duration ORPHAN_GRACE = Duration.ofHours(1);
    /** 过期清理单轮扫描上限。 */
    private static final int EXPIRED_BATCH_LIMIT = 100;

    private final ImportJobMapper importJobMapper;
    private final ImportJobAttemptMapper importJobAttemptMapper;
    private final ImportFileService importFileService;
    private final ImportProgressService importProgressService;
    private final ApplicationEventPublisher events;

    public ImportMaintenanceService(ImportJobMapper importJobMapper,
                                    ImportJobAttemptMapper importJobAttemptMapper,
                                    ImportFileService importFileService,
                                    ImportProgressService importProgressService,
                                    ApplicationEventPublisher events) {
        this.importJobMapper = importJobMapper;
        this.importJobAttemptMapper = importJobAttemptMapper;
        this.importFileService = importFileService;
        this.importProgressService = importProgressService;
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

    /** 定时维护：过期文件下架 + 孤儿文件对账（cron 可配，测试 yml 置 "-" 静默）。 */
    @Scheduled(cron = "${import.cleanup-cron:0 0 * * * *}")
    public void scheduledMaintenance() {
        try (MdcScope ignored = MdcScope.withTraceId(TraceIdSupport.currentOrCreate())) {
            cleanupExpiredImports();
            reconcileStaleFiles();
        }
    }

    /** 恢复收敛：Attempt 先行、Job 收尾（同一租约失效前置条件，同事务回滚保证两表结论一致）。 */
    private void recoverRunningJobsInScope() {
        LocalDateTime now = LocalDateTime.now();
        importJobAttemptMapper.recoverExpiredRunning(now, ERROR_CODE_SERVICE_RESTARTED, RESTART_MESSAGE);
        int recovered = importJobMapper.recoverExpiredRunning(now, ERROR_CODE_SERVICE_RESTARTED, RESTART_MESSAGE);
        if (recovered > 0) {
            log.warn("import_jobs_recovered job_count={} error_code={} trace_id={}",
                    recovered, ERROR_CODE_SERVICE_RESTARTED, TraceIdSupport.currentTraceId());
        }
    }

    /**
     * 过期清理（删除即事实）：SUCCEEDED/PARTIAL 的上传原件与错误报告删除成功才推进 EXPIRED，
     * 失败/路径非法保持原状下一轮再试；条件更新 0 行不被并发推进时发布事件。
     */
    public void cleanupExpiredImports() {
        LocalDateTime now = LocalDateTime.now();
        List<ImportJobEntity> expired = importJobMapper.findExpired(now, EXPIRED_BATCH_LIMIT);
        for (ImportJobEntity job : expired) {
            if (!deleteJobFiles(job)) {
                continue;
            }
            if (importJobMapper.markExpired(job.id(), now) == 1) {
                importProgressService.deleteProjection(job.id());
                events.publishEvent(new ImportJobChanged(job.id()));
                log.info("import_job_expired job_id={} file_path={} trace_id={}",
                        job.id(), job.filePath(), TraceIdSupport.currentTraceId());
            }
        }
    }

    /** 删除任务承载的正式文件（上传原件 + 错误报告，若有）；任一删除失败或路径非法返回 false 保持原状。 */
    private boolean deleteJobFiles(ImportJobEntity job) {
        try {
            Path upload = importFileService.resolvePersisted(job.filePath());
            if (!importFileService.deletePersisted(upload)) {
                return false;
            }
            if (job.errorReportPath() != null && !job.errorReportPath().isBlank()) {
                Path report = importFileService.resolvePersisted(job.errorReportPath());
                if (!importFileService.deletePersisted(report)) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException ex) {
            log.warn("import_expire_path_invalid job_id={} file_path={} reason={}",
                    job.id(), job.filePath(), ex.toString());
            return false;
        }
    }

    /**
     * 孤儿对账（三维审查）：时间宽限 + 数据库引用 + 活跃租约，全部通过才删除。
     * 未被登记且无活跃执行的文件均为「受理/执行崩溃在登记前落盘」的残留。
     */
    public void reconcileStaleFiles() {
        Instant threshold = Instant.now().minus(ORPHAN_GRACE);
        int removed = 0;
        for (ImportFileService.ImportOrphanCandidate candidate : importFileService.orphanCandidatesOlderThan(threshold)) {
            boolean referenced = importJobMapper.countByFilePath(candidate.relativePath()) > 0;
            boolean activeLease = hasActiveLease(candidate);
            if (!referenced && !activeLease && importFileService.deletePersisted(candidate.absolutePath())) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("import_orphan_files_reconciled removed={} trace_id={}",
                    removed, TraceIdSupport.currentTraceId());
        }
    }

    /** 活跃租约判定（第三维）：错误报告候选按 jobNo+attemptNo 判执行仍在岗（报告已写、markPartial 未登记的窗口）。 */
    private boolean hasActiveLease(ImportFileService.ImportOrphanCandidate candidate) {
        if (candidate.attemptNo() == null) {
            // 上传原件无 per-attempt 语义：Job 存在即被 file_path 引用（第二维已覆盖），不存在则不可能有活跃执行
            return false;
        }
        ImportJobEntity job = importJobMapper.selectByJobNo(candidate.jobNo());
        if (job == null) {
            return false;
        }
        return importJobAttemptMapper.countRunningWithValidLease(job.id(), candidate.attemptNo(),
                LocalDateTime.now()) > 0;
    }
}