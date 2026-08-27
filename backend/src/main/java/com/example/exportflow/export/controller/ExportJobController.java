package com.example.exportflow.export.controller;

import com.example.exportflow.common.web.api.ApiResponse;
import com.example.exportflow.export.dto.ExportJobPageResp;
import com.example.exportflow.export.service.ExportJobService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导出任务接口，见 be-td.md 4.6。
 *
 * <p>创建、详情、重试、下载、SSE 等接口将在后续迭代按 be-td.md 4.5-4.10 补充。
 *
 * <p>{@code /api/v1} 前缀由 {@code ApiWebMvcConfiguration} 按 {@code @RestController} 统一追加，
 * 此处仅声明相对路径 {@code /export-jobs}。
 */
@RestController
@RequestMapping("/export-jobs")
@Validated
public class ExportJobController {

    private final ExportJobService exportJobService;

    public ExportJobController(ExportJobService exportJobService) {
        this.exportJobService = exportJobService;
    }

    @GetMapping
    public ApiResponse<ExportJobPageResp> listJobs(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 必须大于等于 1") int page,
            @RequestParam(name = "page_size", defaultValue = "10")
            @Min(value = 1, message = "page_size 必须在 1-100 之间")
            @Max(value = 100, message = "page_size 必须在 1-100 之间") int pageSize) {
        return ApiResponse.success(exportJobService.listJobs(page, pageSize));
    }
}
