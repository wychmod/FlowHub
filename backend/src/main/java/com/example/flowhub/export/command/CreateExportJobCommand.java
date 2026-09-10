package com.example.flowhub.export.command;

import com.example.flowhub.common.web.error.BusinessException;
import com.example.flowhub.common.web.param.ParamUtils;
import com.example.flowhub.export.dto.CreateExportJobRequest;
import com.example.flowhub.export.dto.ExportFilterSnapshotRequest;
import com.example.flowhub.export.dto.ExportSelectionRequest;
import com.example.flowhub.export.error.ExportErrorCode;
import com.example.flowhub.order.query.OrderCriteria;
import com.example.flowhub.order.query.OrderFilterWhitelist;
import com.example.flowhub.order.query.OrderSort;
import com.example.flowhub.order.query.SortDirection;
import com.example.flowhub.order.query.SortField;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 创建导出任务命令（规范化后的稳定形态）：同义请求规范化结果相同，作为 request hash 的输入。
 * <p>
 * criteria 恒非 null：SELECTED_IDS 模式 ids 为规范化勾选 ID；FILTER 模式含筛选条件与排除 ID。
 * fileName 为规范化后的业务名，null 表示未传（落库时由 Service 生成默认名，不参与 hash）。
 */
public record CreateExportJobCommand(
        ExportSelectionMode mode,
        OrderCriteria criteria,
        List<ExportColumn> columns,
        String fileName) {

    /** 文件名非法字符：路径分隔符与 Windows 文件名保留字符、控制字符。 */
    private static final Pattern ILLEGAL_FILE_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    /** 勾选 ID 上限（DTO @Size 已挡常规路径，此处为规范化阶段的防御兜底）。 */
    private static final int MAX_SELECTED_IDS = 1000;

    /** 从请求 DTO 规范化构建命令（ID 去重排序、列白名单校验重排、文件名清理、筛选快照解析）。 */
    public static CreateExportJobCommand from(CreateExportJobRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        ExportSelectionRequest selection = request.selection();
        ExportSelectionMode mode = ExportSelectionMode.fromName(selection.mode());
        if (mode == null) {
            // DTO @Pattern 已挡，防御性兜底
            throw BusinessException.validation("mode 仅支持 SELECTED_IDS/FILTER");
        }
        OrderCriteria criteria = switch (mode) {
            case SELECTED_IDS -> selectedCriteria(selection.orderIds());
            case FILTER -> filterCriteria(selection.filter(), selection.excludedOrderIds());
        };
        return new CreateExportJobCommand(
                mode, criteria, normalizeColumns(request.columns()), normalizeFileName(request.fileName()));
    }

    /** 勾选模式取数条件：规范化 ID 填充 ids（空集/超限由 DTO 挡住，防御再校验）。 */
    private static OrderCriteria selectedCriteria(List<Long> rawIds) {
        List<Long> ids = normalizeIds(rawIds);
        if (ids.isEmpty()) {
            throw new BusinessException(ExportErrorCode.EXPORT_SELECTED_EMPTY);
        }
        if (ids.size() > MAX_SELECTED_IDS) {
            throw new BusinessException(ExportErrorCode.EXPORT_SELECTED_TOO_MANY);
        }
        return new OrderCriteria(ids, List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, null,
                SortField.CREATED_AT, SortDirection.DESC);
    }

    /** 筛选模式取数条件：解析快照为 OrderCriteria，excluded_order_ids 归一后填充。 */
    private static OrderCriteria filterCriteria(ExportFilterSnapshotRequest filter, List<Long> rawExcludedIds) {
        Objects.requireNonNull(filter, "filter 不能为空");
        List<String> statuses = ParamUtils.parseMultiEnum(
                filter.orderStatus(), OrderFilterWhitelist.STATUSES, "order_status");
        List<String> salesChannels = ParamUtils.parseMultiEnum(
                filter.salesChannel(), OrderFilterWhitelist.SALES_CHANNELS, "sales_channel");
        List<String> currencies = ParamUtils.parseMultiEnum(
                filter.currency(), OrderFilterWhitelist.CURRENCIES, "currency");

        String customerPhone = ParamUtils.parsePhone(filter.customerPhone(), "customer_phone");
        BigDecimal amountMin = ParamUtils.parseDecimal(filter.totalAmountMin(), "total_amount_min");
        BigDecimal amountMax = ParamUtils.parseDecimal(filter.totalAmountMax(), "total_amount_max");
        if (amountMin != null && amountMax != null && amountMin.compareTo(amountMax) > 0) {
            throw BusinessException.validation("total_amount_min 不能大于 total_amount_max");
        }
        LocalDateTime createdAtBegin = ParamUtils.parseDateTime(filter.createdAtBegin(), "created_at_begin");
        LocalDateTime createdAtEnd = ParamUtils.parseDateTime(filter.createdAtEnd(), "created_at_end");
        if (createdAtBegin != null && createdAtEnd != null && !createdAtBegin.isBefore(createdAtEnd)) {
            throw BusinessException.validation("created_at_begin 必须早于 created_at_end");
        }

        OrderSort sort = OrderSort.resolve(filter.sortBy(), filter.sortOrder());
        return new OrderCriteria(List.of(), normalizeIds(rawExcludedIds),
                statuses, salesChannels, currencies,
                ParamUtils.trimToNull(filter.customerName()),
                ParamUtils.trimToNull(filter.orderNo()),
                customerPhone, amountMin, amountMax, createdAtBegin, createdAtEnd,
                sort.field(), sort.direction());
    }

    /** ID 集合归一：剔 null + 去重 + 自然序排序。 */
    private static List<Long> normalizeIds(List<Long> rawIds) {
        if (rawIds == null) {
            return List.of();
        }
        return rawIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /** 列规范化：trim + 白名单校验 + 去重，按白名单定义序输出；解析后为空或含未知列抛 EXPORT_COLUMNS_INVALID。 */
    private static List<ExportColumn> normalizeColumns(List<String> rawColumns) {
        Objects.requireNonNull(rawColumns, "columns 不能为空");
        Set<ExportColumn> parsed = new LinkedHashSet<>();
        for (String raw : rawColumns) {
            String normalized = ParamUtils.trimToNull(raw);
            if (normalized == null) {
                continue; // DTO @NotBlank 已挡，防御跳过
            }
            ExportColumn column = ParamUtils.enumFromName(normalized, ExportColumn.class);
            if (column == null) {
                throw new BusinessException(ExportErrorCode.EXPORT_COLUMNS_INVALID,
                        "columns 含未知导出列：" + normalized);
            }
            parsed.add(column);
        }
        if (parsed.isEmpty()) {
            throw new BusinessException(ExportErrorCode.EXPORT_COLUMNS_INVALID, "columns 解析后为空");
        }
        // EnumSet 按枚举定义序迭代，保证列输出顺序只由白名单决定
        return List.copyOf(EnumSet.copyOf(parsed));
    }

    /** 文件名规范化：trim + 剔除非法字符；清理后为空视为未传（返回 null，落库时生成默认名）。 */
    private static String normalizeFileName(String raw) {
        String normalized = ParamUtils.trimToNull(raw);
        if (normalized == null) {
            return null;
        }
        return ParamUtils.trimToNull(ILLEGAL_FILE_NAME_CHARS.matcher(normalized).replaceAll(""));
    }
}
