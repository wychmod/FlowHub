-- V3__persist_export_job_request_snapshot.sql
-- 导出任务请求快照持久化：为 export_jobs 表新增 4 列，把「创建任务时的导出请求参数」整体落库。
-- 依据参考项目 project-export-flow 的 V3__persist_export_job_request_snapshot.sql 完整迁移而来，并补充详细说明。
--
-- 设计目的：异步任务真正执行（生成 Excel）时，原始请求往往已不再是当前状态——
--   例如用户勾选后订单数据又变化、筛选条件条目变更、请求对象已不在内存。
-- 因此要在「创建时刻」就把请求参数固化成快照存入本表，执行器按快照重放，保证结果与用户提交时一致。

-- 1. 筛选条件快照：本次导出采用的筛选条件（如订单状态、销售渠道、金额区间等）的序列化形式（通常是 JSON）。
--    保存后，即使前端已关闭、后续筛选默认值变化，执行器仍按该快照确定「导哪些订单」，保证一致性。
ALTER TABLE export_jobs
    ADD COLUMN filter_snapshot LONGTEXT NOT NULL;

-- 2. 勾选订单 ID 集合：用户「勾选导出」时选中的一批订单 ID，序列化后存为 JSON 文本。
--    快照落库后，执行器据此精确定位本次要导出的订单行，且不受后续订单增删影响；
--    与 filter_snapshot 二选一或结合使用（勾选=明确指定，筛选=按条件查询）。
ALTER TABLE export_jobs
    ADD COLUMN selected_order_ids LONGTEXT NOT NULL;

-- 3. 导出列清单：本次导出包含的列（顺序）快照，序列化后存为 JSON 文本。
--    决定生成 Excel 的列头与顺序；快照化保证「表结构后续调整」不会改变已提交任务的列输出。
ALTER TABLE export_jobs
    ADD COLUMN selected_columns LONGTEXT NOT NULL;

-- 4. 用户请求的文件名：导出时用户期望的文件名（不含扩展名，或含扩展名均可，按实现约定）。
--    持久化后即可在任务列表/结果下载时原样回显该文件名，而不依赖前端再次传入。
ALTER TABLE export_jobs
    ADD COLUMN requested_file_name VARCHAR(255) NOT NULL;