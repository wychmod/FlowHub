package com.example.exportflow.export.controller;

import com.example.exportflow.common.web.error.BusinessException;
import com.example.exportflow.common.web.param.ParamUtils;
import com.example.exportflow.export.dto.CreateExportJobRequest;
import com.example.exportflow.export.dto.ExportJobPageResp;
import com.example.exportflow.export.dto.ListExportJobsRequest;
import com.example.exportflow.export.service.DownloadableExportFile;
import com.example.exportflow.export.service.ExportJobService;
import com.example.exportflow.export.service.ExportSseService;
import com.example.exportflow.export.vo.ExportJobAcceptedVO;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 导出任务接口（创建入口、列表占位、SSE 事件订阅与文件下载；重试等后续迭代补充）。
 */
@RestController
@RequestMapping("/export-jobs")
@Validated
public class ExportJobController {

    /** 幂等键列长度上限（export_jobs.idempotency_key VARCHAR(128)）。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** XLSX 响应类型（下载接口固定输出 Excel 工作簿）。 */
    private static final MediaType XLSX_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    /** Content-Disposition filename= 兜底的安全 ASCII 文件名（可见字符且不含引号/反斜杠等转义位）。 */
    private static final Pattern ASCII_DISPLAY_NAME = Pattern.compile("\\A[\\w. ()\\[\\]-]+\\z");

    private final ExportJobService exportJobService;
    private final ExportSseService exportSseService;

    public ExportJobController(ExportJobService exportJobService, ExportSseService exportSseService) {
        this.exportJobService = exportJobService;
        this.exportSseService = exportSseService;
    }

    /** 创建导出任务（异步导出的同步命令接收入口），成功返回 202 + 任务受理信息。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExportJobAcceptedVO createJob(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateExportJobRequest request) {
        return exportJobService.createJob(request.toCommand(), requireIdempotencyKey(idempotencyKey));
    }

    /** 幂等键入参校验：缺失或超长均归入统一 400（结构错误不属于业务判断）。 */
    private static String requireIdempotencyKey(String rawKey) {
        String key = ParamUtils.trimToNull(rawKey);
        if (key == null) {
            throw BusinessException.validation("Idempotency-Key 请求头不能为空");
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw BusinessException.validation("Idempotency-Key 请求头过长");
        }
        return key;
    }

    /** 查询导出任务分页列表（当前返回空列表）。 */
    @GetMapping
    public ExportJobPageResp listJobs(@Valid @ModelAttribute ListExportJobsRequest request) {
        return exportJobService.listJobs(request.page(), request.pageSize());
    }

    /** SSE 事件订阅（进度/终态广播 + 心跳，契约见 be-td.md 4.10）：断线重连与轮询降级由前端负责。 */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return exportSseService.connect();
    }

    /** 下载已发布的导出文件（流式二进制；错误走统一 Envelope，前端 parseBlobError 兼容）。 */
    @GetMapping("/{job_id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable("job_id") long jobId) {
        DownloadableExportFile file = exportJobService.getDownloadableFile(jobId);
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.displayName()))
                .body(new FileSystemResource(file.absolutePath()));
    }

    /** Content-Disposition 组装：ASCII 兜底 filename + RFC 5987 filename*（非 ASCII 按百分号编码）。 */
    private static String contentDisposition(String displayName) {
        String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
        String asciiFallback = ASCII_DISPLAY_NAME.matcher(displayName).matches() ? displayName : "export.xlsx";
        return "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded;
    }
}
