package com.example.exportflow.export.service;

import com.example.exportflow.common.web.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 导出执行服务：消费端抢占成功后调用，负责实际生成文件并把业务失败收敛为可查询事实。
 * <p>
 * 真正的批量读取与 SXSSF 流式 Excel 生成按第 15 章展开；当前执行体为占位实现，
 * 借其失败路径打通 RUNNING → FAILED 收敛链路。
 */
@Service
public class ExportExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExportExecutionService.class);

    /** 执行失败统一错误码（export_jobs/export_job_attempts 的 error_code 分类）。 */
    public static final String ERROR_CODE_FILE_GENERATION = "FILE_GENERATION_FAILED";

    private final ExportJobService exportJobService;

    public ExportExecutionService(ExportJobService exportJobService) {
        this.exportJobService = exportJobService;
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

    /** 执行体：第 15 章实现游标读取与流式 Excel 生成；占位阶段直接失败以收敛链路。 */
    private void runJob(long jobId) {
        throw new IllegalStateException("导出执行器尚未实现（第 15 章落地），job_id=" + jobId);
    }
}
