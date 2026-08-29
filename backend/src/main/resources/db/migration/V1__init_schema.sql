-- V1__init_schema.sql
-- 初始化导入：订单、导出任务、任务执行尝试、Outbox 事件 共 4 张表。
-- 依据参考项目 project-export-flow 的 V1__init_schema.sql 完整迁移而来，并为每个字段补充中文说明。
--
-- 采用 H2/MySQL 兼容方言（TIMESTAMP/VARCHAR/DECIMAL(18,2)/LONGTEXT、UNIQUE 约束、
-- 独立的 CREATE INDEX），保证 @SpringBootTest 在使用 H2(MySQL 模式) 作为测试数据源时
-- 也能正常执行该迁移（见 src/test/resources/application.yml）。

-- orders：订单表，导出中心的数据源。业务唯一键为 order_no。
CREATE TABLE orders (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY, -- id：订单主键
    order_no      VARCHAR(64)   NOT NULL,            -- order_no：订单号（业务唯一）
    customer_name VARCHAR(128)  NOT NULL,            -- customer_name：客户名称
    status        VARCHAR(32)   NOT NULL,            -- status：订单状态（PENDING/PAID/SHIPPED/COMPLETED/CANCELED）
    total_amount  DECIMAL(18,2) NOT NULL,            -- total_amount：订单总金额（单位元，保留2位小数）
    created_at    TIMESTAMP     NOT NULL,            -- created_at：创建时间（导出的排序字段）
    CONSTRAINT uk_orders_order_no UNIQUE (order_no)  -- 唯一约束：订单号不重复
);

-- export_jobs：导出任务表，记录一次导出任务的状态推进。
CREATE TABLE export_jobs (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY, -- id：任务主键
    job_no                 VARCHAR(64) NOT NULL,              -- job_no：任务号（业务唯一）
    status                 VARCHAR(32) NOT NULL,              -- status：任务状态（PENDING/RUNNING/SUCCEEDED/FAILED/EXPIRED）
    max_order_id_at_create BIGINT      NOT NULL,              -- max_order_id_at_create：创建时的最大订单ID，作为执行一致性边界
    version                BIGINT      NOT NULL DEFAULT 0,    -- version：乐观锁版本号（并发更新时比对）
    created_at             TIMESTAMP   NOT NULL,              -- created_at：创建时间
    updated_at             TIMESTAMP   NOT NULL,              -- updated_at：最后更新时间
    CONSTRAINT uk_export_jobs_job_no UNIQUE (job_no)          -- 唯一约束：任务号不重复
);

-- export_job_attempts：导出任务单次执行尝试表，一次任务可多次重试，每次一行。
CREATE TABLE export_job_attempts (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,  -- id：尝试记录主键
    job_id       BIGINT      NOT NULL,               -- job_id：所属导出任务ID（关联 export_jobs.id）
    attempt_no   INT         NOT NULL,               -- attempt_no：第几次尝试（从1开始）
    status       VARCHAR(32) NOT NULL,               -- status：尝试结果（RUNNING/SUCCEEDED/FAILED）
    started_at   TIMESTAMP,                          -- started_at：本次尝试开始时间（未开始则NULL）
    finished_at  TIMESTAMP,                          -- finished_at：本次尝试结束时间（未结束则NULL）
    created_at   TIMESTAMP   NOT NULL                -- created_at：尝试记录创建时间
);

-- 唯一索引：同一任务的同一尝试序号仅允许一条，从数据库层面保证重试幂等（防止 MQ 重复消费）。
CREATE UNIQUE INDEX uk_attempt_job_no ON export_job_attempts (job_id, attempt_no);

-- outbox_events：事务发件箱表，业务写库与发 MQ 消息同库落盘，由分发器异步推送，保证最终一致。
CREATE TABLE outbox_events (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY, -- id：事件主键
    aggregate_type VARCHAR(64) NOT NULL,              -- aggregate_type：聚合类型（如 EXPORT_JOB）
    aggregate_id   BIGINT      NOT NULL,              -- aggregate_id：聚合ID，关联业务记录（如导出任务ID）
    event_type     VARCHAR(64) NOT NULL,              -- event_type：事件类型（如 EXPORT_JOB_CREATED）
    payload        LONGTEXT    NOT NULL,              -- payload：事件载荷（序列化的 JSON 字符串）
    published_at   TIMESTAMP,                         -- published_at：已推送时间（NULL 表示尚未推送）
    created_at     TIMESTAMP   NOT NULL               -- created_at：事件创建时间
);

-- 索引：按“是否已发布(published_at)”加“创建时间(created_at)”建立，供分发器高效捞取最早未发布的记录。
CREATE INDEX idx_outbox_unpublished ON outbox_events (published_at, created_at);