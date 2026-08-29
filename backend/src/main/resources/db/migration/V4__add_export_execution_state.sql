-- V4__add_export_execution_state.sql
-- 导出任务执行状态字段：为 export_jobs / export_job_attempts 补充进度、耗时、产物文件、错误信息等执行期字段，
-- 并为「过期任务扫描」建立索引。
-- 依据参考项目 project-export-flow 的 V4__add_export_execution_state.sql 完整迁移而来，并补充详细说明。

-- ======================== 一、export_jobs（导出任务表）======================

-- 已处理行数：执行器每写完一批订单累加，用于进度条（processed/total）与断点续跑。
ALTER TABLE export_jobs
    ADD COLUMN processed_rows BIGINT NOT NULL DEFAULT 0;

-- 累计尝试次数：任务总体被尝试执行的次数（每次尝试对应 export_job_attempts 一行）。
-- 达到上限（max_attempts）仍失败则任务标记为失败，避免无限重试。
ALTER TABLE export_jobs
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;

-- 首次开始执行时间：任务从 PENDING → RUNNING 的第一次执行时间（未开始则为 NULL）。
ALTER TABLE export_jobs
    ADD COLUMN started_at TIMESTAMP NULL;

-- 最终结束时间：任务最终全部完成（SUCCEEDED/FAILED/EXPIRED）的时间（进行中则为 NULL）。
ALTER TABLE export_jobs
    ADD COLUMN finished_at TIMESTAMP NULL;

-- 过期时间：任务允许被下载的截止时间。过期后文件会被清理、任务标记为 EXPIRED。
ALTER TABLE export_jobs
    ADD COLUMN expired_at TIMESTAMP NULL;

-- 产物文件路径：生成的 Excel 文件在本地/对象存储中的路径；成功导出后回填。
ALTER TABLE export_jobs
    ADD COLUMN file_path VARCHAR(512) NULL;

-- 产物文件大小（字节）：用于下载前校验/展示大小。
ALTER TABLE export_jobs
    ADD COLUMN file_size_bytes BIGINT NULL;

-- 错误码：任务失败时机器可读的错误分类（如 FILE_GENERATION_FAILED），便于统计与按码重试。
ALTER TABLE export_jobs
    ADD COLUMN error_code VARCHAR(64) NULL;

-- 错误信息：任务失败时详细描述（可读文本 / 摘录的异常信息），用于排障。
ALTER TABLE export_jobs
    ADD COLUMN error_message VARCHAR(500) NULL;

-- ======================== 二、export_job_attempts（执行尝试表）======================

-- 本次尝试错误码：单次尝试失败时记录的机器可读错误分类。
ALTER TABLE export_job_attempts
    ADD COLUMN error_code VARCHAR(64) NULL;

-- 本次尝试错误信息：单次尝试失败时的详细描述。
ALTER TABLE export_job_attempts
    ADD COLUMN error_message VARCHAR(500) NULL;

-- 本次尝试产物文件路径：该次尝试生成的 Excel 路径（重试场景下每次尝试可能各自生成文件）。
ALTER TABLE export_job_attempts
    ADD COLUMN file_path VARCHAR(512) NULL;

-- 本次尝试产物文件大小（字节）。
ALTER TABLE export_job_attempts
    ADD COLUMN file_size_bytes BIGINT NULL;

-- ======================== 三、过期扫描索引 =======================

-- 供「过期任务清理」定时任务高效扫描：查询「某状态 + 已到过期时间」的任务。
-- (status, expired_at) 复合索引让 WHERE status=? AND expired_at < NOW() 走索引，避免全表扫描。
CREATE INDEX idx_export_jobs_expiration ON export_jobs (status, expired_at);