package com.example.flowhub.orderimport.service;

import com.example.flowhub.common.web.error.BusinessException;
import com.example.flowhub.common.web.param.ParamUtils;
import com.example.flowhub.common.web.trace.TraceIdSupport;
import com.example.flowhub.export.entity.OutboxEventEntity;
import com.example.flowhub.export.excel.ExcelExportWriter;
import com.example.flowhub.export.mapper.OutboxEventMapper;
import com.example.flowhub.orderimport.command.ImportColumn;
import com.example.flowhub.orderimport.dto.ImportJobPageResp;
import com.example.flowhub.orderimport.entity.ImportJobEntity;
import com.example.flowhub.orderimport.error.ImportErrorCode;
import com.example.flowhub.orderimport.event.ImportJobChanged;
import com.example.flowhub.orderimport.event.ImportJobEventPayload;
import com.example.flowhub.orderimport.excel.ExcelImportReader;
import com.example.flowhub.orderimport.mapper.ImportJobAttemptMapper;
import com.example.flowhub.orderimport.mapper.ImportJobMapper;
import com.example.flowhub.orderimport.vo.ImportJobAcceptedVO;
import com.example.flowhub.orderimport.vo.ImportJobItemVO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 导入任务服务：创建受理（文件/结构校验 + 同事务写 import_jobs/outbox_events）、列表查询、错误报告解析，
 * 执行侧状态管理（条件抢占、SUCCEEDED/PARTIAL/FAILED 收敛）与人工重试。
 * <p>
 * 不发布 RabbitMQ、不写 Redis、不读写文件细节——文件落盘/解析/删除经 ImportFileService 受控边界。
 */
@Service
public class ImportJobService {

    private static final Logger log = LoggerFactory.getLogger(ImportJobService.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final DateTimeFormatter JOB_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 列表 status 过滤白名单（PARTIAL 部分成功态纳入）。 */
    private static final Set<String> LIST_FILTER_STATUSES =
            Set.of("PENDING", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "EXPIRED");
    /** 尝试上限：达到后不可再抢占，等待人工重试。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 抢占时预置的租约时长（分钟）。 */
    private static final int LEASE_MINUTES = 5;
    /** error_message 列宽（VARCHAR(500)），超长截断防 SQL 失败。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;
    /** xlsx 的 ZIP 魔数前两字节。 */
    private static final byte[] XLSX_MAGIC = {'P', 'K'};

    private final ImportJobMapper importJobMapper;
    private final ImportJobAttemptMapper importJobAttemptMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final ImportFileService importFileService;
    private final ExcelImportReader excelImportReader;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final long maxRows;
    private final long maxFileSizeBytes;
    private final long retentionHours;

    public ImportJobService(ImportJobMapper importJobMapper,
                            ImportJobAttemptMapper importJobAttemptMapper,
                            OutboxEventMapper outboxEventMapper,
                            ImportFileService importFileService,
                            ExcelImportReader excelImportReader,
                            ObjectMapper objectMapper,
                            ApplicationEventPublisher events,
                            @Value("${import.max-rows:100000}") long maxRows,
                            @Value("${import.file.max-size:10485760}") long maxFileSizeBytes,
                            @Value("${import.files.retention-hours:24}") long retentionHours) {
        this.importJobMapper = importJobMapper;
        this.importJobAttemptMapper = importJobAttemptMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.importFileService = importFileService;
        this.excelImportReader = excelImportReader;
        this.objectMapper = objectMapper;
        this.events = events;
        this.maxRows = maxRows;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.retentionHours = retentionHours;
    }

    /** 列表分页：可选 status 过滤，派生 progress_percent/error_report_available/error_summary，按创建时间倒序。 */
    public ImportJobPageResp listJobs(int page, int pageSize, String status) {
        String statusFilter = normalizeStatusFilter(status);
        List<ImportJobItemVO> items = importJobMapper.findPage(pageSize, (page - 1) * pageSize, statusFilter).stream()
                .map(this::toListItem)
                .toList();
        return new ImportJobPageResp(items, importJobMapper.countAll(statusFilter), page, pageSize);
    }

    /** 列表 status 过滤归一：空白视为未传；大小写不敏感白名单校验，非法抛 400。 */
    private static String normalizeStatusFilter(String raw) {
        String status = ParamUtils.trimToNull(raw);
        if (status == null) {
            return null;
        }
        String upper = status.toUpperCase(Locale.ROOT);
        if (!LIST_FILTER_STATUSES.contains(upper)) {
            throw BusinessException.validation("status 仅支持 PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/EXPIRED");
        }
        return upper;
    }

    /** 实体 → 列表行 VO：派生 percent / error_report_available / error_summary（解析 JSON，缺省 null）。 */
    private ImportJobItemVO toListItem(ImportJobEntity job) {
        long processed = job.processedRows() == null ? 0 : job.processedRows();
        long total = job.totalRows() == null ? 0 : job.totalRows();
        return new ImportJobItemVO(
                job.id(),
                job.jobNo(),
                job.status(),
                job.version(),
                job.totalRows(),
                job.processedRows(),
                job.succeededRows(),
                job.skippedRows(),
                ImportJobEventPayload.progressPercent(job.status(), processed, total),
                ImportJobEventPayload.errorReportAvailable(job),
                readErrorSummary(job.errorSummary()),
                job.fileName(),
                null,
                job.errorCode(),
                job.errorMessage(),
                job.createdAt(),
                job.finishedAt(),
                job.expiredAt());
    }

    /** error_summary JSON → 错误分类列表；空白/解析失败返回 null（列表展示可空，不影响任务语义）。 */
    private List<ImportJobItemVO.ErrorSummaryItem> readErrorSummary(String errorSummary) {
        if (errorSummary == null || errorSummary.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(errorSummary,
                    new TypeReference<List<ImportJobItemVO.ErrorSummaryItem>>() {
                    });
        } catch (JsonProcessingException ex) {
            log.warn("import_error_summary_parse_failed job_id={} reason={}", "?", ex.toString());
            return null;
        }
    }

    /**
     * 创建导入任务（受理链路）：文件级校验 → 受控落盘 → 结构级轻扫校验 → 同事务写任务与 Outbox → 202。
     * 任一校验失败不落盘不建任务；DB 写入失败补偿删除已落盘文件（不留孤儿）。
     */
    @Transactional
    public ImportJobAcceptedVO createJob(MultipartFile file) throws IOException {
        validateFileLevel(file);
        LocalDateTime now = LocalDateTime.now();
        String jobNo = generateJobNo(now);
        String filePath = importFileService.persistUpload(jobNo, file);
        try {
            ExcelImportReader.ScanResult scan = excelImportReader.scanStructure(importFileService.resolvePersisted(filePath));
            validateStructure(scan);
            ImportJobEntity job = new ImportJobEntity(
                    null, jobNo, STATUS_PENDING, fileNameOf(file), filePath,
                    scan.dataRowCount(), null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, now);
            importJobMapper.insert(job);
            // record 无 setter，自增 id 以唯一 job_no 取回，供 Outbox aggregate_id 与响应
            ImportJobEntity persisted = importJobMapper.selectByJobNo(jobNo);
            outboxEventMapper.insert(outboxEvent(persisted));
            return new ImportJobAcceptedVO(
                    persisted.id(), persisted.jobNo(), STATUS_PENDING,
                    persisted.totalRows(), persisted.fileName(), persisted.createdAt());
        } catch (IOException ex) {
            // SAX 扫描失败（PK 魔数通过但不是合法 OOXML）：转文件级 400，避免落 500 兜底
            importFileService.deleteQuietly(importFileService.resolvePersisted(filePath));
            throw new BusinessException(ImportErrorCode.IMPORT_FILE_CORRUPTED);
        } catch (RuntimeException ex) {
            // 受理事务回滚（结构校验失败/DB 失败）：删除已落盘原件，避免孤儿文件
            importFileService.deleteQuietly(importFileService.resolvePersisted(filePath));
            throw ex;
        }
    }

    /** 文件级校验（同步受理，任一失败 400）：大小 ≤ max-file-size、后缀 .xlsx、ZIP 魔数 PK。 */
    private void validateFileLevel(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ImportErrorCode.IMPORT_FILE_CORRUPTED, "未收到上传文件或文件为空");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new BusinessException(ImportErrorCode.IMPORT_FILE_TOO_LARGE);
        }
        String original = fileNameOf(file);
        if (original == null || !original.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException(ImportErrorCode.IMPORT_FORMAT_NOT_SUPPORTED);
        }
        if (!isZipMagic(file)) {
            throw new BusinessException(ImportErrorCode.IMPORT_FILE_CORRUPTED);
        }
    }

    /** 前 2 字节须为 PK（ZIP 魔数）：低成本识别非 xlsx 文件，避免 POI 解析报木讷异常。 */
    private boolean isZipMagic(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(2);
            return head.length == 2 && head[0] == XLSX_MAGIC[0] && head[1] == XLSX_MAGIC[1];
        }
    }

    /** 结构级校验：数据 Sheet 名、表头恰好 9 列（多列/缺列/名称/顺序不符均拒绝）、空文件、行数超上限。 */
    private void validateStructure(ExcelImportReader.ScanResult scan) {
        if (!ExcelExportWriter.SHEET_NAME.equals(scan.sheetName())) {
            log.warn("import_sheet_mismatch expected={} actual={}", ExcelExportWriter.SHEET_NAME, scan.sheetName());
            throw new BusinessException(ImportErrorCode.IMPORT_TEMPLATE_MISMATCH,
                    "模板不匹配：数据 Sheet 必须为「" + ExcelExportWriter.SHEET_NAME + "」");
        }
        String headerReason = headerMismatchReason(scan);
        if (headerReason != null) {
            throw new BusinessException(ImportErrorCode.IMPORT_TEMPLATE_MISMATCH, "模板不匹配：" + headerReason);
        }
        if (scan.dataRowCount() == 0) {
            throw new BusinessException(ImportErrorCode.IMPORT_EMPTY_FILE);
        }
        if (scan.dataRowCount() > maxRows) {
            throw new BusinessException(ImportErrorCode.IMPORT_TOO_MANY_ROWS,
                    "数据行数 " + scan.dataRowCount() + " 超过上限 " + maxRows);
        }
    }

    /** 表头严格校验：列数恰为 9 且逐列名称/顺序一致；不匹配返回含错位列号的可读原因，匹配返回 null。 */
    @Nullable
    private String headerMismatchReason(ExcelImportReader.ScanResult scan) {
        List<ImportColumn> columns = ImportColumn.all();
        if (scan.headerColumnCount() != columns.size()) {
            log.warn("import_header_mismatch reason=column_count expected={} actual={}",
                    columns.size(), scan.headerColumnCount());
            return "表头列数应为 " + columns.size() + " 列，实际 " + scan.headerColumnCount() + " 列";
        }
        for (int i = 0; i < columns.size(); i++) {
            String expected = columns.get(i).title();
            String actual = scan.headerTitles().get(i);
            if (!expected.equals(actual)) {
                log.warn("import_header_mismatch column_index={} expected={} actual={}", i, expected, actual);
                return "第 " + (i + 1) + " 列应为「" + expected + "」，实际为「" + (actual == null ? "缺列" : actual) + "」";
            }
        }
        return null;
    }

    /** 条件抢占：CAS 将 PENDING 置 RUNNING，并同事务插入 RUNNING Attempt（两步原子，不可拆分）。 */
    @Transactional
    public boolean claimPendingJob(long jobId) {
        LocalDateTime now = LocalDateTime.now();
        if (importJobMapper.claimPending(jobId, MAX_ATTEMPTS, now, now.plusMinutes(LEASE_MINUTES)) != 1) {
            log.debug("import_job_claim_missed job_id={} trace_id={}", jobId, TraceIdSupport.currentTraceId());
            return false;
        }
        importJobAttemptMapper.insertRunning(jobId, now);
        log.info("import_job_claimed job_id={} lease_expires_at={} trace_id={}",
                jobId, now.plusMinutes(LEASE_MINUTES), TraceIdSupport.currentTraceId());
        return true;
    }

    /** 收敛失败：Job 与当前 RUNNING Attempt 同事务置 FAILED，回填错误与结束时间并广播终态。 */
    @Transactional
    public void markFailed(long jobId, String errorCode, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        String message = truncateMessage(errorMessage);
        int updated = importJobMapper.markFailed(jobId, errorCode, message, now);
        importJobAttemptMapper.markFailed(jobId, errorCode, message, now);
        if (updated == 1) {
            events.publishEvent(new ImportJobChanged(jobId));
        }
        log.warn("import_job_failed job_id={} error_code={} error_message={} trace_id={}",
                jobId, errorCode, message, TraceIdSupport.currentTraceId());
    }

    /** 全成功收敛：RUNNING → SUCCEEDED（成功计数已随进度落库，保留期从完成起算），同事务收敛 Attempt 并广播。 */
    @Transactional
    public void markSucceeded(long jobId) {
        LocalDateTime now = LocalDateTime.now();
        if (importJobMapper.markSucceeded(jobId, now, now.plusHours(retentionHours)) != 1) {
            throw new IllegalStateException("成功终态推进失败（任务非 RUNNING），job_id=" + jobId);
        }
        importJobAttemptMapper.markSucceeded(jobId, now);
        events.publishEvent(new ImportJobChanged(jobId));
        log.info("import_job_succeeded job_id={} trace_id={}", jobId, TraceIdSupport.currentTraceId());
    }

    /**
     * 部分成功收敛：RUNNING → PARTIAL，登记错误报告路径、错误摘要与保留期截止（同事务收敛 Attempt 并广播）。
     * Job 0 行抛异常回滚，调用方须补偿删除已生成的错误报告文件。
     */
    @Transactional
    public void markPartial(long jobId, String errorReportPath, String errorSummary) {
        LocalDateTime now = LocalDateTime.now();
        if (importJobMapper.markPartial(jobId, errorReportPath, errorSummary, now,
                now.plusHours(retentionHours)) != 1) {
            throw new IllegalStateException("部分成功终态推进失败（任务非 RUNNING），job_id=" + jobId);
        }
        importJobAttemptMapper.markPartial(jobId, "skip_rows", errorSummary, now);
        events.publishEvent(new ImportJobChanged(jobId));
        log.info("import_job_partial job_id={} error_report={} skipped={} trace_id={}",
                jobId, errorReportPath, errorSummary, TraceIdSupport.currentTraceId());
    }

    /** 错误报告下载解析：仅 PARTIAL 且登记了错误报告路径放行，受控解析并校验存在。 */
    public DownloadableImportReport getDownloadableErrorReport(long jobId) {
        ImportJobEntity job = importJobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ImportErrorCode.IMPORT_JOB_NOT_FOUND);
        }
        if (!ImportJobEventPayload.errorReportAvailable(job)) {
            throw new BusinessException(ImportErrorCode.IMPORT_ERROR_REPORT_NOT_AVAILABLE);
        }
        Path file;
        try {
            file = importFileService.resolvePersisted(job.errorReportPath());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ImportErrorCode.IMPORT_PATH_INVALID);
        }
        if (!Files.exists(file)) {
            throw new BusinessException(ImportErrorCode.IMPORT_FILE_MISSING);
        }
        return new DownloadableImportReport(file, "import-errors-" + job.jobNo() + ".xlsx");
    }

    /** 人工重试：FAILED → PENDING 条件重置，并同事务写入新 Outbox 事件重走管道（失败 Attempt 历史保留）。 */
    @Transactional
    public ImportJobAcceptedVO retry(long jobId) {
        ImportJobEntity job = importJobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ImportErrorCode.IMPORT_JOB_NOT_FOUND);
        }
        if (job.attemptCount() == null || job.attemptCount() >= MAX_ATTEMPTS) {
            throw new BusinessException(ImportErrorCode.IMPORT_JOB_NOT_RETRYABLE);
        }
        if (importJobMapper.retry(jobId, MAX_ATTEMPTS, LocalDateTime.now()) != 1) {
            throw new BusinessException(ImportErrorCode.IMPORT_JOB_NOT_RETRYABLE);
        }
        outboxEventMapper.insert(retryOutboxEvent(jobId));
        log.info("import_job_retried job_id={} attempt_count={} trace_id={}",
                jobId, job.attemptCount(), TraceIdSupport.currentTraceId());
        return new ImportJobAcceptedVO(jobId, job.jobNo(), STATUS_PENDING, null, job.fileName(), LocalDateTime.now());
    }

    /** 重试 Outbox 事件：复用创建契约（消息只携带执行定位，消费端按库内 Job 抢占执行）。 */
    private OutboxEventEntity retryOutboxEvent(long jobId) {
        return new OutboxEventEntity(null, OutboxEventEntity.AGGREGATE_TYPE_IMPORT_JOB, jobId,
                OutboxEventEntity.EVENT_TYPE_IMPORT_JOB_CREATED,
                writeJson(new RetriedPayload(jobId, true)), TraceIdSupport.currentTraceId(), LocalDateTime.now());
    }

    /** 创建 Outbox 事件（payload 含 job_id/job_no/file_name/trace_id，契约 §7.1）。 */
    private OutboxEventEntity outboxEvent(ImportJobEntity job) {
        CreatedPayload payload = new CreatedPayload(
                job.id(), job.jobNo(), job.fileName(), TraceIdSupport.currentTraceId());
        return new OutboxEventEntity(null, OutboxEventEntity.AGGREGATE_TYPE_IMPORT_JOB,
                job.id(), OutboxEventEntity.EVENT_TYPE_IMPORT_JOB_CREATED,
                writeJson(payload), TraceIdSupport.currentTraceId(), job.createdAt());
    }

    /** 创建事件载荷（JSON 契约固定）。 */
    private record CreatedPayload(
            @JsonProperty("job_id") Long jobId,
            @JsonProperty("job_no") String jobNo,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("trace_id") String traceId) {
    }

    /** 重试事件载荷（JSON 契约固定）。 */
    private record RetriedPayload(
            @JsonProperty("job_id") long jobId,
            @JsonProperty("retry") boolean retry) {
    }

    /** 生成业务任务号：IMP + 日期（yyyyMMdd）+ 8 位大写随机串（同一秒内唯一性靠随机串）。 */
    private static String generateJobNo(LocalDateTime now) {
        String random = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8).toUpperCase(Locale.ROOT);
        return "IMP" + JOB_NO_DATE.format(now) + "-" + random;
    }

    /** 原始上传文件名（仅入库展示用，不把原始名拼进磁盘路径避免路径注入）。 */
    private static String fileNameOf(MultipartFile file) {
        return file.getOriginalFilename();
    }

    /** 截断失败原因至列宽 500（与 error_message VARCHAR(500) 对齐），null 原样返回。 */
    private static String truncateMessage(String message) {
        if (message == null || message.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    /** 序列化为 JSON；受检 JsonProcessingException 统一包装为运行时异常上抛。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON 序列化失败：" + value, ex);
        }
    }

    /** 错误报告下载解析结果：受控绝对路径 + 展示文件名。 */
    public record DownloadableImportReport(Path absolutePath, String displayName) {
    }
}