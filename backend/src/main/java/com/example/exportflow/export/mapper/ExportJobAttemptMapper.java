package com.example.exportflow.export.mapper;

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

    /** 查询当前 RUNNING 尝试的序号（执行体分配 attempt 临时文件名），无 RUNNING 尝试返回 null。 */
    Integer selectRunningAttemptNo(@Param("jobId") long jobId);
}
