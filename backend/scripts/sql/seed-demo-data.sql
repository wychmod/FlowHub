-- =====================================================================
-- ExportFlow 演示数据生成脚本（orders 表）
-- 由 backend/scripts/seed-demo-data.sh 在确认 orders 表为空后调用。
--
-- 设计要点：
--   1. 确定性生成：所有列的取值都由序号 n 经「取模 + 乘法散列」推导，
--      不使用任何随机函数——同样的 @seed_rows 重跑结果完全一致，便于联调与排障。
--   2. 贴近真实分布：订单状态 / 销售渠道 / 币种按业务权重分布，
--      客户名、手机号、省份、金额、下单时间具备足够多样性，
--      能支撑「按条件筛选 + Excel 导出」的完整演示。
--   3. 仅写入 orders 一张表：export_jobs 等导出任务表属尚未实现的模块，
--      由后续业务代码自行维护，演示数据不做伪造。
--
-- 前置条件：
--   - MySQL 8.0+（使用 WITH RECURSIVE 递归 CTE 生成 1..N 序号）；
--   - orders 表已由 Flyway 迁移创建（后端首次启动时自动完成）。
--
-- 入参：@seed_rows —— 生成的订单行数，由外层 shell 注入（SET @seed_rows = N）。
-- =====================================================================

-- 递归 CTE 的默认深度上限是 1000，这里放大到 100 万以支持大规模生成
SET SESSION cte_max_recursion_depth = 1000000;

-- 单条 INSERT ... SELECT 完成全部写入：整批数据在一个隐式事务中落库，要么全成功要么全不落
INSERT INTO orders
    (order_no, customer_name, status, order_status, sales_channel,
     customer_phone, currency, total_amount, shipping_province, created_at)
WITH RECURSIVE
    -- sequence：生成 1..@seed_rows 的连续序号，是所有列取值推导的唯一输入
    sequence(n) AS (
        SELECT 1
        UNION ALL
        SELECT n + 1 FROM sequence WHERE n < @seed_rows
    ),
    -- orders_seed：由序号 n 推导出每一列的取值（先算好、后插入，避免同一表达式重复书写）
    orders_seed AS (
        SELECT
            -- 订单号：固定前缀 + 8 位零填充序号，与 n 一一对应，天然满足唯一约束
            CONCAT('EF2026-', LPAD(n, 8, '0')) AS order_no,

            -- 客户名称：约每 17 单出现一家企业客户（城市 + 行业 + 有限公司）；
            -- 其余为个人客户「姓 + 名」，组合共 300 种——同一姓名反复出现即「老客户复购」
            CASE
                WHEN MOD(n, 17) = 0 THEN CONCAT(
                    ELT(MOD(n, 8) + 1, '北京', '上海', '广州', '深圳', '杭州', '成都', '武汉', '西安'),
                    ELT(MOD(n * 3, 6) + 1, '科技', '贸易', '商贸', '供应链', '电子商务', '信息技术'),
                    '有限公司')
                ELSE CONCAT(
                    ELT(MOD(n, 12) + 1, '张', '王', '李', '刘', '陈', '杨', '赵', '黄', '周', '吴', '徐', '孙'),
                    ELT(MOD(n * 7, 25) + 1, '伟', '芳', '娜', '敏', '静', '磊', '军', '洋', '勇', '艳',
                                              '杰', '涛', '明', '超', '秀兰', '建国', '丽', '强', '平', '刚',
                                              '文', '云', '峰', '玉梅', '志远'))
            END AS customer_name,

            -- 订单状态：按业务权重分布 PENDING 15% / PAID 30% / SHIPPED 25% /
            -- COMPLETED 20% / CANCELED 10%（status 与 order_status 同值双写：
            -- V6 之后 order_status 为语义列，status 保留兼容旧 schema）
            CASE
                WHEN MOD(n, 100) < 15 THEN 'PENDING'
                WHEN MOD(n, 100) < 45 THEN 'PAID'
                WHEN MOD(n, 100) < 70 THEN 'SHIPPED'
                WHEN MOD(n, 100) < 90 THEN 'COMPLETED'
                ELSE 'CANCELED'
            END AS order_status,

            -- 销售渠道：WEB 40% / APP 30% / STORE 20% / PARTNER 10%
            CASE
                WHEN MOD(n * 3, 100) < 40 THEN 'WEB'
                WHEN MOD(n * 3, 100) < 70 THEN 'APP'
                WHEN MOD(n * 3, 100) < 90 THEN 'STORE'
                ELSE 'PARTNER'
            END AS sales_channel,

            -- 客户手机号：常见号段前缀 + 8 位散列数字，拼成 11 位手机号
            CONCAT(
                ELT(MOD(n, 6) + 1, '138', '139', '150', '166', '177', '186'),
                LPAD(MOD(n * 7919, 100000000), 8, '0')) AS customer_phone,

            -- 币种：CNY 90% / USD 5% / EUR 3% / HKD 2%
            CASE
                WHEN MOD(n * 11, 100) < 90 THEN 'CNY'
                WHEN MOD(n * 11, 100) < 95 THEN 'USD'
                WHEN MOD(n * 11, 100) < 98 THEN 'EUR'
                ELSE 'HKD'
            END AS currency,

            -- 订单金额：基础值经素数乘法散列落在 0.01 ~ 20000.01 元之间；
            -- PARTNER（分销/批发）渠道为整批采购单，金额再放大 4 倍
            CAST(
                (MOD(n * 6151, 2000000) + 1)
                * CASE WHEN MOD(n * 3, 100) >= 90 THEN 4 ELSE 1 END
                / 100 AS DECIMAL(18, 2)) AS total_amount,

            -- 收件省份：16 个省市经散列近似均匀分布
            ELT(MOD(n * 13, 16) + 1,
                '北京市', '上海市', '广东省', '浙江省', '江苏省', '四川省', '湖北省', '山东省',
                '河南省', '福建省', '湖南省', '陕西省', '重庆市', '安徽省', '辽宁省', '云南省')
                AS shipping_province,

            -- 下单时间：以 2025-09-01 为基准，把 n 经素数 9973 散列到之后 365 天内的某一秒，
            -- 使订单时间铺满「近一年」且不随序号单调递增（更贴近真实下单节奏）
            TIMESTAMPADD(SECOND, MOD(n * 9973, 31536000), TIMESTAMP('2025-09-01 00:00:00'))
                AS created_at
        FROM sequence
    )
SELECT
    order_no, customer_name, order_status, order_status, sales_channel,
    customer_phone, currency, total_amount, shipping_province, created_at
FROM orders_seed
-- 按下单时间排序写入，使自增 id 的顺序与时间先后一致（贴近真实业务表）
ORDER BY created_at, order_no;
