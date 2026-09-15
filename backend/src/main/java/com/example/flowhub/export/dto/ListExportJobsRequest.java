package com.example.flowhub.export.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.BindParam;

/**
 * 导出任务列表查询请求（record + @BindParam 构造器绑定）。
 * <p>
 * status 过滤值取值白名单与大小写归一由 Service 层校验。
 */
public record ListExportJobsRequest(
        @Min(value = 1, message = "page 必须大于等于 1")
        Integer page,

        @BindParam("page_size")
        @Min(value = 1, message = "page_size 必须在 1-100 之间")
        @Max(value = 100, message = "page_size 必须在 1-100 之间")
        Integer pageSize,

        String status) {

    /** 紧凑构造器：分页参数默认值兜底（page=1, pageSize=10）。 */
    public ListExportJobsRequest {
        if (page == null) {
            page = 1;
        }
        if (pageSize == null) {
            pageSize = 10;
        }
    }
}
