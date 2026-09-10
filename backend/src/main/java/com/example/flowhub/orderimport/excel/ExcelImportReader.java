package com.example.flowhub.orderimport.excel;

import com.example.flowhub.orderimport.command.ImportColumn;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * POI XSSF SAX 流式读 Excel（docs/order-import-design.md §5.2/B5 核心新增）。
 * <p>
 * 用 {@link XSSFSheetXMLHandler} 走 event 模型逐单元格回调，不实例化 Workbook/零散行对象，
 * 内存恒定（仅共享字符串表 + 当前一行数组），10 万行可在受理期安全轻扫、执行期安全流式读。
 * 单元格值统一经 DataFormatter 转为文本（空单元格不回调，保持 null）；列位由 cell reference
 * （如 "C4"）推导以对齐 ImportColumn 9 列顺序，即使中间有空列也不错位。
 * <p>
 * 两用：{@link #scanStructure} 同步受理期结构级轻扫（表头 + 行计数），{@link #readRows}
 * 异步执行期按行回调。
 */
@Component
public class ExcelImportReader {

    private static final Logger log = LoggerFactory.getLogger(ExcelImportReader.class);

    private static final int COLUMN_COUNT = ImportColumn.all().size();

    /** 结构级扫描结果：Sheet 名、表头标题（按列序，缺列为 null）、表头实际列数（含超出白名单的列）与数据行数。 */
    public record ScanResult(String sheetName, List<String> headerTitles, int headerColumnCount, int dataRowCount) {
    }

    /** 行级回调：excelRowNumber 为 1 基的 Excel 行号（表头为第 1 行，数据从第 2 行起）；cells 为按列序对齐的 9 元数组。 */
    public interface RowHandler {
        void onRow(int excelRowNumber, String[] cells);
    }

    /**
     * 结构级轻扫：一次过读取结构供受理期校验。
     *
     * @return Sheet 名、表头 9 列标题（按列序，缺列为 null）、表头实际列数（含超出白名单的列）与数据行计数。
     */
    public ScanResult scanStructure(Path file) throws IOException {
        final String[] header = new String[COLUMN_COUNT];
        final String[] sheetName = {null};
        final int[] headerColumnCount = {0};
        final int[] count = {0};
        parse(file, new RowSink() {
            @Override
            public void sheet(String name) {
                sheetName[0] = name;
            }

            @Override
            public void head(String[] headerCells, int columnCount) {
                System.arraycopy(headerCells, 0, header, 0, COLUMN_COUNT);
                headerColumnCount[0] = columnCount;
            }

            @Override
            public void row(int excelRowNumber, String[] cells) {
                count[0]++;
            }
        });
        // Arrays.asList（非 List.of）：缺列时 header 含 null，List.of 会 NPE，缺列须逐列比对出模板不匹配
        return new ScanResult(sheetName[0], Arrays.asList(header), headerColumnCount[0], count[0]);
    }

    /** 流式读数据行：每行为首回调一次（跳过表头），由调用方攒批执行校验与入库。 */
    public void readRows(Path file, RowHandler handler) throws IOException {
        parse(file, new RowSink() {
            @Override
            public void head(String[] headerCells, int columnCount) {
                // 表头已由受理期校验；此处无需处理
            }

            @Override
            public void row(int excelRowNumber, String[] cells) {
                handler.onRow(excelRowNumber, cells);
            }
        });
    }

    /** 通用解析：读第一个 Sheet，先回调表头再逐数据行回调（跳过整行空白的行）。 */
    private void parse(Path file, RowSink sink) throws IOException {
        try (OPCPackage pkg = OPCPackage.open(file.toFile())) {
            XSSFReader reader = new XSSFReader(pkg);
            // event 模型读共享字符串表（不经 XSSFReader.getSharedStringsTable 的完整模型，避免加载全部内联/富文本）
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            CollectingHandler collecting = new CollectingHandler(sink);
            XSSFSheetXMLHandler xssfHandler = new XSSFSheetXMLHandler(
                    styles, sharedStrings,
                    collecting, new DataFormatter(), false);
            XMLReader parser = XMLHelper.newXMLReader();
            parser.setContentHandler(xssfHandler);
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            if (sheets.hasNext()) {
                // getSheetName 依赖 next() 推进的当前 sheet 引用，须在 next() 之后取
                try (InputStream sheetData = sheets.next()) {
                    sink.sheet(sheets.getSheetName());
                    parser.parse(new InputSource(sheetData));
                }
            }
        } catch (SAXException | OpenXML4JException | ParserConfigurationException ex) {
            // 单元格引用/共享字符串引用跨 Sheet 时可能断言失败：归并为不可读错误由受理层映射
            throw new IOException("Excel SAX 解析失败: " + file, ex);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // POI 对空共享字符串等非法结构抛运行时异常：同样视为文件损坏
            log.warn("import_excel_parse_invalid file={} reason={}", file, ex.toString());
            throw new IOException("Excel 结构非法: " + file, ex);
        }
    }

    /** 数据行回调接口（内部）：sheet 收数据 Sheet 名、head 收表头与实际列数、row 收数据行。 */
    interface RowSink {
        default void sheet(String sheetName) {
        }

        void head(String[] headerCells, int columnCount);

        void row(int excelRowNumber, String[] cells);
    }

    /**
     * POI SheetContentsHandler 适配：把流式单元格回调组装成按列对齐的行数组。
     * 空单元格不触发回调（保持 null），列位由 cell reference 推导保证与 ImportColumn 顺序对齐。
     */
    private static final class CollectingHandler implements SheetContentsHandler {

        private final RowSink sink;
        private String[] rowBuf;
        private boolean rowHasContent;
        private boolean headerSent;
        /** 当前行出现过的最大列（1 基计数，含超出白名单的列），表头行用它判定实际列数。 */
        private int widestColumn;

        CollectingHandler(RowSink sink) {
            this.sink = sink;
        }

        @Override
        public void startRow(int rowIndex) {
            rowBuf = new String[COLUMN_COUNT];
            rowHasContent = false;
            widestColumn = 0;
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (cellReference == null) {
                return;
            }
            int col = columnIndexOf(cellReference);
            if (col < 0) {
                return;
            }
            // 先累计列宽再过滤白名单外列：表头行多出的第 10 列也要计入实际列数供严格校验
            widestColumn = Math.max(widestColumn, col + 1);
            if (col >= COLUMN_COUNT || formattedValue == null || formattedValue.isBlank()) {
                return;
            }
            rowBuf[col] = formattedValue;
            rowHasContent = true;
        }

        @Override
        public void endRow(int rowIndex) {
            if (!headerSent) {
                headerSent = true;
                sink.head(rowBuf, widestColumn);
            } else if (rowHasContent) {
                // 第 0 行为表头；数据行的 Excel 行号 = 0 基行号 + 1
                sink.row(rowIndex + 1, rowBuf);
            }
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // 页眉页脚不参与数据解析
        }

        /** 由单元格引用（如 "C4"）推导列索引：字母部分转 0 基列号，不关心行号。 */
        private static int columnIndexOf(String cellReference) {
            int col = 0;
            for (int i = 0; i < cellReference.length(); i++) {
                char c = cellReference.charAt(i);
                if (!Character.isLetter(c)) {
                    break;
                }
                col = col * 26 + (c - 'A' + 1);
            }
            return col - 1;
        }
    }
}