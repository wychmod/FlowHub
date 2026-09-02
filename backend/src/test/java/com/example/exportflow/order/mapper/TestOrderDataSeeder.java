package com.example.exportflow.order.mapper;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 测试夹具：向测试库（H2）灌入 57 行确定性订单数据，口径与 seed-demo-data.sql 一致。
 */
public final class TestOrderDataSeeder {

    static final int ROW_COUNT = 57;

    private static final LocalDateTime SEED_BASE_TIME = LocalDateTime.of(2025, 9, 1, 0, 0, 0);

    private static final List<String> SURNAMES =
            List.of("张", "王", "李", "刘", "陈", "杨", "赵", "黄", "周", "吴", "徐", "孙");
    private static final List<String> GIVEN_NAMES =
            List.of("伟", "芳", "娜", "敏", "静", "磊", "军", "洋", "勇", "艳",
                    "杰", "涛", "明", "超", "秀兰", "建国", "丽", "强", "平", "刚",
                    "文", "云", "峰", "玉梅", "志远");
    private static final List<String> PHONE_PREFIXES =
            List.of("138", "139", "150", "166", "177", "186");

    /** 收件省份（与 seed 脚本 ELT(MOD(n * 13, 16) + 1, ...) 对齐）。 */
    private static final List<String> PROVINCES =
            List.of("北京市", "上海市", "广东省", "浙江省", "江苏省", "四川省", "湖北省", "山东省",
                    "河南省", "福建省", "湖南省", "陕西省", "重庆市", "安徽省", "辽宁省", "云南省");

    private TestOrderDataSeeder() {
    }

    /** 清空 orders 表并重灌 57 行确定性订单（幂等，可在 @BeforeEach 中反复调用）。 */
    public static void seed(JdbcTemplate jdbcTemplate) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为空");
        jdbcTemplate.update("DELETE FROM orders");
        for (int n = 1; n <= ROW_COUNT; n++) {
            String orderStatus = weightedStatus(n);
            String salesChannel = weightedChannel(n);
            jdbcTemplate.update("""
                    INSERT INTO orders (id, order_no, customer_name, status, order_status, sales_channel,
                                        customer_phone, currency, total_amount, shipping_province, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (long) n,
                    "EF2026-%08d".formatted(n),
                    SURNAMES.get(n % SURNAMES.size()) + GIVEN_NAMES.get((n * 7) % GIVEN_NAMES.size()),
                    orderStatus,
                    orderStatus,
                    salesChannel,
                    PHONE_PREFIXES.get(n % PHONE_PREFIXES.size())
                            + "%08d".formatted((n * 7919) % 100_000_000L),
                    weightedCurrency(n),
                    mockAmount(n, salesChannel),
                    PROVINCES.get((n * 13) % PROVINCES.size()),
                    Timestamp.valueOf(SEED_BASE_TIME.plusSeconds((n * 9973L) % 31_536_000L)));
        }
    }

    private static String weightedStatus(int n) {
        int bucket = n % 100;
        if (bucket < 15) return "PENDING";
        if (bucket < 45) return "PAID";
        if (bucket < 70) return "SHIPPED";
        if (bucket < 90) return "COMPLETED";
        return "CANCELED";
    }

    private static String weightedChannel(int n) {
        int bucket = (n * 3) % 100;
        if (bucket < 40) return "WEB";
        if (bucket < 70) return "APP";
        if (bucket < 90) return "STORE";
        return "PARTNER";
    }

    private static String weightedCurrency(int n) {
        int bucket = (n * 11) % 100;
        if (bucket < 90) return "CNY";
        if (bucket < 95) return "USD";
        if (bucket < 98) return "EUR";
        return "HKD";
    }

    private static BigDecimal mockAmount(int n, String channel) {
        long cents = (n * 6151L) % 2_000_000L + 1;
        if ("PARTNER".equals(channel)) {
            cents *= 4;
        }
        return BigDecimal.valueOf(cents, 2);
    }
}
