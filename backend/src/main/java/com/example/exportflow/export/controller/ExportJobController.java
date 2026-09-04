package com.example.exportflow.export.controller;

import com.example.exportflow.common.web.error.BusinessException;
import com.example.exportflow.common.web.param.ParamUtils;
import com.example.exportflow.export.dto.CreateExportJobRequest;
import com.example.exportflow.export.dto.ExportJobPageResp;
import com.example.exportflow.export.dto.ListExportJobsRequest;
import com.example.exportflow.export.service.ExportJobService;
import com.example.exportflow.export.vo.ExportJobAcceptedVO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导出任务接口（当前含创建入口与列表占位；重试/下载/SSE 等后续迭代补充）。
 */
@RestController
@RequestMapping("/export-jobs")
@Validated
public class ExportJobController {

    /** 幂等键列长度上限（export_jobs.idempotency_key VARCHAR(128)）。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final ExportJobService exportJobService;

    public ExportJobController(ExportJobService exportJobService) {
        this.exportJobService = exportJobService;
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
}
