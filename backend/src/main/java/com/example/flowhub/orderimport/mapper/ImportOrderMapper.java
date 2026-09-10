package com.example.flowhub.orderimport.mapper;

import com.example.flowhub.orderimport.entity.ImportOrderRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * 导入执行侧订单写入数据访问接口（SQL 见 resources/mapper/ImportOrderMapper.xml）。
 * 冲突预查 + 批量入库；orders.order_no 唯一约束作为最终裁决线。
 */
@Mapper
public interface ImportOrderMapper {

    /** 冲突预查：返回列表中已存在于 orders 的订单号集合（批量，一次 IN 查询）。 */
    Set<String> selectExistingOrderNos(@Param("orderNos") List<String> orderNos);

    /** 批量入库：按 9 列契约写入 orders，order_status 与 status 同值双写。 */
    int insertBatch(@Param("rows") List<ImportOrderRow> rows);
}