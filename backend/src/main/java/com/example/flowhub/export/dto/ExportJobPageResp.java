package com.example.flowhub.export.dto;

import com.example.flowhub.export.vo.ExportJobItemVO;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 导出任务分页响应。 */
public record ExportJobPageResp(
        List<ExportJobItemVO> items,
        long total,
        int page,

        @JsonProperty("page_size")
        int pageSize) {
}
