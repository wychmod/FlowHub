package com.example.exportflow.export.mapper;

import com.example.exportflow.export.entity.ExportJobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 导出任务数据访问接口（MyBatis 实现，SQL 见 resources/mapper/ExportJobMapper.xml）。
 */
@Mapper
public interface ExportJobMapper {

    /** 插入导出任务（幂等键唯一约束兜底并发创建）。 */
    int insert(ExportJobEntity job);

    /** 按主键查询任务（执行器加载快照），未命中返回 null。 */
    ExportJobEntity selectById(
            @Param("jobId")
            long jobId);

    /** 每批推进已处理行数（version 递增使进度变更对状态校准可见）。 */
    int updateProcessedRows(
            @Param("jobId") long jobId,
            @Param("processedRows") long processedRows,
            @Param("now") LocalDateTime now);

    /** 按幂等键查询任务（幂等命中复用），未命中返回 null。 */
    ExportJobEntity selectByIdempotencyKey(
            @Param("idempotencyKey")
            String idempotencyKey);

    /**
     * 条件抢占：仅 PENDING 且未达尝试上限的任务可被置为 RUNNING（CAS 裁决执行权）。
     *
     * @param maxAttempts 尝试上限（含本次），达上限后不再可抢占
     * @param leaseExpiresAt 租约到期时间（抢占成功后的失联恢复信号，第 19 章消费）
     * @return 1 = 抢占成功获得执行权；0 = 重复投递/已被抢占/达上限/不存在
     */
    int claimPending(@Param("jobId") long jobId,
                     @Param("maxAttempts") int maxAttempts,
                     @Param("now") LocalDateTime now,
                     @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    /**
     * 收敛失败：RUNNING → FAILED 回填错误与结束时间（status 条件防重复收敛）。
     *
     * @return 1 = 收敛成功；0 = 任务已不处于 RUNNING（已被其他路径推进）
     */
    int markFailed(@Param("jobId") long jobId,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") LocalDateTime now);
}
