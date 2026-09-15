-- V2__add_export_job_idempotency.sql
-- 导出任务幂等改造：为 export_jobs 表新增幂等键、请求指纹、筛选命中数 3 列，并对幂等键加唯一约束。
-- 依据参考项目的 V2__add_export_job_idempotency.sql 完整迁移而来，并补充详细说明。
--
-- 设计目的：当用户对同一批订单重复触发「导出」时，服务端需能识别并返回既有的任务，
-- 而不是新建一个重复任务。这三列共同支撑幂等创建。

-- 1. 幂等键：由「用户 + 导出参数」稳定计算出的唯一键，作为幂等判重的依据。
--    典型取值为「导出模式 + 筛选条件/勾选订单集合 + 用户标识」拼出的字符串；
--    客户端重试/重复请求时携带同一 key，服务端按它命中已存在的任务而返回原任务，杜绝重复任务。
ALTER TABLE export_jobs
    ADD COLUMN idempotency_key VARCHAR(128) NOT NULL;

-- 2. 请求指纹：对「导出请求内容」整体做摘要（如 SHA-256 后的 64 位十六进制串）。
--    与 idempotency_key 的区别：key 用于判重，hash 用于比对「两次请求内容是否完全一致」，
--    防止客户端复用同一 key 但携带了不同导出参数（避免返回错误的已存在任务）。
ALTER TABLE export_jobs
    ADD COLUMN request_hash CHAR(64) NOT NULL;

-- 3. 筛选命中数：本次导出筛选条件预估命中的订单行数。
--    用于在任务创建阶段（尚未真正生成 Excel）先给出预估规模，便于进度条/前方校验，也用于日志与排障。
ALTER TABLE export_jobs
    ADD COLUMN filter_count BIGINT NOT NULL;

-- 4. 幂等键唯一约束：在数据库层面强制同一幂等键只能存在一条任务。
--    即便并发线程同时发起同 key 的创建请求，也只有一个能成功插入，另一条会触发唯一冲突而被捕获并降级为「返回既有任务」，
--    从而让「幂等」在多实例/并发场景下仍成立（这是内存判重无法保证的最终防线）。
ALTER TABLE export_jobs
    ADD CONSTRAINT uk_export_jobs_idempotency_key UNIQUE (idempotency_key);