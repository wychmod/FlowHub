package com.example.flowhub.export.excel;

import com.example.flowhub.export.entity.ExportOrderRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Writer 契约单测：用真实 XSSF 重新打开输出文件断言——表头随所选列、
 * 公式前缀转义为文本、金额为可计算数值、冻结/筛选生效、未知列在创建文件前拒绝。
 * <p>「文件能重新打开且内容正确」是文件系统的最终验收，优于断言代码执行到最后一行。
 */
class ExcelExportWriterTest {

    private final ExcelExportWriter writer = new ExcelExportWriter();

    @TempDir
    Path tempDir;

    // ==================== 表头与行内容 ====================

    @Test
    void writesHeaderAndRowsInRequestedOrder() throws IOException {
        Path target = tempDir.resolve("ordered.xlsx");
        try (ExcelExportWriter.WorkbookSession session = writer.open(target,
                List.of("total_amount", "order_no", "created_at"))) {
            session.writeBatch(List.of(
                    row(1, "ORD-001", "100.50"),
                    row(2, "ORD-002", "9.99")));
        }

        try (XSSFWorkbook reopened = new XSSFWorkbook(Files.newInputStream(target))) {
            XSSFSheet sheet = reopened.getSheet("订单数据");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("订单金额");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("订单号");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("下单时间");

            // 金额是可计算数值（NUMERIC + 0.00 格式），不是带格式的字符串
            Cell amount = sheet.getRow(1).getCell(0);
            assertThat(amount.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(amount.getNumericCellValue()).isEqualTo(100.50);
            assertThat(amount.getCellStyle().getDataFormatString()).isEqualTo("0.00");

            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("ORD-001");
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("2026-01-01 10:00:00");
        }
    }

    @Test
    void escapesFormulaPrefixAsPlainText() throws IOException {
        Path target = tempDir.resolve("formula.xlsx");
        try (ExcelExportWriter.WorkbookSession session = writer.open(target, List.of("order_no", "customer_name"))) {
            ExportOrderRow tricky = new ExportOrderRow(1L, "=2+3", "PAID", "WEB", "+Customer", "13800000000",
                    new BigDecimal("1.00"), "CNY", "浙江省", LocalDateTime.of(2026, 1, 1, 10, 0, 0));
            session.writeBatch(List.of(tricky));
        }

        try (XSSFWorkbook reopened = new XSSFWorkbook(Files.newInputStream(target))) {
            XSSFSheet sheet = reopened.getSheet("订单数据");
            // 前导单引号让 Excel 按文本显示，不把内容当公式执行
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("'=2+3");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("'+Customer");
        }
    }

    @Test
    void nullTextAndAmountBecomeBlankCells() throws IOException {
        Path target = tempDir.resolve("blank.xlsx");
        try (ExcelExportWriter.WorkbookSession session = writer.open(target, List.of("order_no", "total_amount"))) {
            session.writeBatch(List.of(row(1, "ORD-001", null)));
        }

        try (XSSFWorkbook reopened = new XSSFWorkbook(Files.newInputStream(target))) {
            XSSFSheet sheet = reopened.getSheet("订单数据");
            assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.BLANK);
        }
    }

    // ==================== 文件结构设置（Session 级一次成型） ====================

    @Test
    void freezesHeaderAndEnablesAutoFilter() throws IOException {
        Path target = tempDir.resolve("pane.xlsx");
        try (ExcelExportWriter.WorkbookSession session = writer.open(target, List.of("order_no"))) {
            session.writeBatch(List.of(row(1, "ORD-001", "1.00")));
        }

        try (XSSFWorkbook reopened = new XSSFWorkbook(Files.newInputStream(target))) {
            XSSFSheet sheet = reopened.getSheet("订单数据");
            assertThat(sheet.getPaneInformation()).isNotNull();
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getCTWorksheet().getAutoFilter()).isNotNull();
        }
    }

    @Test
    void emptyRequestedColumnsFallsBackToAllNine() throws IOException {
        Path target = tempDir.resolve("fallback.xlsx");
        try (ExcelExportWriter.WorkbookSession session = writer.open(target, null)) {
            session.writeBatch(List.of(row(1, "ORD-001", "1.00")));
        }

        try (XSSFWorkbook reopened = new XSSFWorkbook(Files.newInputStream(target))) {
            assertThat(reopened.getSheet("订单数据").getRow(0).getLastCellNum()).isEqualTo((short) 9);
        }
    }

    // ==================== 白名单第二道防线 ====================

    @Test
    void rejectsUnknownColumnBeforeCreatingFile() {
        Path target = tempDir.resolve("rejected.xlsx");

        assertThatThrownBy(() -> writer.open(target, List.of("order_no", "password")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");

        // 校验失败发生在创建任何文件之前：不产生半成品
        assertThat(Files.exists(target)).isFalse();
    }

    // ==================== 数据构造 ====================

    private static ExportOrderRow row(long id, String orderNo, String amount) {
        return new ExportOrderRow(id, orderNo, "PAID", "WEB", "张伟", "+8613800000000",
                amount == null ? null : new BigDecimal(amount), "CNY", "浙江省",
                LocalDateTime.of(2026, 1, 1, 10, 0, 0));
    }
}
