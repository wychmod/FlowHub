package com.example.exportflow.order.dto;

import com.example.exportflow.order.query.SortDirection;
import com.example.exportflow.order.query.SortField;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.BindParam;

/**
 * 订单查询请求（record + 构造器绑定，见 docs/order-query-design.md 第二节、第四节第 2 点）。
 * <p>
 * 分页 + 筛选 + 排序三组字段共置一个 DTO；snake_case 请求参数经
 * {@link BindParam} 绑定到 record 组件（Spring Framework 6.1+ 构造器绑定），
 * 不再使用别名 getter/setter。Bean Validation 注解直接标注在组件上。
 * <p>
 * 空值契约（设计文档第七节）：筛选字段一律 {@code String} 原样接收，
 * {@code null} 是唯一合法的「未传」表达（空白串由 Service 归一化为 null），
 * 数值/时间/枚举的解析统一收敛在 Service 归一化阶段，禁止空串/0 哨兵值。
 *
 * @param page           页码，从 1 开始；缺省由紧凑构造器兜底为 1
 * @param pageSize       每页条数，1-100；缺省兜底为 20
 * @param orderStatus    订单状态多值筛选，逗号分隔（如 PAID,SHIPPED）
 * @param salesChannel   销售渠道多值筛选，逗号分隔
 * @param currency       币种多值筛选，逗号分隔
 * @param customerName   客户姓名模糊匹配
 * @param orderNo        订单号前缀匹配
 * @param customerPhone  客户手机号精确等值（11 位数字）
 * @param totalAmountMin 金额区间下界（含）
 * @param totalAmountMax 金额区间上界（含）
 * @param createdAtBegin 下单时间下界（含），本地时间 yyyy-MM-dd'T'HH:mm:ss[.SSS]
 * @param createdAtEnd   下单时间上界（不含，左闭右开）
 * @param sortBy         排序字段（白名单见 SortField）；缺省取 created_at；
 *                       DTO 层经 @Pattern 引用 SortField.NAMES_PATTERN 前置拦截非白名单取值
 * @param sortOrder      排序方向 asc/desc；缺省用 sortBy 字段的默认方向；
 *                       不允许脱离 sort_by 单独传（Service 层 400）；
 *                       DTO 层经 @Pattern 引用 SortDirection.NAMES_PATTERN 前置拦截非法方向
 */
public record OrderRequest(

        // ==================== 分页 ====================

        @Min(value = 1, message = "page 必须大于等于 1")
        Integer page,

        @BindParam("page_size")
        @Min(value = 1, message = "page_size 必须在 1-100 之间")
        @Max(value = 100, message = "page_size 必须在 1-100 之间")
        Integer pageSize,

        // ==================== 筛选 ====================

        @BindParam("order_status")
        @Size(max = 64, message = "order_status 过长")
        String orderStatus,

        @BindParam("sales_channel")
        @Size(max = 64, message = "sales_channel 过长")
        String salesChannel,

        @BindParam("currency")
        @Size(max = 32, message = "currency 过长")
        String currency,

        @BindParam("customer_name")
        @Size(max = 128, message = "customer_name 过长")
        String customerName,

        @BindParam("order_no")
        @Size(max = 64, message = "order_no 过长")
        String orderNo,

        @BindParam("customer_phone")
        @Size(max = 32, message = "customer_phone 过长")
        String customerPhone,

        @BindParam("total_amount_min")
        @Size(max = 20, message = "total_amount_min 过长")
        String totalAmountMin,

        @BindParam("total_amount_max")
        @Size(max = 20, message = "total_amount_max 过长")
        String totalAmountMax,

        @BindParam("created_at_begin")
        @Size(max = 32, message = "created_at_begin 过长")
        String createdAtBegin,

        @BindParam("created_at_end")
        @Size(max = 32, message = "created_at_end 过长")
        String createdAtEnd,

        // ==================== 排序 ====================

        @BindParam("sort_by")
        @Size(max = 64, message = "sort_by 过长")
        @Pattern(regexp = SortField.NAMES_PATTERN,
                message = "sort_by 仅支持 created_at/total_amount/order_no/id")
        String sortBy,

        @BindParam("sort_order")
        @Size(max = 16, message = "sort_order 过长")
        @Pattern(regexp = SortDirection.NAMES_PATTERN,
                message = "sort_order 仅支持 asc/desc")
        String sortOrder) {

    /**
     * 紧凑构造器：为未传的分页参数兜底默认值。
     * <p>
     * 分页字段用包装类型，缺失参数在构造器绑定时注入 {@code null} 而非 {@code 0}，
     * 「未传」与「传了 0」在进入校验前即可区分（0 会被 @Min 拒绝，null 走默认值）。
     */
    public OrderRequest {
        if (page == null) {
            page = 1;
        }
        if (pageSize == null) {
            pageSize = 20;
        }
    }
}
