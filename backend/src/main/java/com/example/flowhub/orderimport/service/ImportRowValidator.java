package com.example.flowhub.orderimport.service;

import com.example.flowhub.common.web.param.ParamUtils;
import com.example.flowhub.orderimport.command.ImportColumn;
import com.example.flowhub.orderimport.command.ImportCurrency;
import com.example.flowhub.orderimport.command.ImportOrderStatus;
import com.example.flowhub.orderimport.command.ImportSalesChannel;
import com.example.flowhub.orderimport.entity.ImportOrderRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

/**
 * 行级校验引擎（docs/order-import-design.md §6.2/B9）：逐列的格式/枚举/必填/长度/金额/时间校验。
 * <p>
 * 不持跨行状态（文件内查重由执行体持有），每次调用产出一个 {@link Outcome}——通过则带可入库行，
 * 否则带错误明细。枚举一律委托 {@link ParamUtils#enumFromName}（大小写不敏感），
 * 金额 {@link BigDecimal} 比较一律用 {@code compareTo}。
 */
@Component
public class ImportRowValidator {

    // uuuu 非 yyyy：STRICT 模式下 year-of-era 缺 era 字段无法归约，uuuu（前推年）可被 STRICT 正常解析。
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final int MAX_ORDER_NO = 64;
    private static final int MAX_CUSTOMER_NAME = 128;
    private static final int MAX_PHONE = 32;
    private static final int MAX_PROVINCE = 64;
    private static final int MAX_AMOUNT_SCALE = 2;
    /** DECIMAL(18,2) 上界（16 位整数 + 2 位小数）。 */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");

    /** 校验结果：valid=true 时 row 已归一为可入库值对象；否则 errors 含逐列错误明细。 */
    public record Outcome(boolean valid, List<FieldError> errors, ImportOrderRow row) {
    }

    /** 单列错误：column 为失败列（标题可用于错误报告「错误列」列），reason 为具体文案。 */
    public record FieldError(ImportColumn column, String reason) {
    }

    /** 校验一行（cells 为按 ImportColumn 列序对齐的 9 元数组，空单元格为 null）。 */
    public Outcome validate(String[] cells) {
        if (cells == null || cells.length != ImportColumn.all().size()) {
            return new Outcome(false, List.of(new FieldError(ImportColumn.ORDER_NO, "行列数与导入模板不一致")), null);
        }
        List<FieldError> errors = new ArrayList<>();

        String orderNo = required(cells[0], "订单号为空", ImportColumn.ORDER_NO, errors);
        rejectInjection(cells[0], ImportColumn.ORDER_NO, errors);
        if (orderNo != null && orderNo.length() > MAX_ORDER_NO) {
            errors.add(new FieldError(ImportColumn.ORDER_NO, "订单号过长"));
        }

        String orderStatus = enumColumn(cells[1], ImportColumn.ORDER_STATUS, ImportOrderStatus.class,
                "订单状态非法", errors);
        String salesChannel = enumColumn(cells[2], ImportColumn.SALES_CHANNEL, ImportSalesChannel.class,
                "销售渠道非法", errors);

        String customerName = required(cells[3], "客户姓名为空", ImportColumn.CUSTOMER_NAME, errors);
        rejectInjection(cells[3], ImportColumn.CUSTOMER_NAME, errors);
        if (customerName != null && customerName.length() > MAX_CUSTOMER_NAME) {
            errors.add(new FieldError(ImportColumn.CUSTOMER_NAME, "客户姓名过长"));
        }

        // 客户手机号选填：非空仅限长度
        String customerPhone = ParamUtils.trimToNull(cells[4]);
        rejectInjection(cells[4], ImportColumn.CUSTOMER_PHONE, errors);
        if (customerPhone != null && customerPhone.length() > MAX_PHONE) {
            errors.add(new FieldError(ImportColumn.CUSTOMER_PHONE, "客户手机号过长"));
        }

        BigDecimal totalAmount = amount(cells[5], errors);

        String currency = enumColumn(cells[6], ImportColumn.CURRENCY, ImportCurrency.class, "币种非法", errors);

        // 收货省份选填：非空仅限长度
        String province = ParamUtils.trimToNull(cells[7]);
        rejectInjection(cells[7], ImportColumn.SHIPPING_PROVINCE, errors);
        if (province != null && province.length() > MAX_PROVINCE) {
            errors.add(new FieldError(ImportColumn.SHIPPING_PROVINCE, "收货省份过长"));
        }

        LocalDateTime createdAt = createdAt(cells[8], errors);

        if (!errors.isEmpty()) {
            return new Outcome(false, List.copyOf(errors), null);
        }
        ImportOrderRow row = new ImportOrderRow(
                orderNo, orderStatus, salesChannel, customerName, customerPhone,
                totalAmount, currency, province, createdAt);
        return new Outcome(true, List.of(), row);
    }

    /** 必填文本列：空白归一为 null 并记错误，否则返回归一值。 */
    private String required(String raw, String message, ImportColumn column, List<FieldError> errors) {
        String value = ParamUtils.trimToNull(raw);
        if (value == null) {
            errors.add(new FieldError(column, message));
        }
        return value;
    }

    /** 枚举列：大小写不敏感解析，非法（含空）记错误，返回大写归一值。 */
    private <T extends Enum<T>> String enumColumn(String raw, ImportColumn column, Class<T> enumType,
                                                  String message, List<FieldError> errors) {
        T parsed = ParamUtils.enumFromName(raw, enumType);
        if (parsed == null) {
            errors.add(new FieldError(column, message));
            return null;
        }
        return parsed.name();
    }

    /** 公式注入防护（横切）：以 = + - @ 开头的文本判危险输入（Excel 会当公式执行）。 */
    private void rejectInjection(String raw, ImportColumn column, List<FieldError> errors) {
        String value = ParamUtils.trimToNull(raw);
        if (value == null) {
            return;
        }
        char c = value.charAt(0);
        if (c == '=' || c == '+' || c == '-' || c == '@') {
            errors.add(new FieldError(column, "内容以 " + c + " 开头，可能为公式注入"));
        }
    }

    /** 金额：必填、非负、最多 2 位小数、DECIMAL(18,2) 上下界，失败记错误并返回 null。 */
    private BigDecimal amount(String raw, List<FieldError> errors) {
        String value = ParamUtils.trimToNull(raw);
        if (value == null) {
            errors.add(new FieldError(ImportColumn.TOTAL_AMOUNT, "订单金额为空"));
            return null;
        }
        final BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException ex) {
            errors.add(new FieldError(ImportColumn.TOTAL_AMOUNT, "订单金额必须为非负数字"));
            return null;
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            errors.add(new FieldError(ImportColumn.TOTAL_AMOUNT, "订单金额必须为非负数字"));
            return null;
        }
        if (amount.scale() > MAX_AMOUNT_SCALE) {
            errors.add(new FieldError(ImportColumn.TOTAL_AMOUNT, "订单金额最多 2 位小数"));
            return null;
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            errors.add(new FieldError(ImportColumn.TOTAL_AMOUNT, "订单金额超出可存储范围"));
            return null;
        }
        return amount;
    }

    /** 下单时间：严格文本 yyyy-MM-dd HH:mm:ss（决策 4，不兼容 Excel 日期单元格）。 */
    private LocalDateTime createdAt(String raw, List<FieldError> errors) {
        String value = ParamUtils.trimToNull(raw);
        if (value == null) {
            errors.add(new FieldError(ImportColumn.CREATED_AT, "下单时间为空"));
            return null;
        }
        try {
            return LocalDateTime.parse(value, TIME_FORMAT);
        } catch (DateTimeParseException ex) {
            errors.add(new FieldError(ImportColumn.CREATED_AT, "下单时间须为 yyyy-MM-dd HH:mm:ss 文本"));
            return null;
        }
    }
}