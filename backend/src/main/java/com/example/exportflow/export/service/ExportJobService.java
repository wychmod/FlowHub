package com.example.exportflow.export.service;

import com.example.exportflow.common.web.error.BusinessException;
import com.example.exportflow.common.web.param.ParamUtils;
import com.example.exportflow.common.web.trace.TraceIdSupport;
import com.example.exportflow.common.web.util.Sha256Utils;
import com.example.exportflow.export.command.CreateExportJobCommand;
import com.example.exportflow.export.command.ExportColumn;
import com.example.exportflow.export.command.ExportSelectionMode;
import com.example.exportflow.export.dto.ExportJobPageResp;
import com.example.exportflow.export.entity.ExportJobEntity;
import com.example.exportflow.export.entity.ExportSelectionSnapshot;
import com.example.exportflow.export.entity.OutboxEventEntity;
import com.example.exportflow.export.error.ExportErrorCode;
import com.example.exportflow.export.event.ExportJobChanged;
import com.example.exportflow.export.event.ExportJobEventPayload;
import com.example.exportflow.export.mapper.ExportJobAttemptMapper;
import com.example.exportflow.export.mapper.ExportJobMapper;
import com.example.exportflow.export.mapper.ExportOrderMapper;
import com.example.exportflow.export.mapper.OutboxEventMapper;
import com.example.exportflow.export.vo.ExportJobAcceptedVO;
import com.example.exportflow.export.vo.ExportJobItemVO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 导出任务服务：创建入口（幂等判断 + 业务校验 + 同事务写 export_jobs/outbox_events）、列表查询，
 * 执行侧状态管理（条件抢占 claimPendingJob、成功/失败终态收敛）与成功文件下载解析。
 * <p>
 * 不发布 RabbitMQ 消息、不写 Redis、不写入/删除文件——文件的分配、发布、解析与删除一律经
 * ExportFileService 受控边界（下载前仅对已受控解析的路径做只读的存在性/大小检查）。
 */
@Service
public class ExportJobService {

    private static final Logger log = LoggerFactory.getLogger(ExportJobService.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final DateTimeFormatter JOB_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DEFAULT_FILE_NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 尝试上限：达到后不再可抢占，等待人工重试。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 抢占时预置的租约时长（分钟）：抢占成功后执行端失联的恢复信号（维护服务消费）。 */
    private static final int LEASE_MINUTES = 5;

    /** error_message 列宽（VARCHAR(500)），超长截断防 SQL 失败。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    private final ExportJobMapper exportJobMapper;
    private final ExportJobAttemptMapper exportJobAttemptMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final ExportOrderMapper exportOrderMapper;
    private final ExportFileService exportFileService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final long filterMaxRows;
    private final long retentionHours;

    public ExportJobService(ExportJobMapper exportJobMapper,
                            ExportJobAttemptMapper exportJobAttemptMapper,
                            OutboxEventMapper outboxEventMapper,
                            ExportOrderMapper exportOrderMapper,
                            ExportFileService exportFileService,
                            ObjectMapper objectMapper,
                            ApplicationEventPublisher events,
                            @Value("${export.filter-max-rows:500000}") long filterMaxRows,
                            @Value("${export.files.retention-hours:24}") long retentionHours) {
        this.exportJobMapper = exportJobMapper;
        this.exportJobAttemptMapper = exportJobAttemptMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.exportOrderMapper = exportOrderMapper;
        this.exportFileService = exportFileService;
        this.objectMapper = objectMapper;
        this.events = events;
        this.filterMaxRows = filterMaxRows;
        this.retentionHours = retentionHours;
    }

    /** 查询导出任务分页列表（be-td.md 4.6）：可选 status 过滤，派生 progress_percent/downloadable，按创建时间倒序。 */
    public ExportJobPageResp listJobs(int page, int pageSize, String status) {
        String statusFilter = normalizeStatusFilter(status);
        List<ExportJobItemVO> items = exportJobMapper.findPage(pageSize, (page - 1) * pageSize, statusFilter).stream()
                .map(this::toListItem)
                .toList();
        return new ExportJobPageResp(items, exportJobMapper.countAll(statusFilter), page, pageSize);
    }

    /** 列表接口允许的 status 过滤值白名单（be-td.md 4.6 契约）。 */
    private static final Set<String> LIST_FILTER_STATUSES =
            Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "EXPIRED");

    /** 列表 status 过滤参数归一：空白视为未传；大小写不敏感白名单校验，非法抛 400。 */
    private static String normalizeStatusFilter(String raw) {
        String status = ParamUtils.trimToNull(raw);
        if (status == null) {
            return null;
        }
        String upper = status.toUpperCase(Locale.ROOT);
        if (!LIST_FILTER_STATUSES.contains(upper)) {
            throw BusinessException.validation("status 仅支持 PENDING/RUNNING/SUCCEEDED/FAILED/EXPIRED");
        }
        return upper;
    }

    /** 实体 → 列表行 VO：派生 progress_percent（复用事件口径）与 downloadable（SUCCEEDED 且未过期）。 */
    private ExportJobItemVO toListItem(ExportJobEntity job) {
        long processed = job.processedRows() == null ? 0 : job.processedRows();
        long total = job.filterCount() == null ? 0 : job.filterCount();
        boolean notExpired = job.expiredAt() == null || job.expiredAt().isAfter(LocalDateTime.now());
        return new ExportJobItemVO(
                job.id(),
                job.jobNo(),
                job.status(),
                job.version(),
                job.processedRows(),
                job.filterCount(),
                ExportJobEventPayload.progressPercent(job.status(), processed, total),
                STATUS_SUCCEEDED.equals(job.status()) && notExpired,
                job.fileSizeBytes(),
                job.errorCode(),
                job.errorMessage(),
                job.requestedFileName(),
                job.createdAt(),
                job.finishedAt(),
                job.expiredAt());
    }

    /**
     * 创建导出任务：规范化后先查幂等（命中复用），再做业务校验，最后同事务写任务与 Outbox。
     *
     * @param command        已规范化的创建命令
     * @param idempotencyKey 请求头幂等键（非空，Controller 已校验）
     */
    @Transactional
    public ExportJobAcceptedVO createJob(CreateExportJobCommand command, String idempotencyKey) {
        String requestHash = requestHash(command);
        ExportJobEntity existing = exportJobMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return reuseOrConflict(existing, requestHash);
        }

        ExportSelectionSnapshot snapshot = validatedSnapshot(command);
        LocalDateTime now = LocalDateTime.now();
        ExportJobEntity job = new ExportJobEntity(
                null,
                generateJobNo(now),
                STATUS_PENDING,
                snapshot.maxOrderIdAtCreate(),
                idempotencyKey,
                requestHash,
                snapshot.filterCount(),
                writeJson(command.criteria()),
                writeJson(selectedOrderIds(command)),
                writeJson(columnKeys(command)),
                command.fileName() != null ? command.fileName() : defaultFileName(now),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now);
        try {
            exportJobMapper.insert(job);
        } catch (DuplicateKeyException ex) {
            // 并发创建撞幂等键唯一约束：降级为按已有任务判定复用或冲突
            ExportJobEntity winner = exportJobMapper.selectByIdempotencyKey(idempotencyKey);
            if (winner != null) {
                return reuseOrConflict(winner, requestHash);
            }
            throw ex;
        }
        // record 无 setter 不回填自增主键，按唯一幂等键取回持久化实体（含 job_id）供 Outbox 与响应使用
        ExportJobEntity persisted = exportJobMapper.selectByIdempotencyKey(idempotencyKey);
        outboxEventMapper.insert(outboxEvent(persisted, command));
        return toAcceptedVO(persisted);
    }

    /**
     * 条件抢占：CAS 将 PENDING 任务置 RUNNING，并同事务插入 RUNNING Attempt（两步原子，不可拆分）。
     *
     * @return true = 抢占成功（Attempt 已建，调用方可执行）；false = 重复投递/已被抢占/达尝试上限，无任何副作用
     */
    @Transactional
    public boolean claimPendingJob(long jobId) {
        LocalDateTime now = LocalDateTime.now();
        // 仅 PENDING 且未达上限可转换，受影响行数即执行权裁决；0 行时事务内无其他写，等同无副作用
        if (exportJobMapper.claimPending(jobId, MAX_ATTEMPTS, now, now.plusMinutes(LEASE_MINUTES)) != 1) {
            log.debug("export_job_claim_missed job_id={} trace_id={}", jobId, TraceIdSupport.currentTraceId());
            return false;
        }
        // 必须与抢占同一事务：任一方向拆开都会留下脏事实（孤儿 RUNNING 或伪执行记录）
        exportJobAttemptMapper.insertRunning(jobId, now);
        log.info("export_job_claimed job_id={} lease_expires_at={} trace_id={}",
                jobId, now.plusMinutes(LEASE_MINUTES), TraceIdSupport.currentTraceId());
        return true;
    }

    /**
     * 收敛失败：Job 与当前 RUNNING Attempt 同事务置 FAILED，回填错误与结束时间（执行服务在业务异常时调用）。
     * <p>
     * UPDATE 带 status='RUNNING' 单向条件，0 行 = 已被其他路径推进，保持库内既有事实。
     */
    @Transactional
    public void markFailed(long jobId, String errorCode, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        String message = truncateMessage(errorMessage);
        // updated = UPDATE 受影响行数（主键条件，至多 1 行）：1 = 本次收敛生效，0 = 已被其他路径推进
        int updated = exportJobMapper.markFailed(jobId, errorCode, message, now);
        // Attempt 侧返回值不判断：Job 是状态事实源，Attempt 仅审计记录（无 RUNNING 行不影响收敛事实）
        exportJobAttemptMapper.markFailed(jobId, errorCode, message, now);
        // DB 事实落定后发布变化事件（AFTER_COMMIT 提交后广播）；0 行不发伪事件
        if (updated == 1) {
            events.publishEvent(new ExportJobChanged(jobId));
        }
        log.warn("export_job_failed job_id={} error_code={} error_message={} trace_id={}",
                jobId, errorCode, message, TraceIdSupport.currentTraceId());
    }

    /**
     * 成功收敛：Job 与当前 RUNNING Attempt 同事务置 SUCCEEDED 并登记产物（发布协议第 3 步）。
     * <p>
     * UPDATE 带 status='RUNNING' 单向条件；Job 0 行抛异常回滚，调用方须补偿删除已发布文件
     * （文件先成功、数据库失败时用户从未见过 SUCCEEDED）。
     */
    @Transactional
    public void markSucceeded(long jobId, String filePath, long fileSizeBytes) {
        LocalDateTime now = LocalDateTime.now();
        if (exportJobMapper.markSucceeded(jobId, filePath, fileSizeBytes, now, now.plusHours(retentionHours)) != 1) {
            throw new IllegalStateException("成功终态推进失败（任务非 RUNNING），job_id=" + jobId);
        }
        // Attempt 仅审计记录（与 markFailed 对称：Job 是状态事实源，无 RUNNING 行不影响收敛事实）
        exportJobAttemptMapper.markSucceeded(jobId, filePath, fileSizeBytes, now);
        // DB 事实落定后发布变化事件（AFTER_COMMIT 提交后广播 job.succeeded）
        events.publishEvent(new ExportJobChanged(jobId));
        log.info("export_job_succeeded job_id={} file_path={} file_size_bytes={} trace_id={}",
                jobId, filePath, fileSizeBytes, TraceIdSupport.currentTraceId());
    }

    /**
     * 解析可下载文件（下载规则）：仅 SUCCEEDED 且未过期的任务放行，
     * 按 DB 登记的相对路径重过受控校验后才返回；文件丢失不重新生成（重建会改变
     * 原 Attempt 的数据边界与证据），返回明确错误让用户重新创建任务。
     */
    public DownloadableExportFile getDownloadableFile(long jobId) {
        ExportJobEntity job = exportJobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ExportErrorCode.EXPORT_JOB_NOT_FOUND);
        }
        // 文件存在只是发布过程的一部分，Job 状态才是向用户公开下载能力的业务事实
        if (!STATUS_SUCCEEDED.equals(job.status())) {
            throw new BusinessException(ExportErrorCode.EXPORT_JOB_NOT_DOWNLOADABLE);
        }
        if (job.expiredAt() != null && job.expiredAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ExportErrorCode.EXPORT_FILE_EXPIRED);
        }
        if (job.filePath() == null || job.filePath().isBlank()) {
            throw new BusinessException(ExportErrorCode.EXPORT_FILE_MISSING);
        }
        Path file;
        try {
            file = exportFileService.resolvePersisted(job.filePath());
        } catch (IllegalArgumentException ex) {
            // DB 记录被污染（绝对路径/.. /符号链接）：结构化错误而非 500
            throw new BusinessException(ExportErrorCode.EXPORT_PATH_INVALID);
        }
        if (!Files.exists(file)) {
            throw new BusinessException(ExportErrorCode.EXPORT_FILE_MISSING);
        }
        // 大小优先用发布时登记的证据值，登记缺失时按磁盘实际补齐
        long sizeBytes = job.fileSizeBytes() != null ? job.fileSizeBytes() : fileSizeQuietly(file);
        return new DownloadableExportFile(file, sizeBytes, displayNameOf(job));
    }

    /** 展示文件名 = 创建时清洗过的 file_name（补 .xlsx 后缀），仅用于下载响应，不参与磁盘路径。 */
    private static String displayNameOf(ExportJobEntity job) {
        String name = job.requestedFileName() == null || job.requestedFileName().isBlank()
                ? "export-" + job.jobNo()
                : job.requestedFileName();
        return name.endsWith(".xlsx") ? name : name + ".xlsx";
    }

    private static long fileSizeQuietly(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * 人工重试：FAILED → PENDING 条件重置，并同事务写入新 Outbox 事件重走可靠投递管道。
     * <p>
     * 失败 Attempt 历史一条不删（回答「曾怎样失败、何时再次执行」）；重试本身不执行任务——
     * 消费端仍需经历条件抢占，attempt_count 在抢占时递增（不是重试点击次数）。
     */
    @Transactional
    public ExportJobAcceptedVO retry(long jobId) {
        ExportJobEntity job = exportJobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ExportErrorCode.EXPORT_JOB_NOT_FOUND);
        }
        if (job.attemptCount() == null || job.attemptCount() >= MAX_ATTEMPTS) {
            throw new BusinessException(ExportErrorCode.EXPORT_JOB_NOT_RETRYABLE);
        }
        // 条件重置（并发重试/状态已推进时 0 行拒绝）；重试与新 Outbox 同事务——状态提交后崩溃也不会静默遗忘
        if (exportJobMapper.retry(jobId, MAX_ATTEMPTS, LocalDateTime.now()) != 1) {
            throw new BusinessException(ExportErrorCode.EXPORT_JOB_NOT_RETRYABLE);
        }
        outboxEventMapper.insert(retryOutboxEvent(jobId));
        log.info("export_job_retried job_id={} attempt_count={} trace_id={}",
                jobId, job.attemptCount(), TraceIdSupport.currentTraceId());
        return new ExportJobAcceptedVO(jobId, job.jobNo(), STATUS_PENDING, job.filterCount());
    }

    /** 重试 Outbox 事件：复用创建事件契约（消息只携带执行定位，消费端按库内 Job 抢占执行）。 */
    private OutboxEventEntity retryOutboxEvent(long jobId) {
        return new OutboxEventEntity(
                null,
                OutboxEventEntity.AGGREGATE_TYPE_EXPORT_JOB,
                jobId,
                OutboxEventEntity.EVENT_TYPE_EXPORT_JOB_CREATED,
                writeJson(new RetriedPayload(jobId, true)),
                TraceIdSupport.currentTraceId(),
                LocalDateTime.now());
    }

    /** 重试事件载荷（JSON 契约固定）。 */
    private record RetriedPayload(
            @JsonProperty("job_id") long jobId,
            @JsonProperty("retry") boolean retry) {
    }

    /** 幂等命中判定：请求内容一致复用原任务，不一致返回 409 冲突。 */
    private static ExportJobAcceptedVO reuseOrConflict(ExportJobEntity existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new BusinessException(ExportErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return toAcceptedVO(existing);
    }

    /** 业务校验 + 一致性快照：命中 0 行按模式报错、筛选超上限拒绝；单查询取范围 COUNT 与高水位。 */
    private ExportSelectionSnapshot validatedSnapshot(CreateExportJobCommand command) {
        ExportSelectionSnapshot snapshot = exportOrderMapper.snapshotByCriteria(command.criteria());
        if (command.mode() == ExportSelectionMode.SELECTED_IDS) {
            if (snapshot.filterCount() == 0) {
                throw new BusinessException(ExportErrorCode.EXPORT_SELECTION_EMPTY);
            }
        } else {
            if (snapshot.filterCount() == 0) {
                throw new BusinessException(ExportErrorCode.EXPORT_FILTER_ZERO_ROWS);
            }
            if (snapshot.filterCount() > filterMaxRows) {
                throw new BusinessException(ExportErrorCode.EXPORT_FILTER_TOO_MANY_ROWS,
                        "筛选命中订单数 " + snapshot.filterCount() + " 超过上限 " + filterMaxRows);
            }
        }
        return snapshot;
    }

    /** 构建同事务写入的 Outbox 事件（payload 必含 job_id/job_no/request_snapshot/columns/file_name/trace_id）。 */
    private OutboxEventEntity outboxEvent(ExportJobEntity job, CreateExportJobCommand command) {
        CreatedPayload payload = new CreatedPayload(
                job.id(),
                job.jobNo(),
                command.criteria(),
                columnKeys(command),
                job.requestedFileName(),
                TraceIdSupport.currentTraceId());
        return new OutboxEventEntity(
                null,
                OutboxEventEntity.AGGREGATE_TYPE_EXPORT_JOB,
                job.id(),
                OutboxEventEntity.EVENT_TYPE_EXPORT_JOB_CREATED,
                writeJson(payload),
                TraceIdSupport.currentTraceId(),
                job.createdAt());
    }

    /** Outbox 事件载荷（JSON 契约固定，重放消费方依赖这些字段）。 */
    private record CreatedPayload(
            @JsonProperty("job_id") Long jobId,
            @JsonProperty("job_no") String jobNo,
            @JsonProperty("request_snapshot") Object requestSnapshot,
            @JsonProperty("columns") List<String> columns,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("trace_id") String traceId) {
    }

    private static List<String> columnKeys(CreateExportJobCommand command) {
        return command.columns().stream().map(ExportColumn::key).toList();
    }

    private static List<Long> selectedOrderIds(CreateExportJobCommand command) {
        return command.criteria().ids();
    }

    private static String generateJobNo(LocalDateTime now) {
        String random = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);
        return "EXP" + JOB_NO_DATE.format(now) + "-" + random;
    }

    private static String defaultFileName(LocalDateTime now) {
        return "export-" + DEFAULT_FILE_NAME_TIME.format(now);
    }

    /** error_message 按列宽截断（异常信息可能超长，防 INSERT/UPDATE 失败）。 */
    private static String truncateMessage(String message) {
        if (message == null || message.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    /** 规范化 Command 的 SHA-256 指纹（同义请求规范化结果相同 → hash 相同）。 */
    private String requestHash(CreateExportJobCommand command) {
        return Sha256Utils.sha256Hex(writeJson(command));
    }

    /** 对象序列化为 JSON（筛选快照/勾选与列落库、Outbox payload、hash 输入共用）。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            // 序列化失败属编程/配置错误而非用户输入问题，fail-fast 抛 500 语义异常，不转业务 400
            throw new IllegalStateException("JSON 序列化失败：" + value, ex);
        }
    }

    /** 持久化实体转 202 受理响应（job_id/job_no/status/total_rows，契约见 be-td.md 4.5）。 */
    private static ExportJobAcceptedVO toAcceptedVO(ExportJobEntity job) {
        return new ExportJobAcceptedVO(job.id(), job.jobNo(), job.status(), job.filterCount());
    }
}
