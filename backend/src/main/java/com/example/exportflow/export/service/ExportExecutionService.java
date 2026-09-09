package com.example.exportflow.export.service;

import com.example.exportflow.common.web.trace.TraceIdSupport;
import com.example.exportflow.common.web.util.ExceptionUtils;
import com.example.exportflow.export.entity.ExportJobEntity;
import com.example.exportflow.export.entity.ExportOrderRow;
import com.example.exportflow.export.excel.ExcelExportWriter;
import com.example.exportflow.export.mapper.ExportJobAttemptMapper;
import com.example.exportflow.export.mapper.ExportJobMapper;
import com.example.exportflow.export.mapper.ExportOrderMapper;
import com.example.exportflow.order.query.OrderCriteria;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 导出执行服务：消费端抢占成功后调用，按任务快照 Keyset 批量读取订单、SXSSF 流式写 Excel，
 * 按发布协议原子移动为正式文件并收敛成功终态，业务失败收敛为可查询事实。
 * <p>
 * 数据读取管道、进度推进/通知（ExportProgressService）、流式写文件（ExcelExportWriter）、
 * 文件发布与成功终态（ExportFileService/发布协议）由各自组件负责。
 */
@Service
public class ExportExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExportExecutionService.class);

    /** 执行失败统一错误码（export_jobs/export_job_attempts 的 error_code 分类）。 */
    public static final String ERROR_CODE_FILE_GENERATION = "FILE_GENERATION_FAILED";

    private static final String STATUS_RUNNING = "RUNNING";

    private final ExportJobService exportJobService;
    private final ExportProgressService exportProgressService;
    private final ExportFileService exportFileService;
    private final ExcelExportWriter excelExportWriter;
    private final ExportJobMapper exportJobMapper;
    private final ExportJobAttemptMapper exportJobAttemptMapper;
    private final ExportOrderMapper exportOrderMapper;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public ExportExecutionService(ExportJobService exportJobService,
                                  ExportProgressService exportProgressService,
                                  ExportFileService exportFileService,
                                  ExcelExportWriter excelExportWriter,
                                  ExportJobMapper exportJobMapper,
                                  ExportJobAttemptMapper exportJobAttemptMapper,
                                  ExportOrderMapper exportOrderMapper,
                                  ObjectMapper objectMapper,
                                  @Value("${export.execution.batch-size:1000}") int batchSize) {
        this.exportJobService = exportJobService;
        this.exportProgressService = exportProgressService;
        this.exportFileService = exportFileService;
        this.excelExportWriter = excelExportWriter;
        this.exportJobMapper = exportJobMapper;
        this.exportJobAttemptMapper = exportJobAttemptMapper;
        this.exportOrderMapper = exportOrderMapper;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    /**
     * 执行任务并把业务异常内部收敛为 Job/Attempt FAILED，不向 Consumer 外抛
     * （失败已成为可查询事实，随后正常 Ack，避免同一条消息无限重放）；
     * 收敛后按 DB 事实刷新投影并经事件广播终态。
     * <p>
     * markFailed 自身失败属基础设施故障，异常穿出由 Consumer 走不 Ack 路径。
     */
    public void execute(long jobId) {
        try {
            runJob(jobId);
            // 成功终态后按 DB 事实刷新投影（SUCCEEDED → percent 100）
            exportProgressService.refreshProjection(jobId);
        } catch (Exception ex) {
            String message = ExceptionUtils.messageOrTypeName(ex);
            exportJobService.markFailed(jobId, ERROR_CODE_FILE_GENERATION, message);
            exportProgressService.refreshProjection(jobId);
            log.warn("export_execution_failed job_id={} error_code={} reason={}",
                    jobId, ERROR_CODE_FILE_GENERATION, message);
        }
    }

    /**
     * 执行体：加载任务快照 → Keyset 分批读取 → SXSSF 流式写业务临时文件 → 发布协议收敛成功终态。
     * <p>
     * 游标推进顺序即失败屏障：查询 → writeBatch（本批真实进入 Workbook）→ 累计 → 推进 lastId → 落进度；
     * 空批或不足一批结束。发布协议固定顺序：原子移动发布 → MySQL 事务登记成功 →
     * 失败补偿删除（文件先成功、数据库失败时用户从未见过 SUCCEEDED）。
     */
    private void runJob(long jobId) throws IOException {
        ExportJobEntity job = exportJobMapper.selectById(jobId);
        if (job == null || !STATUS_RUNNING.equals(job.status())) {
            throw new IllegalStateException("任务不存在或未处于 RUNNING，job_id=" + jobId);
        }
        OrderCriteria criteria = readCriteria(jobId, job.filterSnapshot());
        List<String> columns = readColumns(jobId, job.selectedColumns());
        int attemptNo = attemptNo(jobId);
        Path temporary = exportFileService.temporaryPath(jobId, attemptNo);
        long processed = 0;
        long lastId = 0;
        PublishedFile published = null;
        try {
            try (ExcelExportWriter.WorkbookSession workbook = excelExportWriter.open(temporary, columns)) {
                while (true) {
                    List<ExportOrderRow> batch = exportOrderMapper.findBatch(
                            lastId, job.maxOrderIdAtCreate(), batchSize, criteria);
                    if (batch.isEmpty()) {
                        break;
                    }
                    // 顺序即屏障：writeBatch 成功返回才允许累计/推进/报进度，失败批次不得伪装已处理
                    workbook.writeBatch(batch);
                    processed += batch.size();
                    lastId = batch.getLast().id();
                    // 条件推进事实源（0 行 fail-fast）→ 发事件 → 尽力写投影
                    exportProgressService.report(jobId, processed, job.filterCount());
                    if (batch.size() < batchSize) {
                        break;
                    }
                }
            }
            log.info("export_job_file_written job_id={} processed={} file={} trace_id={}",
                    jobId, processed, temporary, TraceIdSupport.currentTraceId());
            // 发布协议：先原子移动发布文件，再同事务提交成功状态；顺序不可颠倒
            published = exportFileService.publish(temporary, attemptNo);
            temporary = null; // 所有权转移：临时文件已被移动，后续失败只补偿正式文件
            exportJobService.markSucceeded(jobId, published.relativePath(), published.sizeBytes());
        } catch (Exception ex) {
            // 失败补偿：已发布未登记的正式文件不留孤儿；半成品临时文件一并清理
            if (published != null) {
                exportFileService.deletePublished(published.absolutePath());
            }
            if (temporary != null) {
                exportFileService.deleteQuietly(temporary);
            }
            throw ex;
        }
    }

    /** 快照重建：filter_snapshot 直存 OrderCriteria 序列化 JSON，反序列化即得完整筛选条件。 */
    private OrderCriteria readCriteria(long jobId, String filterSnapshot) {
        try {
            return objectMapper.readValue(filterSnapshot, OrderCriteria.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("筛选快照反序列化失败，job_id=" + jobId, ex);
        }
    }

    /** 列快照重建：selected_columns JSON → 列 key 列表（顺序即表头顺序，Writer 二次校验白名单）。 */
    private List<String> readColumns(long jobId, String selectedColumns) {
        try {
            return objectMapper.readValue(selectedColumns, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("导出列快照反序列化失败，job_id=" + jobId, ex);
        }
    }

    /** 当前 RUNNING Attempt 序号（claim 同事务插入，正常恒存在；0 兜底直插等边缘场景）。 */
    private int attemptNo(long jobId) {
        Integer attemptNo = exportJobAttemptMapper.selectRunningAttemptNo(jobId);
        return attemptNo == null ? 0 : attemptNo;
    }
}
