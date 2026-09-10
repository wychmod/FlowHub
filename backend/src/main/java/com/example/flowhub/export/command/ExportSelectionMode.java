package com.example.flowhub.export.command;

import com.example.flowhub.common.web.param.ParamUtils;

/**
 * 导出范围模式，与请求体 selection.mode 契约一致。
 */
public enum ExportSelectionMode {

    /** 勾选导出：按 order_ids 精确取数。 */
    SELECTED_IDS,

    /** 筛选导出：按 filter 快照条件取数。 */
    FILTER;

    /** 大小写不敏感解析，未识别返回 null。 */
    public static ExportSelectionMode fromName(String name) {
        return ParamUtils.enumFromName(name, ExportSelectionMode.class);
    }
}
