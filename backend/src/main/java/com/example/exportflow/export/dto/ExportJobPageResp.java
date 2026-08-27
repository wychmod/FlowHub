package com.example.exportflow.export.dto;

import com.example.exportflow.export.vo.ExportJobItemVO;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 导出任务分页响应，见 be-td.md 4.6。 */
public record ExportJobPageResp(
        List<ExportJobItemVO> items,
        long total,
        int page,
        @JsonProperty("page_size") int pageSize) {
}
