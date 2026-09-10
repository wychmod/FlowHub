package com.example.flowhub.export.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 导出任务执行尝试数据访问接口（MyBatis 实现，SQL 见 resources/mapper/ExportJobAttemptMapper.xml）。
 * <p>
 * Attempt 只在抢占成功后创建：重复投递抢占失败不产生记录（否则虚增执行历史）。
 */
@Mapper
public interface ExportJobAttemptMapper {

    /**
     * 插入 RUNNING 尝试记录（attempt_no = MAX+1，叠加唯一索引 uk_attempt_job_no 库层防重复）。
     *
     * @return 恒为 1（聚合子查询无分组恒返回一行）
     */
    int insertRunning(@Param("jobId") long jobId, @Param("now") LocalDateTime now);

    /**
     * 收敛当前 RUNNING 尝试为 FAILED（按 job_id + RUNNING 定位，同一时刻至多一条）。
     *
     * @return 1 = 收敛成功；0 = 无 RUNNING 尝试（已被其他路径推进）
     */
    int markFailed(@Param("jobId") long jobId,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") LocalDateTime now);

    /** 收敛当前 RUNNING 尝试为 SUCCEEDED 并登记该次产物（审计记录，每次尝试各自留存路径与大小）。 */
    int markSucceeded(@Param("jobId") long jobId,
                      @Param("filePath") String filePath,
                      @Param("fileSizeBytes") long fileSizeBytes,
                      @Param("now") LocalDateTime now);

    /**
     * 启动/维护恢复：仅收敛「其 Job 已租约失效」的 RUNNING 尝试（与 Job 同一前置条件，
     * Attempt 先行保证不误伤仍有有效租约的执行）。
     *
     * @return 本次收敛的尝试数
     */
    int recoverExpiredRunning(@Param("now") LocalDateTime now,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage);

    /** 活跃租约判断（孤儿对账）：该次尝试仍 RUNNING 且其 Job 租约有效 → 文件可能正在被使用。 */
    int countRunningWithValidLease(@Param("jobId") long jobId,
                                   @Param("attemptNo") int attemptNo,
                                   @Param("now") LocalDateTime now);

    /** 正式文件引用计数（孤儿对账）：失败尝试的文件证据被引用即保留。 */
    int countByFilePath(@Param("filePath") String filePath);

    /** 查询当前 RUNNING 尝试的序号（执行体分配 attempt 临时文件名），无 RUNNING 尝试返回 null。 */
    Integer selectRunningAttemptNo(@Param("jobId") long jobId);
}
