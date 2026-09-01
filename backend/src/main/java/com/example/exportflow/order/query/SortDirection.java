package com.example.exportflow.order.query;

import com.example.exportflow.common.web.param.ParamUtils;

/**
 * 排序方向枚举。
 * <p>
 * 与 {@link SortField} 配合构成排序契约：所有实现的 {@code ORDER BY} 末尾固定追加
 * {@code id} 作 tie-breaker，方向与主排序一致（见设计文档第三节第 3 点）。
 */
public enum SortDirection {

    /** 升序。 */
    ASC,

    /** 降序。 */
    DESC;

    /**
     * 大小写不敏感解析排序方向（通用解析委托 {@link ParamUtils#enumFromName}）。
     *
     * @param name 方向字符串（asc / desc）
     * @return 对应枚举；未识别返回 null，由调用方决定抛 400
     */
    public static SortDirection fromName(String name) {
        return ParamUtils.enumFromName(name, SortDirection.class);
    }
}
