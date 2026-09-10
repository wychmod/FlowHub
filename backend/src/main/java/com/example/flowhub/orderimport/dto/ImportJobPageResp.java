package com.example.flowhub.orderimport.dto;

import com.example.flowhub.orderimport.vo.ImportJobItemVO;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 导入任务分页响应（docs/order-import-design.md §5.3）。 */
public record ImportJobPageResp(
        List<ImportJobItemVO> items,
        long total,
        int page,

        @JsonProperty("page_size")
        int pageSize) {
}