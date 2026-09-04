package com.example.exportflow.export.mapper;

import com.example.exportflow.export.entity.OutboxEventEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事务发件箱数据访问接口（MyBatis 实现，SQL 见 resources/mapper/OutboxEventMapper.xml）。
 */
@Mapper
public interface OutboxEventMapper {

    /** 插入 Outbox 事件（与导出任务同事务写入）。 */
    int insert(OutboxEventEntity event);
}
