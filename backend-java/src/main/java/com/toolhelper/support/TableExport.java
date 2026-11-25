package com.toolhelper.support;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** 文本表格 OOXML 生成；调用方负责固定列定义和输入配额。 */
public final class TableExport {
    private TableExport() {}
    public static byte[] generate(String title, String format, List<List<String>> rows, int[] widths) {
        if (rows.size() < 2) throw new ApiException(409, "NO_EXPORT_DATA", "当前表格没有可导出数据");
        if (format == null || !Set.of("xlsx", "docx").contains(format)) throw new ApiException(400, "INVALID_EXPORT_FORMAT", "只支持.xlsx和.docx");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                boolean excel = format.equals("xlsx");
                String main = excel ? "xl/workbook.xml" : "word/document.xml";
                entry(zip, "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"" + main + "\"/></Relationships>");
                entry(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + (excel ? "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                        : "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>") + "</Types>");
                if (excel) xlsx(zip, title, rows); else docx(zip, title, rows, widths);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException("内存导出失败"); }
    }
    private static void xlsx(ZipOutputStream zip, String title, List<List<String>> rows) throws IOException {
        entry(zip, "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"" + escape(title) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        entry(zip, "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
        StringBuilder xml = new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"" + rows.getFirst().size() + "\" width=\"30\" customWidth=\"1\"/></cols><sheetData>");
        int index = 0;
        for (List<String> row : rows) {
            xml.append("<row r=\"").append(++index).append("\">");
            for (int col = 0; col < row.size(); col++) {
                // inlineStr始终是文本，包括长数字ID、=开头主题，不能变成公式或丢失精度。
                xml.append("<c r=\"").append((char) ('A' + col)).append(index).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(row.get(col))).append("</t></is></c>");
            }
            xml.append("</row>");
        }
        xml.append("</sheetData><autoFilter ref=\"A1:").append((char) ('A' + rows.getFirst().size() - 1)).append(index).append("\"/></worksheet>");
        entry(zip, "xl/worksheets/sheet1.xml", xml.toString());
    }
    private static void docx(ZipOutputStream zip, String title, List<List<String>> rows, int[] widths) throws IOException {
        int columns = rows.getFirst().size();
        if (widths.length != columns) throw new IllegalArgumentException("导出列宽数量不一致");
        StringBuilder xml = new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:rPr><w:b/><w:sz w:val=\"30\"/></w:rPr><w:t>" + escape(title) + "</w:t></w:r></w:p><w:tbl><w:tblPr><w:tblW w:w=\"15398\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/><w:tblBorders>");
        for (String edge : List.of("top", "left", "bottom", "right", "insideH", "insideV")) xml.append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"4\" w:color=\"D1D5DB\"/>");
        xml.append("</w:tblBorders></w:tblPr><w:tblGrid>");
        for (int width : widths) xml.append("<w:gridCol w:w=\"").append(width).append("\"/>");
        xml.append("</w:tblGrid>");
        boolean header = true;
        for (List<String> row : rows) {
            xml.append("<w:tr>"); if (header) xml.append("<w:trPr><w:tblHeader/></w:trPr>");
            for (int col = 0; col < row.size(); col++) {
                String text = row.get(col);
                xml.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[col]).append("\" w:type=\"dxa\"/><w:vAlign w:val=\"center\"/>");
                if (header) xml.append("<w:shd w:fill=\"E5E7EB\"/>");
                xml.append("</w:tcPr><w:p><w:pPr><w:spacing w:after=\"80\"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:eastAsia=\"Microsoft YaHei\"/><w:sz w:val=\"20\"/>");
                if (header) xml.append("<w:b/>");
                xml.append("</w:rPr><w:t xml:space=\"preserve\">").append(escape(text)).append("</w:t></w:r></w:p></w:tc>");
            }
            xml.append("</w:tr>"); header = false;
        }
        xml.append("</w:tbl><w:p/><w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr></w:body></w:document>");
        entry(zip, "word/document.xml", xml.toString());
    }
    private static String escape(String value) {
        StringBuilder text = new StringBuilder();
        value.codePoints().filter(c -> c == 9 || c == 10 || c == 13 || c >= 32 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF).forEach(text::appendCodePoint);
        return text.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + xml).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
