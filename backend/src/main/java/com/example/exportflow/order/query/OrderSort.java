package com.example.exportflow.order.query;

import com.example.exportflow.common.web.error.BusinessException;
import com.example.exportflow.common.web.param.ParamUtils;

/**
 * 排序规格值对象（字段 + 方向）。
 * <p>
 * 解析规则：均缺省用 created_at+desc；只传 sort_by 用字段默认方向；sort_order 脱离 sort_by 抛 400。
 */
public record OrderSort(SortField field, SortDirection direction) {

    /** 解析 sort_by/sort_order 原始入参，非法值抛 VALIDATION_ERROR。 */
    public static OrderSort resolve(String rawField, String rawDirection) {
        String normalizedField = ParamUtils.trimToNull(rawField);
        String normalizedDirection = ParamUtils.trimToNull(rawDirection);
        if (normalizedField == null && normalizedDirection != null) {
            throw BusinessException.validation("sort_order 不能脱离 sort_by 单独使用");
        }
        if (normalizedField == null) {
            return new OrderSort(SortField.CREATED_AT, SortField.CREATED_AT.defaultDirection());
        }
        SortField field = SortField.fromName(normalizedField);
        if (field == null) {
            throw BusinessException.validation("不支持的排序字段：" + normalizedField);
        }
        SortDirection direction = field.defaultDirection();
        if (normalizedDirection != null) {
            direction = SortDirection.fromName(normalizedDirection);
            if (direction == null) {
                throw BusinessException.validation("不支持的排序方向：" + normalizedDirection);
            }
        }
        return new OrderSort(field, direction);
    }
}
