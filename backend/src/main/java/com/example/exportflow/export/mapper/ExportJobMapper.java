package com.example.exportflow.export.mapper;

import com.example.exportflow.export.entity.ExportJobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 导出任务数据访问接口（MyBatis 实现，SQL 见 resources/mapper/ExportJobMapper.xml）。
 */
@Mapper
public interface ExportJobMapper {

    /** 插入导出任务（幂等键唯一约束兜底并发创建）。 */
    int insert(ExportJobEntity job);

    /** 按幂等键查询任务（幂等命中复用），未命中返回 null。 */
    ExportJobEntity selectByIdempotencyKey(
            @Param("idempotencyKey")
            String idempotencyKey);
}
