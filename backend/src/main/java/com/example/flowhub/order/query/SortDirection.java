package com.example.flowhub.order.query;

import com.example.flowhub.common.web.param.ParamUtils;

/**
 * 排序方向枚举，与 {@link SortField} 配合使用。
 * <p>
 * 所有 ORDER BY 末尾须追加 id 作 tie-breaker，方向与主排序一致。
 */
public enum SortDirection {

    /** 升序。 */
    ASC,

    /** 降序。 */
    DESC;

    /**
     * sort_order 合法取值正则，供 OrderRequest 的 {@code @Pattern} 引用。
     * <p>
     * 新增/修改方向时须同步更新。
     */
    public static final String NAMES_PATTERN = "\\s*(?i:asc|desc)?\\s*";

    /** 大小写不敏感解析，未识别返回 null。 */
    public static SortDirection fromName(String name) {
        return ParamUtils.enumFromName(name, SortDirection.class);
    }
}
