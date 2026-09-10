package com.example.flowhub.export.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 导出范围请求体（JSON 绑定）：SELECTED_IDS 勾选 / FILTER 筛选两种模式共用。
 * <p>
 * 两个分支互斥的跨字段一致性由 {@code @AssertTrue} 校验，失败进入 field_errors。
 */
public record ExportSelectionRequest(

        @NotBlank(message = "mode 不能为空")
        @Pattern(regexp = "SELECTED_IDS|FILTER", message = "mode 仅支持 SELECTED_IDS/FILTER")
        String mode,

        // ==================== SELECTED_IDS 分支 ====================

        @JsonProperty("order_ids")
        @Size(max = 1000, message = "order_ids 最多 1000 个")
        List<@NotNull(message = "order_ids 不能包含 null") Long> orderIds,

        // ==================== FILTER 分支 ====================

        @JsonProperty("excluded_order_ids")
        @Size(max = 1000, message = "excluded_order_ids 最多 1000 个")
        List<Long> excludedOrderIds,

        @Valid
        @JsonProperty("filter")
        ExportFilterSnapshotRequest filter) {

    /** SELECTED_IDS 分支一致性：必须有 order_ids，且禁止 filter/excluded_order_ids。 */
    @JsonIgnore
    @AssertTrue(message = "SELECTED_IDS 模式必须携带 order_ids，且不能携带 filter 或 excluded_order_ids")
    public boolean isSelectedIdsValid() {
        if (!"SELECTED_IDS".equals(mode)) {
            return true;
        }
        return orderIds != null && !orderIds.isEmpty() && filter == null && excludedOrderIds == null;
    }

    /** FILTER 分支一致性：必须有 filter，且禁止 order_ids。 */
    @JsonIgnore
    @AssertTrue(message = "FILTER 模式必须携带 filter，且不能携带 order_ids")
    public boolean isFilterValid() {
        if (!"FILTER".equals(mode)) {
            return true;
        }
        return filter != null && orderIds == null;
    }
}
