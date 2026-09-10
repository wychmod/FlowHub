package com.example.exportflow.orderimport.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 订单导入执行尝试数据访问接口（SQL 见 resources/mapper/ImportJobAttemptMapper.xml）。
 * 尝试无 per-attempt 产物（上传原件为全任务共享），收敛只写状态与错误证据。
 */
@Mapper
public interface ImportJobAttemptMapper {

    /** 插入 RUNNING 尝试（与抢占同一事务提交，attempt_no 取 MAX+1 原子生成）。 */
    int insertRunning(@Param("jobId") long jobId, @Param("now") LocalDateTime now);

    /** 收敛当前 RUNNING 尝试为 FAILED（按 job_id + RUNNING 定位，至多一条）。 */
    int markFailed(@Param("jobId") long jobId,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") LocalDateTime now);

    /** 尝试全成功收敛：RUNNING → SUCCEEDED（无产物列，仅回填结束时间）。 */
    int markSucceeded(@Param("jobId") long jobId, @Param("now") LocalDateTime now);

    /** 尝试部分成功收敛：RUNNING → PARTIAL，登记错误摘要证据（供审计查看）。 */
    int markPartial(@Param("jobId") long jobId,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage,
                    @Param("now") LocalDateTime now);

    /** 启动/维护恢复：与 Job 恢复同一「租约失效」前置条件，Attempt 先行收敛。 */
    int recoverExpiredRunning(@Param("now") LocalDateTime now,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage);

    /** 活跃租约判断（孤儿对账）：尝试 RUNNING 且 Job RUNNING 且租约未到期。 */
    int countRunningWithValidLease(@Param("jobId") long jobId,
                                   @Param("attemptNo") int attemptNo,
                                   @Param("now") LocalDateTime now);

    /** 文件引用计数（孤儿对账）：import_jobs 的 file_path/error_report_path 被引用即保留。 */
    int countByFilePath(@Param("filePath") String filePath);

    /** 当前 RUNNING 尝试序号（claim 同事务插入后供执行体查询，无则返回 null）。 */
    Integer selectRunningAttemptNo(@Param("jobId") long jobId);
}