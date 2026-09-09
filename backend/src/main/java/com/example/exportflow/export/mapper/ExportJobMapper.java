package com.example.exportflow.export.mapper;

import com.example.exportflow.export.entity.ExportJobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 进度推进（条件守卫：仅 RUNNING 且不回退可推进，同批续期 heartbeat/lease）。
     *
     * @return 1 = 推进成功；0 = 任务非 RUNNING 或进度回退（调用方须 fail-fast）
     */
    int updateProgress(
            @Param("jobId") long jobId,
            @Param("processedRows") long processedRows,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    /** 按幂等键查询任务（幂等命中复用），未命中返回 null。 */
    ExportJobEntity selectByIdempotencyKey(
            @Param("idempotencyKey")
            String idempotencyKey);

    /**
     * 条件抢占：仅 PENDING 且未达尝试上限的任务可被置为 RUNNING（CAS 裁决执行权）。
     *
     * @param maxAttempts 尝试上限（含本次），达上限后不再可抢占
     * @param leaseExpiresAt 租约到期时间（抢占成功后的失联恢复信号，维护服务消费）
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

    /**
     * 成功收敛：RUNNING → SUCCEEDED 登记产物相对路径/大小、完成时间与下载截止时间（发布协议第 3 步）。
     *
     * @param expiredAt 下载保留期截止时间（清理任务消费）
     * @return 1 = 收敛成功；0 = 任务已不处于 RUNNING（调用方须抛异常回滚并补偿删除已发布文件）
     */
    int markSucceeded(@Param("jobId") long jobId,
                      @Param("filePath") String filePath,
                      @Param("fileSizeBytes") long fileSizeBytes,
                      @Param("now") LocalDateTime now,
                      @Param("expiredAt") LocalDateTime expiredAt);

    /**
     * 人工重试：FAILED → PENDING 并清空聚合视图（进度/时间/文件/错误/心跳/租约），
     * Attempt 历史保留；attempt_count 在抢占时递增，不是重试点击次数。
     *
     * @return 1 = 重置成功；0 = 非 FAILED 或已达尝试上限（调用方转业务错误）
     */
    int retry(@Param("jobId") long jobId,
              @Param("maxAttempts") int maxAttempts,
              @Param("now") LocalDateTime now);

    /**
     * 启动/维护恢复：仅收敛「租约已失效（含未写租约）」的 RUNNING 为 FAILED，
     * 写入稳定错误码保留中断事实，清空租约供重试后重新抢占。
     *
     * @return 本次收敛的任务数
     */
    int recoverExpiredRunning(@Param("now") LocalDateTime now,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage);

    /** 到期扫描（清理）：SUCCEEDED 且已过保留期，按到期时间升序限量返回。 */
    List<ExportJobEntity> findExpiredSuccess(@Param("now") LocalDateTime now,
                                             @Param("limit") int limit);

    /**
     * 过期收敛：物理文件删除成功后才允许执行（删除即事实）。
     *
     * @return 1 = 收敛成功；0 = 任务已不处于 SUCCEEDED（已被其他流程推进，不发事件）
     */
    int markExpired(@Param("jobId") long jobId, @Param("now") LocalDateTime now);

    /** 正式文件引用计数（孤儿对账）：被任何 Job 登记即保留。 */
    int countByFilePath(@Param("filePath") String filePath);
}
