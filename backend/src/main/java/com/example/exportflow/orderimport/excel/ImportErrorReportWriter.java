package com.example.exportflow.orderimport.excel;

import com.example.exportflow.orderimport.service.ImportErrorEntry;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 错误报告 xlsx 生成（docs/order-import-design.md §5.4/B11）：行号 / 订单号 / 错误列 / 错误原因。
 * <p>
 * 用 SXSSF 流式写（错误条目最多 5000 行，内存恒定）；错误报告是任务产物，直接写入受控
 * importRoot 最终位置，登记由 markPartial 负责、登记失败由调用方补偿删除。
 */
@Component
public class ImportErrorReportWriter {

    private static final String SHEET_NAME = "导入错误";
    private static final String[] HEADERS = {"行号（Excel）", "订单号", "错误列", "错误原因"};
    private static final int ROW_WINDOW = 100;

    /**
     * 渲染错误报告到目标路径。
     *
     * @param target       受控绝对路径
     * @param rows         有界缓冲的错误条目（最多 error-report-max-rows 条）
     * @param totalSkipped 实际跳过总行数（超缓冲截断时用于提示）
     * @param truncated    是否因超上限截断
     * @return 成功写出（文件已完整落盘）
     */
    public boolean write(Path target, List<ImportErrorEntry> rows, int totalSkipped, boolean truncated) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target);
             SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW)) {
            workbook.setCompressTempFiles(true);
            SXSSFSheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle headerStyle = headerStyle(workbook);
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 26 * 256);
            }
            sheet.createFreezePane(0, 1);

            int rowIndex = 1;
            if (truncated && totalSkipped > rows.size()) {
                Row note = sheet.createRow(rowIndex++);
                note.createCell(0).setCellValue("错误总行数 " + totalSkipped + "，本报告仅展示前 " + rows.size() + " 条");
            }
            for (ImportErrorEntry entry : rows) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(entry.excelRowNo());
                row.createCell(1).setCellValue(entry.orderNo() == null ? "" : entry.orderNo());
                row.createCell(2).setCellValue(entry.column());
                row.createCell(3).setCellValue(entry.reason());
            }
            return true;
        }
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}