package com.example.exportflow.export.service;

import com.example.exportflow.common.web.error.BusinessException;
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
import com.example.exportflow.export.mapper.ExportJobAttemptMapper;
import com.example.exportflow.export.mapper.ExportJobMapper;
import com.example.exportflow.export.mapper.ExportOrderMapper;
import com.example.exportflow.export.mapper.OutboxEventMapper;
import com.example.exportflow.export.vo.ExportJobAcceptedVO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 导出任务服务：创建入口（幂等判断 + 业务校验 + 同事务写 export_jobs/outbox_events）、列表查询，
 * 以及执行侧状态管理（条件抢占 claimPendingJob、失败收敛 markFailed）。
 * <p>
 * 不发布 RabbitMQ 消息、不写 Redis、不生成文件——Outbox 落库成功即入口职责完成。
 */
@Service
public class ExportJobService {

    private static final Logger log = LoggerFactory.getLogger(ExportJobService.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final DateTimeFormatter JOB_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DEFAULT_FILE_NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 尝试上限：达到后不再可抢占，等待人工重试（第 19 章）。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 抢占时预置的租约时长（分钟）：抢占成功后执行端失联的恢复信号（第 19 章消费）。 */
    private static final int LEASE_MINUTES = 5;

    /** error_message 列宽（VARCHAR(500)），超长截断防 SQL 失败。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    private final ExportJobMapper exportJobMapper;
    private final ExportJobAttemptMapper exportJobAttemptMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final ExportOrderMapper exportOrderMapper;
    private final ObjectMapper objectMapper;
    private final long filterMaxRows;

    public ExportJobService(ExportJobMapper exportJobMapper,
                            ExportJobAttemptMapper exportJobAttemptMapper,
                            OutboxEventMapper outboxEventMapper,
                            ExportOrderMapper exportOrderMapper,
                            ObjectMapper objectMapper,
                            @Value("${export.filter-max-rows:500000}") long filterMaxRows) {
        this.exportJobMapper = exportJobMapper;
        this.exportJobAttemptMapper = exportJobAttemptMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.exportOrderMapper = exportOrderMapper;
        this.objectMapper = objectMapper;
        this.filterMaxRows = filterMaxRows;
    }

    /** 查询导出任务分页列表（当前返回空列表）。 */
    public ExportJobPageResp listJobs(int page, int pageSize) {
        return new ExportJobPageResp(List.of(), 0, page, pageSize);
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
        exportJobMapper.markFailed(jobId, errorCode, message, now);
        exportJobAttemptMapper.markFailed(jobId, errorCode, message, now);
        log.warn("export_job_failed job_id={} error_code={} error_message={} trace_id={}",
                jobId, errorCode, message, TraceIdSupport.currentTraceId());
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
