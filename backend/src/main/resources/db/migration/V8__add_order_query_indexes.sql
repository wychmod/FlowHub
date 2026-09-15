-- V8__add_order_query_indexes.sql
-- 订单查询能力（条件筛选 + 排序）索引落地。
--
-- 索引取舍原则：
--   1. 等值列在前、范围/排序列在后（最左前缀原则）设计联合索引；
--   2. 单列索引必须有足够选择性（典型查询命中比例 < 10%~20%），
--      否则优化器会放弃索引走全表扫描，索引沦为摆设还白付写入维护成本；
--   3. 低基数枚举列不单独建索引：sales_channel 的 WEB 占约 40%、currency 的 CNY 占约 90%
--      （分布见 seed-demo-data.sql），单列过滤区分度远不达标；它们的正确用法是作为
--      联合索引的等值前缀参与组合过滤，或作为其他索引筛完后的回表过滤条件；
--   4. 前后通配模糊 LIKE 的 customer_name 无法使用普通 B+ 树索引，不为其建索引；
--      shipping_province 16 省均匀分布（单省约 6.25%），低于选择性阈值，也不建。
--   注：order_no 的前缀 LIKE 与精确等值由 V1 既有唯一约束 uk_orders_order_no 支撑，无需新建。

-- 状态筛选 + 时间排序/范围（最高频组合）
CREATE INDEX idx_orders_status_created ON orders (order_status, created_at);

-- 金额范围 + 金额排序
CREATE INDEX idx_orders_amount ON orders (total_amount);

-- 默认时间倒序翻页；InnoDB 二级索引隐含主键，可免 filesort 服务 ORDER BY created_at DESC, id DESC
CREATE INDEX idx_orders_created_at ON orders (created_at);

-- 手机号精确等值查询（11 位手机号接近唯一，选择性极高，B+ 树等值命中）
CREATE INDEX idx_orders_phone ON orders (customer_phone);
