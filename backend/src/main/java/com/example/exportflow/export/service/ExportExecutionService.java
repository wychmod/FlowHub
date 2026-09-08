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
 * 并把业务失败收敛为可查询事实。
 * <p>
 * 数据读取管道见第 15 章；进度推进/通知见第 16 章（ExportProgressService）；
 * 流式写文件见第 17 章（ExcelExportWriter）；文件发布与成功终态随第 18 章接入。
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
        } catch (Exception ex) {
            String message = ExceptionUtils.messageOrTypeName(ex);
            exportJobService.markFailed(jobId, ERROR_CODE_FILE_GENERATION, message);
            exportProgressService.refreshProjection(jobId);
            log.warn("export_execution_failed job_id={} error_code={} reason={}",
                    jobId, ERROR_CODE_FILE_GENERATION, message);
        }
    }

    /**
     * 执行体：加载任务快照 → Keyset 分批读取 → SXSSF 流式写业务临时文件。
     * <p>
     * 游标推进顺序即失败屏障：查询 → writeBatch（本批真实进入 Workbook）→ 累计 → 推进 lastId → 落进度；
     * 空批或不足一批结束。写盘失败删除半成品临时文件；成功终态（发布/下载）随第 18 章接入。
     */
    private void runJob(long jobId) throws IOException {
        ExportJobEntity job = exportJobMapper.selectById(jobId);
        if (job == null || !STATUS_RUNNING.equals(job.status())) {
            throw new IllegalStateException("任务不存在或未处于 RUNNING，job_id=" + jobId);
        }
        OrderCriteria criteria = readCriteria(jobId, job.filterSnapshot());
        List<String> columns = readColumns(jobId, job.selectedColumns());
        Path temporary = exportFileService.temporaryPath(jobId, attemptNo(jobId));
        long processed = 0;
        long lastId = 0;
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
                    // 条件推进事实源（0 行 fail-fast）→ 发事件 → 尽力写投影（第 16 章）
                    exportProgressService.report(jobId, processed, job.filterCount());
                    if (batch.size() < batchSize) {
                        break;
                    }
                }
            }
            log.info("export_job_file_written job_id={} processed={} file={} trace_id={}",
                    jobId, processed, temporary, TraceIdSupport.currentTraceId());
            throw new IllegalStateException("成功终态尚未实现（第 18 章），job_id=" + jobId
                    + "，已生成 " + processed + " 行临时文件 " + temporary.getFileName());
        } catch (Exception ex) {
            // 半成品不遗留：写盘失败与「终态未实现」占位失败都必须清理业务临时文件
            exportFileService.deleteQuietly(temporary);
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
