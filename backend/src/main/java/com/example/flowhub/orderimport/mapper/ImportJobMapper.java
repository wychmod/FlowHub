package com.example.flowhub.orderimport.mapper;

import com.example.flowhub.orderimport.entity.ImportJobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单导入任务数据访问接口（MyBatis 实现，SQL 见 resources/mapper/ImportJobMapper.xml）。
 */
@Mapper
public interface ImportJobMapper {

    /** 插入导入任务（PENDING 初始态，job_no 唯一约束兜底并发创建）。 */
    int insert(ImportJobEntity job);

    /** 按主键查询任务（执行器加载快照），未命中返回 null。 */
    ImportJobEntity selectById(@Param("jobId") long jobId);

    /** 按任务编号查询（record 无 setter，自增 id 经唯一 job_no 取回），供创建后写 Outbox/组装响应。 */
    ImportJobEntity selectByJobNo(@Param("jobNo") String jobNo);

    /**
     * 进度推进（条件守卫：仅 RUNNING 且 processed_rows 不回退，同批续期 heartbeat/lease，
     * 成功/跳过计数取调用方累计值单调落库）。
     *
     * @return 1 = 推进成功；0 = 任务非 RUNNING 或进度回退（调用方须 fail-fast）
     */
    int updateProgress(
            @Param("jobId") long jobId,
            @Param("processedRows") int processedRows,
            @Param("succeededRows") int succeededRows,
            @Param("skippedRows") int skippedRows,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    /** 分页查询任务列表（status 非空时按状态过滤），按创建时间倒序 + id 倒序。 */
    List<ImportJobEntity> findPage(@Param("limit") int limit, @Param("offset") int offset,
                                   @Param("status") String status);

    /** 导入任务总数（列表分页 total，status 非空时按状态过滤）。 */
    long countAll(@Param("status") String status);

    /**
     * 条件抢占：仅 PENDING 且未达尝试上限的任务可被置为 RUNNING（CAS 裁决执行权）。
     * 同时清空上一轮遗留的错误报告引用与计数，保证重试后从零推进。
     *
     * @return 1 = 抢占成功；0 = 重复投递/已被抢占/达上限/不存在
     */
    int claimPending(@Param("jobId") long jobId,
                     @Param("maxAttempts") int maxAttempts,
                     @Param("now") LocalDateTime now,
                     @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    /** 收敛失败：RUNNING → FAILED 回填错误与结束时间（status 条件防重复收敛）。 */
    int markFailed(@Param("jobId") long jobId,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") LocalDateTime now);

    /** 全成功收敛：RUNNING → SUCCEEDED（无错误报告，成功计数已随进度落库），
     *  保留期从完成起算供过期清理（expiredAt = now + retentionHours）。 */
    int markSucceeded(@Param("jobId") long jobId, @Param("now") LocalDateTime now,
                      @Param("expiredAt") LocalDateTime expiredAt);

    /** 部分成功收敛：RUNNING → PARTIAL，登记错误报告路径、错误摘要与保留期截止时间。 */
    int markPartial(@Param("jobId") long jobId,
                    @Param("errorReportPath") String errorReportPath,
                    @Param("errorSummary") String errorSummary,
                    @Param("now") LocalDateTime now,
                    @Param("expiredAt") LocalDateTime expiredAt);

    /** 人工重试：FAILED → PENDING 清空执行反馈列（保留上传原件与 total_rows），并清空错误报告引用。 */
    int retry(@Param("jobId") long jobId,
              @Param("maxAttempts") int maxAttempts,
              @Param("now") LocalDateTime now);

    /** 启动/维护恢复：仅收敛「租约已失效（含 NULL）」的 RUNNING 为 FAILED。 */
    int recoverExpiredRunning(@Param("now") LocalDateTime now,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage);

    /** 到期扫描（清理）：SUCCEEDED/PARTIAL 且已过保留期，按到期时间升序限量返回。 */
    List<ImportJobEntity> findExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 过期收敛：文件删除成功后才允许执行（删除即事实）。 */
    int markExpired(@Param("jobId") long jobId, @Param("now") LocalDateTime now);

    /** 文件/错误报告引用计数（孤儿对账）：被任何 Job 登记即保留。 */
    int countByFilePath(@Param("filePath") String filePath);
}