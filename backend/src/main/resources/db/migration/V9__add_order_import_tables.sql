-- V9__add_order_import_tables.sql
-- 订单 Excel 导入：新增 import_jobs（导入任务）与 import_job_attempts（执行尝试）两张表。
-- 依据 docs/order-import-design.md §3 的 DDL 落地；状态机含 PARTIAL（部分成功）三终态语义。
--
-- 关键设计点：
-- 1. import_jobs 不建幂等键/request_hash 列（创建接口无幂等键，重复上传 = 两个独立任务，
--    数据层靠 orders.order_no 唯一约束兜底）;
-- 2. succeeded_rows/skipped_rows 落独立计数列供列表派生与 PARTIAL 判定（skipped>0 即 PARTIAL）;
-- 3. error_report_path 为 PARTIAL 时的错误报告 xlsx 相对路径; error_summary 存 Top N 错误分类 JSON;
-- 4. 建 (status, expired_at) 过期清理索引与 (status, lease_expires_at) 失联恢复索引，对齐导出侧。

CREATE TABLE import_jobs (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,  -- id: 任务主键
    job_no             VARCHAR(32)   NOT NULL,             -- job_no: 任务号（业务唯一）
    status             VARCHAR(16)   NOT NULL,             -- status: PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/EXPIRED
    version            BIGINT        NOT NULL DEFAULT 0,   -- version: 乐观锁（SSE/HTTP 校准与并发收敛 guard）
    file_name          VARCHAR(255)  NOT NULL,             -- file_name: 原始上传文件名（含 .xlsx）
    file_path          VARCHAR(512)  DEFAULT NULL,         -- file_path: 上传原件相对路径（受控 importRoot）
    total_rows         INT           NOT NULL DEFAULT 0,   -- total_rows: 数据行总数（受理时结构级扫描确定）
    processed_rows     INT           NOT NULL DEFAULT 0,   -- processed_rows: 已处理行数（进度推进）
    succeeded_rows     INT           NOT NULL DEFAULT 0,   -- succeeded_rows: 成功入库行数
    skipped_rows       INT           NOT NULL DEFAULT 0,   -- skipped_rows: 跳过行数（错误行）
    error_report_path  VARCHAR(512)  DEFAULT NULL,         -- error_report_path: PARTIAL 时错误报告 xlsx 相对路径
    error_summary      VARCHAR(2000) DEFAULT NULL,         -- error_summary: 错误分类 Top N JSON [{reason,count}]
    attempt_count      INT           NOT NULL DEFAULT 0,   -- attempt_count: 已开始的真实执行次数
    started_at         DATETIME      DEFAULT NULL,         -- started_at: 首次开始执行时间
    last_heartbeat_at  DATETIME      DEFAULT NULL,         -- last_heartbeat_at: 最近一次心跳
    lease_expires_at   DATETIME      DEFAULT NULL,         -- lease_expires_at: 租约到期时间（失联恢复信号）
    finished_at        DATETIME      DEFAULT NULL,         -- finished_at: 终态完成时间
    expired_at         DATETIME      DEFAULT NULL,         -- expired_at: 保留期截止（期满清理 → EXPIRED）
    error_code         VARCHAR(64)   DEFAULT NULL,         -- error_code: 失败错误码
    error_message      VARCHAR(500)  DEFAULT NULL,         -- error_message: 失败原因（截断至列宽 500）
    created_at         DATETIME      NOT NULL,             -- created_at: 创建时间
    CONSTRAINT uk_import_jobs_job_no UNIQUE (job_no),      -- 唯一约束：任务号不重复
    KEY idx_import_jobs_status (status),
    KEY idx_import_jobs_created (created_at),
    KEY idx_import_jobs_expiration (status, expired_at),   -- 过期清理扫描
    KEY idx_import_jobs_running_lease (status, lease_expires_at)  -- 失联恢复扫描
);

-- import_job_attempts：一次任务可多次重试（FAILED→PENDING），每次尝试一行，执行事实/审计记录。
-- 与导出侧对称；attempt 状态含 PARTIAL（该次执行存在跳过行）。
CREATE TABLE import_job_attempts (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,  -- id: 尝试记录主键
    job_id        BIGINT        NOT NULL,             -- job_id: 所属导入任务ID
    attempt_no    INT           NOT NULL,             -- attempt_no: 第几次尝试（从 1 开始）
    status        VARCHAR(16)   NOT NULL,             -- status: RUNNING/SUCCEEDED/PARTIAL/FAILED
    error_code    VARCHAR(64)   DEFAULT NULL,         -- error_code: 该次尝试错误码
    error_message VARCHAR(500)  DEFAULT NULL,         -- error_message: 该次尝试错误信息
    started_at    DATETIME      DEFAULT NULL,         -- started_at: 本次尝试开始时间
    finished_at   DATETIME      DEFAULT NULL,         -- finished_at: 本次尝试结束时间
    created_at    DATETIME      NOT NULL,             -- created_at: 尝试记录创建时间
    CONSTRAINT uk_attempt_import_job_no UNIQUE (job_id, attempt_no),  -- 同任务同序号仅一条（库层防重复审计）
    KEY idx_attempt_import_job (job_id)
);