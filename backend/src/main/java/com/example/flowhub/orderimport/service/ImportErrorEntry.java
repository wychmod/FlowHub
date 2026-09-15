package com.example.flowhub.orderimport.service;

/**
 * 错误报告条目：一行跳过的业务错误。
 * <p>
 * excelRowNo 为 1 基 Excel 行号（表头第 1 行，数据从第 2 行起），与用户打开文件看到的行号对齐；
 * column/reason 多列错误已按「列名；列名」与「原因；原因」聚合为单条。
 */
public record ImportErrorEntry(int excelRowNo, String orderNo, String column, String reason) {
}