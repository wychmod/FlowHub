-- V6__complete_order_export_columns.sql
-- 补全订单可导出业务列：为 orders 表新增导出中心需要的订单明细字段，并将已有历史数据按规则回填。
-- 依据参考项目 project-export-flow 的 V6__complete_order_export_columns.sql 完整迁移而来，并补充详细说明。
--
-- 背景：V1 的 orders 表（参考项目初版演示 schema）只有精简字段（order_no/customer_name/status/...），
-- 不足以支撑「按渠道、按币种、按省份等维度导出」。本版本补齐导出所需的业务列，
-- 让 Excel 导出能包含丰富列，并向下兼容已有的 status 字段。

-- ======================== 一、新增列 =======================

-- 保留原有的 status 列，用于兼容初版演示 schema（不加 NOT NULL、不做删除，避免破坏既有代码/数据）。
-- order_status：订单状态，语义上替代 status（取值 PENDING/PAID/SHIPPED/COMPLETED/CANCELED）。
-- 新增列设为可空(NULL)，随后用 UPDATE 回填；理由见下方回填语句。
ALTER TABLE orders ADD COLUMN order_status VARCHAR(32) NULL;

-- sales_channel：销售渠道（WEB/APP/STORE/PARTNER），导出列之一。
ALTER TABLE orders ADD COLUMN sales_channel VARCHAR(32) NULL;

-- customer_phone：客户联系电话，导出列之一（历史数据无该字段，先置空串占位）。
ALTER TABLE orders ADD COLUMN customer_phone VARCHAR(32) NULL;

-- currency：订单币种（如 CNY/USD），导出列之一（历史数据默认按 CNY 处理）。
ALTER TABLE orders ADD COLUMN currency VARCHAR(8) NULL;

-- shipping_province：收件省份，导出列之一（历史数据无该字段，先置空串占位）。
ALTER TABLE orders ADD COLUMN shipping_province VARCHAR(64) NULL;

-- ======================== 二、数据回填 =======================

-- 新增列对既有行而言都是 NULL；此处按统一规则回填：
--   order_status        ← 取原 status 列值（新旧语义一致，直接迁移）
--   sales_channel       ← 历史数据无渠道信息，统一按 'WEB'（网上商城）处理
--   customer_phone      ← 无历史数据，置空串占位
--   currency            ← 无历史数据，统一按 'CNY'(人民币) 处理
--   shipping_province   ← 无历史数据，置空串占位
-- 仅回填 order_status IS NULL 的行：避开已被回填过的事实，保证本脚本可安全重放。
UPDATE orders
SET order_status = status,
    sales_channel = 'WEB',
    customer_phone = '',
    currency = 'CNY',
    shipping_province = ''
WHERE order_status IS NULL;