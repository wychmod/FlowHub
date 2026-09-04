package com.example.exportflow.export.service;

import com.example.exportflow.common.web.error.BusinessException;
import com.example.exportflow.common.web.trace.TraceIdSupport;
import com.example.exportflow.common.web.util.Sha256Utils;
import com.example.exportflow.export.command.CreateExportJobCommand;
import com.example.exportflow.export.command.ExportColumn;
import com.example.exportflow.export.command.ExportSelectionMode;
import com.example.exportflow.export.dto.ExportJobPageResp;
import com.example.exportflow.export.entity.ExportJobEntity;
import com.example.exportflow.export.entity.OutboxEventEntity;
import com.example.exportflow.export.error.ExportErrorCode;
import com.example.exportflow.export.mapper.ExportJobMapper;
import com.example.exportflow.export.mapper.OutboxEventMapper;
import com.example.exportflow.export.vo.ExportJobAcceptedVO;
import com.example.exportflow.order.mapper.OrderMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 导出任务服务：创建入口（幂等判断 + 业务校验 + 同事务写 export_jobs/outbox_events）与列表查询。
 * <p>
 * 不发布 RabbitMQ 消息、不写 Redis、不生成文件——Outbox 落库成功即入口职责完成。
 */
@Service
public class ExportJobService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String AGGREGATE_TYPE_EXPORT_JOB = "EXPORT_JOB";
    private static final String EVENT_TYPE_EXPORT_JOB_CREATED = "EXPORT_JOB_CREATED";
    private static final DateTimeFormatter JOB_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DEFAULT_FILE_NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ExportJobMapper exportJobMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;
    private final long filterMaxRows;

    public ExportJobService(ExportJobMapper exportJobMapper,
                            OutboxEventMapper outboxEventMapper,
                            OrderMapper orderMapper,
                            ObjectMapper objectMapper,
                            @Value("${export.filter-max-rows:500000}") long filterMaxRows) {
        this.exportJobMapper = exportJobMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.orderMapper = orderMapper;
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

        long totalRows = countMatchedRows(command);
        LocalDateTime now = LocalDateTime.now();
        ExportJobEntity job = new ExportJobEntity(
                null,
                generateJobNo(now),
                STATUS_PENDING,
                orderMapper.selectMaxId(),
                idempotencyKey,
                requestHash,
                totalRows,
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

    /** 幂等命中判定：请求内容一致复用原任务，不一致返回 409 冲突。 */
    private static ExportJobAcceptedVO reuseOrConflict(ExportJobEntity existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new BusinessException(ExportErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return toAcceptedVO(existing);
    }

    /** 业务校验：命中 0 行按模式报错；筛选超过配置上限拒绝。 */
    private long countMatchedRows(CreateExportJobCommand command) {
        long count = orderMapper.countByCriteria(command.criteria());
        if (command.mode() == ExportSelectionMode.SELECTED_IDS) {
            if (count == 0) {
                throw new BusinessException(ExportErrorCode.EXPORT_SELECTION_EMPTY);
            }
        } else {
            if (count == 0) {
                throw new BusinessException(ExportErrorCode.EXPORT_FILTER_ZERO_ROWS);
            }
            if (count > filterMaxRows) {
                throw new BusinessException(ExportErrorCode.EXPORT_FILTER_TOO_MANY_ROWS,
                        "筛选命中订单数 " + count + " 超过上限 " + filterMaxRows);
            }
        }
        return count;
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
                AGGREGATE_TYPE_EXPORT_JOB,
                job.id(),
                EVENT_TYPE_EXPORT_JOB_CREATED,
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
