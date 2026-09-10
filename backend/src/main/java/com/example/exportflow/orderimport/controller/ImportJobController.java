package com.example.exportflow.orderimport.controller;

import com.example.exportflow.common.web.util.ContentDispositionUtils;
import com.example.exportflow.orderimport.dto.ImportJobPageResp;
import com.example.exportflow.orderimport.dto.ListImportJobsRequest;
import com.example.exportflow.orderimport.excel.ImportTemplateWriter;
import com.example.exportflow.orderimport.service.ImportJobService;
import com.example.exportflow.orderimport.service.ImportSseService;
import com.example.exportflow.orderimport.vo.ImportJobAcceptedVO;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 订单导入接口（docs/order-import-design.md §4 的 6 个端点：模板下载/上传受理/列表/错误报告下载/人工重试/SSE）。
 */
@RestController
@RequestMapping("/import-jobs")
@Validated
public class ImportJobController {

    /** Excel 响应类型（模板下载与错误报告下载固定输出工作簿）。 */
    private static final MediaType XLSX_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final ImportJobService importJobService;
    private final ImportSseService importSseService;
    private final ImportTemplateWriter importTemplateWriter;

    public ImportJobController(ImportJobService importJobService,
                               ImportSseService importSseService,
                               ImportTemplateWriter importTemplateWriter) {
        this.importJobService = importJobService;
        this.importSseService = importSseService;
        this.importTemplateWriter = importTemplateWriter;
    }

    /** 模板下载：返回 9 列导入模板工作簿（表头 + 示例行 + 下拉 + 填写说明，划分锚定导出格式）。 */
    @GetMapping(value = "/template",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> template() throws IOException {
        byte[] bytes = importTemplateWriter.render();
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtils.attachment("import-template.xlsx", "import.xlsx"))
                .body(bytes);
    }

    /** 上传受理（multipart）：三层校验（文件级/结构级同步，行级异步），成功返回 202。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobAcceptedVO upload(@RequestParam("file") MultipartFile file) throws IOException {
        return importJobService.createJob(file);
    }

    /** 分页列表（可选 status 过滤，粒度到 ImportJobItemVO 派生字段）。 */
    @GetMapping
    public ImportJobPageResp listJobs(@Valid @ModelAttribute ListImportJobsRequest request) {
        return importJobService.listJobs(request.page(), request.pageSize(), request.status());
    }

    /** SSE 事件订阅：进度/终态广播 + 心跳（断线重连与轮询降级由前端负责）。 */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return importSseService.connect();
    }

    /** 错误报告下载：仅 PARTIAL 且登记了报告路径放行，流式返回错误工作簿。 */
    @GetMapping("/{job_id}/error-report")
    public ResponseEntity<FileSystemResource> errorReport(@PathVariable("job_id") long jobId) {
        ImportJobService.DownloadableImportReport report = importJobService.getDownloadableErrorReport(jobId);
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtils.attachment(report.displayName(), "import.xlsx"))
                .body(new FileSystemResource(report.absolutePath()));
    }

    /** 人工重试失败任务（FAILED → PENDING + 新 Outbox，受理后仍走条件抢占）。 */
    @PostMapping("/{job_id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobAcceptedVO retry(@PathVariable("job_id") long jobId) {
        return importJobService.retry(jobId);
    }

    }