package com.example.flowhub.orderimport.service;

import com.example.flowhub.common.web.trace.TraceIdSupport;
import com.example.flowhub.common.web.util.ExceptionUtils;
import com.example.flowhub.orderimport.entity.ImportJobEntity;
import com.example.flowhub.orderimport.entity.ImportOrderRow;
import com.example.flowhub.orderimport.excel.ExcelImportReader;
import com.example.flowhub.orderimport.excel.ImportErrorReportWriter;
import com.example.flowhub.orderimport.mapper.ImportJobAttemptMapper;
import com.example.flowhub.orderimport.mapper.ImportJobMapper;
import com.example.flowhub.orderimport.mapper.ImportOrderMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入执行服务：消费端抢占成功后调用，
 * SAX 流式读 → 行级校验 → 文件内查重 → 冲突预查 → 批量入库 → 进度推进 → 终态收敛。
 * <p>
 * 业务失败终态收敛为可查询事实；有效行照常导入、错误行跳过并入错误报告（PARTIAL 部分成功语义）。
 */
@Service
public class ImportExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ImportExecutionService.class);

    /** 执行失败统一错误码（import_jobs/import_job_attempts 的 error_code）。 */
    public static final String ERROR_CODE_EXECUTION = "IMPORT_EXECUTION_FAILED";
    /** 错误摘要保留 top N 类。 */
    private static final int SUMMARY_TOP_N = 10;

    private static final String STATUS_RUNNING = "RUNNING";

    private final ImportJobService importJobService;
    private final ImportProgressService importProgressService;
    private final ImportFileService importFileService;
    private final ExcelImportReader excelImportReader;
    private final ImportRowValidator importRowValidator;
    private final ImportErrorReportWriter importErrorReportWriter;
    private final ImportJobMapper importJobMapper;
    private final ImportJobAttemptMapper importJobAttemptMapper;
    private final ImportOrderMapper importOrderMapper;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int errorReportMaxRows;

    public ImportExecutionService(ImportJobService importJobService,
                                  ImportProgressService importProgressService,
                                  ImportFileService importFileService,
                                  ExcelImportReader excelImportReader,
                                  ImportRowValidator importRowValidator,
                                  ImportErrorReportWriter importErrorReportWriter,
                                  ImportJobMapper importJobMapper,
                                  ImportJobAttemptMapper importJobAttemptMapper,
                                  ImportOrderMapper importOrderMapper,
                                  ObjectMapper objectMapper,
                                  @Value("${import.execution.batch-size:1000}") int batchSize,
                                  @Value("${import.error-report-max-rows:5000}") int errorReportMaxRows) {
        this.importJobService = importJobService;
        this.importProgressService = importProgressService;
        this.importFileService = importFileService;
        this.excelImportReader = excelImportReader;
        this.importRowValidator = importRowValidator;
        this.importErrorReportWriter = importErrorReportWriter;
        this.importJobMapper = importJobMapper;
        this.importJobAttemptMapper = importJobAttemptMapper;
        this.importOrderMapper = importOrderMapper;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.errorReportMaxRows = errorReportMaxRows;
    }

    /** 执行任务并把业务异常内部收敛为 Job/Attempt FAILED，不向 Consumer 外抛；收敛后按 DB 事实刷新投影。 */
    public void execute(long jobId) {
        try {
            runJob(jobId);
            importProgressService.refreshProjection(jobId);
        } catch (Exception ex) {
            String message = ExceptionUtils.messageOrTypeName(ex);
            importJobService.markFailed(jobId, ERROR_CODE_EXECUTION, message);
            importProgressService.refreshProjection(jobId);
            log.warn("import_execution_failed job_id={} error_code={} reason={}",
                    jobId, ERROR_CODE_EXECUTION, message);
        }
    }

    /** 执行体：SAX 流式读 + 批校验/入库 + 进度推进 + 终态收敛（skipped==0 → SUCCEEDED，否则 PARTIAL）。 */
    private void runJob(long jobId) throws IOException {
        ImportJobEntity job = importJobMapper.selectById(jobId);
        if (job == null || !STATUS_RUNNING.equals(job.status())) {
            throw new IllegalStateException("任务不存在或未处于 RUNNING，job_id=" + jobId);
        }
        Path upload = importFileService.resolvePersisted(job.filePath());
        int attemptNo = attemptNo(jobId);
        long totalRows = job.totalRows() == null ? 0 : job.totalRows();

        final int[] processed = {0};
        final int[] succeeded = {0};
        final int[] skipped = {0};
        final Set<String> fileSeen = new HashSet<>();
        final List<PendRow> batch = new ArrayList<>();
        final List<ImportErrorEntry> errorEntries = new ArrayList<>();
        final Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        final boolean[] truncated = {false};

        excelImportReader.readRows(upload, (excelRowNo, cells) -> {
            processed[0]++;
            ImportRowValidator.Outcome oc = importRowValidator.validate(cells);
            if (!oc.valid()) {
                String orderNo = cells != null && cells.length > 0 ? cells[0] : null;
                recordValidationError(excelRowNo, orderNo, oc.errors(), errorEntries, reasonCounts, truncated);
                skipped[0]++;
                return;
            }
            ImportOrderRow row = oc.row();
            if (!fileSeen.add(row.orderNo())) {
                recordReasonError(excelRowNo, row.orderNo(), "订单号", "订单号在文件内重复",
                        errorEntries, reasonCounts, truncated);
                skipped[0]++;
                return;
            }
            batch.add(new PendRow(row, excelRowNo));
            if (batch.size() >= batchSize) {
                flushBatch(jobId, batch, processed, succeeded, skipped, reasonCounts, errorEntries, truncated, totalRows);
                batch.clear();
            }
        });
        if (!batch.isEmpty()) {
            flushBatch(jobId, batch, processed, succeeded, skipped, reasonCounts, errorEntries, truncated, totalRows);
            batch.clear();
        }

        log.info("import_job_parsed job_id={} processed={} succeeded={} skipped={} trace_id={}",
                jobId, processed[0], succeeded[0], skipped[0], TraceIdSupport.currentTraceId());
        converge(job, attemptNo, skipped[0], errorEntries, reasonCounts, truncated[0]);
    }

    /**
     * 攒满一批就推进：冲突预查（IN 命中即跳过）→ DuplicateKey 兜底 → 批量入库 → 报进度。
     * succeeded/skipped 为累计值数组（跨批累加），processed 已由逐行计数提前累计。
     */
    private void flushBatch(long jobId, List<PendRow> batch, int[] processed, int[] succeeded, int[] skipped,
                            Map<String, Integer> reasonCounts, List<ImportErrorEntry> errorEntries,
                            boolean[] truncated, long totalRows) {
        List<String> orderNos = batch.stream().map(p -> p.row().orderNo()).toList();
        Set<String> existing = orderNos.isEmpty() ? Set.of() : importOrderMapper.selectExistingOrderNos(orderNos);

        List<PendRow> toInsert = new ArrayList<>();
        for (PendRow p : batch) {
            if (existing.contains(p.row().orderNo())) {
                // 与库内已有单据重复：干净归入跳过集（决策 1），不整体失败
                recordReasonError(p.excelRowNo(), p.row().orderNo(), "订单号", "订单号与库内已有单据重复",
                        errorEntries, reasonCounts, truncated);
                skipped[0]++;
            } else {
                toInsert.add(p);
            }
        }
        int inserted = insertWithDuplicateFallback(toInsert, skipped, errorEntries, reasonCounts, truncated);
        succeeded[0] += inserted;
        importProgressService.report(jobId, processed[0], succeeded[0], skipped[0], totalRows);
    }

    /** 批量入库 + 唯一约束兜底：预查后仍撞键（并发写入）时逐行降级，命中行按原 Excel 行号转跳过。 */
    private int insertWithDuplicateFallback(List<PendRow> toInsert,
                                            int[] skipped, List<ImportErrorEntry> errorEntries,
                                            Map<String, Integer> reasonCounts, boolean[] truncated) {
        if (toInsert.isEmpty()) {
            return 0;
        }
        try {
            // 主路径：整批入库；DuplicateKeyException 说明有并发窗口写入的重复，走降级
            return importOrderMapper.insertBatch(toInsert.stream().map(PendRow::row).toList());
        } catch (DuplicateKeyException ex) {
            int inserted = 0;
            for (PendRow p : toInsert) {
                try {
                    importOrderMapper.insertBatch(List.of(p.row()));
                    inserted++;
                } catch (DuplicateKeyException conflict) {
                    // 撞唯一约束：该单号已被并发写入，行号取自 PendRow 保证与 Excel 行对齐
                    recordReasonError(p.excelRowNo(), p.row().orderNo(), "订单号", "订单号与库内已有单据重复",
                            errorEntries, reasonCounts, truncated);
                    skipped[0]++;
                }
            }
            return inserted;
        }
    }

    /** 校验失败记录：同一行多列错误聚合成一条错误报告（列名/原因用「；」连接），并累加错误分类计数。 */
    private void recordValidationError(int excelRowNo, String orderNo, List<ImportRowValidator.FieldError> errors,
                                       List<ImportErrorEntry> errorEntries, Map<String, Integer> reasonCounts,
                                       boolean[] truncated) {
        String columns = errors.stream().map(e -> e.column().title()).distinct()
                .reduce((a, b) -> a + "；" + b).orElse("");
        String reasons = errors.stream().map(ImportRowValidator.FieldError::reason).distinct()
                .reduce((a, b) -> a + "；" + b).orElse("");
        errors.forEach(e -> reasonCounts.merge(e.reason(), 1, Integer::sum));
        appendEntry(errorEntries, new ImportErrorEntry(excelRowNo, orderNo, columns, reasons), truncated);
    }

    /** 单原因错误（文件内/库内冲突）记录：分类计数 + 入缓冲。 */
    private void recordReasonError(int excelRowNo, String orderNo, String column, String reason,
                                   List<ImportErrorEntry> errorEntries, Map<String, Integer> reasonCounts,
                                   boolean[] truncated) {
        reasonCounts.merge(reason, 1, Integer::sum);
        appendEntry(errorEntries, new ImportErrorEntry(excelRowNo, orderNo, column, reason), truncated);
    }

    /** 有界缓冲：仅保留前 error-report-max-rows 条（超限仅截断展示，分类计数与 skipped 仍如实），置 truncated。 */
    private void appendEntry(List<ImportErrorEntry> errorEntries, ImportErrorEntry entry, boolean[] truncated) {
        if (errorEntries.size() < errorReportMaxRows) {
            errorEntries.add(entry);
        } else {
            truncated[0] = true;
        }
    }

    /** 终态收敛：全成功 markSucceeded；有跳过则先生成错误报告再 markPartial（DB 失败补偿删除报告文件）。 */
    private void converge(ImportJobEntity job, int attemptNo, int skipped,
                          List<ImportErrorEntry> errorEntries, Map<String, Integer> reasonCounts,
                          boolean truncated) throws IOException {
        if (skipped == 0) {
            importJobService.markSucceeded(job.id());
            return;
        }
        String relative = importFileService.errorReportPath(job.jobNo(), attemptNo);
        Path report = importFileService.resolveErrorReportAbsolute(relative);
        importErrorReportWriter.write(report, List.copyOf(errorEntries), skipped, truncated);
        String summary = buildSummary(reasonCounts);
        try {
            importJobService.markPartial(job.id(), relative, summary);
        } catch (RuntimeException ex) {
            // markPartial 事务失败：已生成的错误报告不留孤儿，删除后原样上抛走失败收敛
            importFileService.deleteQuietly(report);
            throw ex;
        }
    }

    /** 错误分类 Top N JSON（[{reason,count}]，按次数降序截前 N）。 */
    private String buildSummary(Map<String, Integer> reasonCounts) {
        List<SummaryEntry> top = reasonCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(SUMMARY_TOP_N)
                .map(e -> new SummaryEntry(e.getKey(), e.getValue()))
                .toList();
        try {
            return objectMapper.writeValueAsString(top);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("错误摘要序列化失败", ex);
        }
    }

    private int attemptNo(long jobId) {
        Integer attemptNo = importJobAttemptMapper.selectRunningAttemptNo(jobId);
        return attemptNo == null ? 0 : attemptNo;
    }

    /** 错误分类摘要项（[{reason, count}]）。 */
    private record SummaryEntry(@JsonProperty("reason") String reason, @JsonProperty("count") int count) {
    }

    /** 待入库行 + 其 Excel 行号（冲突预查转跳过时，错误报告行号与文件对齐）。 */
    private record PendRow(ImportOrderRow row, int excelRowNo) {
    }
}