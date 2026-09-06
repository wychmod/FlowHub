package com.example.exportflow.export.mapper;

import com.example.exportflow.export.entity.ExportOrderRow;
import com.example.exportflow.export.entity.ExportSelectionSnapshot;
import com.example.exportflow.order.query.OrderCriteria;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 导出执行侧订单读取（MyBatis 实现，SQL 见 resources/mapper/ExportOrderMapper.xml）。
 * <p>
 * 筛选条件跨 Mapper 复用 OrderMapper.criteriaConditions 片段，与创建统计保持 WHERE 同源。
 */
@Mapper
public interface ExportOrderMapper {

    /** 同一筛选条件下一次统计命中行数与最大订单 ID（创建时高水位）。 */
    ExportSelectionSnapshot snapshotByCriteria(
            @Param("criteria")
            OrderCriteria criteria);

    /** Keyset 批查：id ∈ (lastId, maxOrderId] 按快照条件升序取一批。 */
    List<ExportOrderRow> findBatch(
            @Param("lastId") long lastId,
            @Param("maxOrderId") long maxOrderId,
            @Param("batchSize") int batchSize,
            @Param("criteria") OrderCriteria criteria);
}
