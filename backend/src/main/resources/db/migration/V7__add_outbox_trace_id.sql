-- V7__add_outbox_trace_id.sql
-- Outbox 链路追踪：为 outbox_events 表增加 trace_id 列，并为其建立索引。
-- 依据参考项目的 V7__add_outbox_trace_id.sql 完整迁移而来，并补充详细说明。
--
-- 背景：当前项目已有 trace_id 基础设施（TraceIdFilter/MdcScope/TraceIdSupport，见 common/web/trace）。
-- 业务写库时将链路 trace_id 写入 MDC；本版本在发送给 MQ 的每条 outbox 事件里也带上该 trace_id，
-- 使「创建导出任务 → 持久化 → 发消息 → 消费者处理」整条异步链路可用同一 trace_id 串起来，便于全链路排障审计。

-- trace_id：与写入 outbox 事件的业务线程同一链路（线程）上生成的 trace_id。
-- 消费者拉取到事件后取用该 trace_id 继续传递，从而在日志/监控系统中把一次导出的多个环节关联为一条完整链路。
-- AFTER payload 仅指定列在表结构中的展示位置，属 MySQL 语义（不影响功能）。
ALTER TABLE outbox_events
    ADD COLUMN trace_id VARCHAR(64) NULL AFTER payload;

-- 索引：供按 trace_id 检索某条链路上产生的所有 outbox 事件（排查某次调用的消息发送/消费情况）。
CREATE INDEX idx_outbox_trace_id ON outbox_events (trace_id);