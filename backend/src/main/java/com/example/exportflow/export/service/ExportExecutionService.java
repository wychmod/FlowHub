package com.example.exportflow.export.service;

import com.example.exportflow.common.web.trace.TraceIdSupport;
import com.example.exportflow.common.web.util.ExceptionUtils;
import com.example.exportflow.export.entity.ExportJobEntity;
import com.example.exportflow.export.entity.ExportOrderRow;
import com.example.exportflow.export.mapper.ExportJobMapper;
import com.example.exportflow.export.mapper.ExportOrderMapper;
import com.example.exportflow.order.query.OrderCriteria;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出执行服务：消费端抢占成功后调用，按任务快照 Keyset 批量读取订单并把业务失败收敛为可查询事实。
 * <p>
 * 数据读取管道见第 15 章（快照重建 + 高水位上界 + id 游标）；SXSSF 写 Excel 与成功终态按第 17/18 章接入。
 */
@Service
public class ExportExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExportExecutionService.class);

    /** 执行失败统一错误码（export_jobs/export_job_attempts 的 error_code 分类）。 */
    public static final String ERROR_CODE_FILE_GENERATION = "FILE_GENERATION_FAILED";

    private static final String STATUS_RUNNING = "RUNNING";

    private final ExportJobService exportJobService;
    private final ExportJobMapper exportJobMapper;
    private final ExportOrderMapper exportOrderMapper;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public ExportExecutionService(ExportJobService exportJobService,
                                  ExportJobMapper exportJobMapper,
                                  ExportOrderMapper exportOrderMapper,
                                  ObjectMapper objectMapper,
                                  @Value("${export.execution.batch-size:1000}") int batchSize) {
        this.exportJobService = exportJobService;
        this.exportJobMapper = exportJobMapper;
        this.exportOrderMapper = exportOrderMapper;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    /**
     * 执行任务并把业务异常内部收敛为 Job/Attempt FAILED，不向 Consumer 外抛
     * （失败已成为可查询事实，随后正常 Ack，避免同一条消息无限重放）。
     * <p>
     * markFailed 自身失败属基础设施故障，异常穿出由 Consumer 走不 Ack 路径。
     */
    public void execute(long jobId) {
        try {
            runJob(jobId);
        } catch (Exception ex) {
            String message = ExceptionUtils.messageOrTypeName(ex);
            exportJobService.markFailed(jobId, ERROR_CODE_FILE_GENERATION, message);
            log.warn("export_execution_failed job_id={} error_code={} reason={}",
                    jobId, ERROR_CODE_FILE_GENERATION, message);
        }
    }

    /**
     * 执行体：加载任务快照后按 Keyset 游标分批读取——下界为已读最后 ID，上界为创建时高水位，
     * 条件全部来自任务快照（不依赖浏览器/消息外的任何状态）。
     * <p>
     * 游标推进顺序即失败屏障：查询 → （第 17 章 writeBatch 扩展点）→ 累计 → 推进 lastId → 落进度；
     * 空批或不足一批结束。文件生成与成功终态尚未实现，读取完成后仍收敛 FAILED。
     */
    private void runJob(long jobId) {
        ExportJobEntity job = exportJobMapper.selectById(jobId);
        if (job == null || !STATUS_RUNNING.equals(job.status())) {
            throw new IllegalStateException("任务不存在或未处于 RUNNING，job_id=" + jobId);
        }
        OrderCriteria criteria = readCriteria(jobId, job.filterSnapshot());
        long processed = 0;
        long lastId = 0;
        while (true) {
            List<ExportOrderRow> batch = exportOrderMapper.findBatch(
                    lastId, job.maxOrderIdAtCreate(), batchSize, criteria);
            if (batch.isEmpty()) {
                break;
            }
            // writeBatch 扩展点（第 17 章）：lastId 只能在本批真实写入成功后推进，失败批次不得伪装已处理
            processed += batch.size();
            lastId = batch.getLast().id();
            exportJobMapper.updateProcessedRows(jobId, processed, LocalDateTime.now());
            if (batch.size() < batchSize) {
                break;
            }
        }
        log.info("export_job_batches_read job_id={} processed={} filter_count={} trace_id={}",
                jobId, processed, job.filterCount(), TraceIdSupport.currentTraceId());
        throw new IllegalStateException("Excel 生成尚未实现（第 17 章），job_id=" + jobId
                + "，已读取 " + processed + " 行");
    }

    /** 快照重建：filter_snapshot 直存 OrderCriteria 序列化 JSON，反序列化即得完整筛选条件。 */
    private OrderCriteria readCriteria(long jobId, String filterSnapshot) {
        try {
            return objectMapper.readValue(filterSnapshot, OrderCriteria.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("筛选快照反序列化失败，job_id=" + jobId, ex);
        }
    }
}
