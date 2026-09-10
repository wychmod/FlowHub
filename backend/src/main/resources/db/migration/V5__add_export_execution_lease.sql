-- V5__add_export_execution_lease.sql
-- 导出任务执行租约（Lease）/ 心跳（Heartbeat）改造：为 export_jobs 增加租约与心跳字段，
-- 用于崩溃恢复——当执行 worker 宕机时，租约过期后其他 worker 可安全地接管该任务。
-- 依据参考项目的 V5__add_export_execution_lease.sql 完整迁移而来，并补充详细说明。
--
-- 背景：一个导出任务可能由多个 worker 实例竞争执行。如果没有「租约」，两个 worker 可能同时
-- 执行同一任务（重复导出、资源浪费）。租约机制保证：同一时刻只有持约者能执行，持约者死掉后，
-- 租约自动过期，别的 worker 才能接管。

-- 最近一次心跳时间：执行中的 worker 周期性地「续租」，每续一次就更新 last_heartbeat_at。
-- 它是判活依据——只要能持续更心跳，说明 worker 还活着。（未开始执行或已结束则可为 NULL。）
ALTER TABLE export_jobs
    ADD COLUMN last_heartbeat_at TIMESTAMP NULL;

-- 租约到期时间：当前持有者可以独占执行到的时间点（通常 = last_heartbeat_at + 租约时长）。
-- 若此刻超过 NOW()，说明 lease 仍在有效期内（worker 存活、可继续执行）；
-- 若 NOW() 已超过 lease_expires_at，说明持有者已失联（崩溃/网络分区），该任务可被重新认领。
ALTER TABLE export_jobs
    ADD COLUMN lease_expires_at TIMESTAMP NULL;

-- 运行中租约索引：供「租约恢复/认领」定时任务高效查询「处于运行中、但租约已过期」的任务，
-- 形如 WHERE status = 'RUNNING' AND lease_expires_at < NOW()。
-- 命中这些记录的任务会被回收并交给其他 worker 重跑，实现崩溃恢复。
CREATE INDEX idx_export_jobs_running_lease ON export_jobs (status, lease_expires_at);