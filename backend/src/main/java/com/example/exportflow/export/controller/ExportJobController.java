package com.example.exportflow.export.controller;

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
 * 导出任务接口（占位实现，后续迭代补充创建/重试/下载/SSE 等）。
 */
@RestController
@RequestMapping("/export-jobs")
@Validated
public class ExportJobController {

    private final ExportJobService exportJobService;

    public ExportJobController(ExportJobService exportJobService) {
        this.exportJobService = exportJobService;
    }

    /** 查询导出任务分页列表（当前返回空列表）。 */
    @GetMapping
    public ExportJobPageResp listJobs(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "page 必须大于等于 1")
            int page,
            @RequestParam(name = "page_size", defaultValue = "10")
            @Min(value = 1, message = "page_size 必须在 1-100 之间")
            @Max(value = 100, message = "page_size 必须在 1-100 之间")
            int pageSize) {
        return exportJobService.listJobs(page, pageSize);
    }
}
