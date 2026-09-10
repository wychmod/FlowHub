package com.example.flowhub.export.mapper;

import com.example.flowhub.export.entity.OutboxEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事务发件箱数据访问接口（MyBatis 实现，SQL 见 resources/mapper/OutboxEventMapper.xml）。
 */
@Mapper
public interface OutboxEventMapper {

    /** 插入 Outbox 事件（与导出任务同事务写入）。 */
    int insert(OutboxEventEntity event);

    /** 捞取未发布事件（按 created_at,id 升序，limit 限单轮条数）。 */
    List<OutboxEventEntity> findUnpublished(@Param("aggregateType") String aggregateType,
                                            @Param("eventType") String eventType,
                                            @Param("limit") int limit);

    /** 标记已发布（AND published_at IS NULL 单向打勾，防并发重复回填），返回影响行数。 */
    int markPublished(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt);
}
