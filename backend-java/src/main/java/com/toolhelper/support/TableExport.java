package com.toolhelper.support;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** 文本表格 OOXML 生成；调用方负责固定列定义和输入配额。 */
public final class TableExport {
    private TableExport() {}
    public static byte[] generate(String title, String format, List<List<String>> rows, int[] widths) {
        return report(title, format, rows, widths, List.of(), List.of());
    }
    /** 报告可附端点明细与说明；已有模块无附录时保持原有单表布局。 */
    public static byte[] report(String title, String format, List<List<String>> rows, int[] widths,
            List<List<String>> details, List<String> notes) {
        if (rows.size() < 2) throw new ApiException(409, "NO_EXPORT_DATA", "当前表格没有可导出数据");
        if (format == null || !Set.of("xlsx", "docx").contains(format)) throw new ApiException(400, "INVALID_EXPORT_FORMAT", "只支持.xlsx和.docx");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                boolean excel = format.equals("xlsx");
                String main = excel ? "xl/workbook.xml" : "word/document.xml";
                entry(zip, "_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"" + main + "\"/></Relationships>");
                entry(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + (excel ? "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" + sheetTypes(details, notes)
                        : "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>") + "</Types>");
                if (excel) xlsx(zip, title, rows, details, notes); else docx(zip, title, rows, widths, details, notes);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException("内存导出失败"); }
    }
    private static String sheetTypes(List<List<String>> details, List<String> notes) {
        StringBuilder types = new StringBuilder();
        int count = 1 + (details.isEmpty() ? 0 : 1) + (notes.isEmpty() ? 0 : 1);
        for (int i = 1; i <= count; i++) types.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return types.toString();
    }
    private static void xlsx(ZipOutputStream zip, String title, List<List<String>> rows, List<List<String>> details, List<String> notes) throws IOException {
        List<String> titles = new ArrayList<>(List.of(title));
        List<List<List<String>>> sheets = new ArrayList<>(List.of(rows));
        if (!details.isEmpty()) { titles.add("端点详情"); sheets.add(details); }
        if (!notes.isEmpty()) { titles.add("整改与限制"); List<List<String>> data = new ArrayList<>(); data.add(List.of("说明")); notes.forEach(note -> data.add(List.of(note))); sheets.add(data); }
        StringBuilder workbook = new StringBuilder("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder relationships = new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            workbook.append("<sheet name=\"").append(escape(titles.get(i))).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
            relationships.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>");
            xlsxSheet(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheets.get(i));
        }
        entry(zip, "xl/workbook.xml", workbook.append("</sheets></workbook>").toString());
        entry(zip, "xl/_rels/workbook.xml.rels", relationships.append("</Relationships>").toString());
    }
    private static void xlsxSheet(ZipOutputStream zip, String path, List<List<String>> rows) throws IOException {
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
        entry(zip, path, xml.toString());
    }
    private static void docx(ZipOutputStream zip, String title, List<List<String>> rows, int[] widths,
            List<List<String>> details, List<String> notes) throws IOException {
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
        xml.append("</w:tbl>");
        if (!details.isEmpty()) {
            reportParagraph(xml, "端点详情");
            for (List<String> detail : details.subList(1, details.size())) {
                List<String> values = new ArrayList<>();
                for (int i = 0; i < detail.size(); i++) values.add(details.getFirst().get(i) + "：" + detail.get(i));
                reportParagraph(xml, String.join("；", values));
            }
        }
        if (!notes.isEmpty()) { reportParagraph(xml, "整改建议与检测限制"); notes.forEach(note -> reportParagraph(xml, note)); }
        xml.append("<w:p/><w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr></w:body></w:document>");
        entry(zip, "word/document.xml", xml.toString());
    }
    private static void reportParagraph(StringBuilder xml, String text) {
        xml.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:eastAsia=\"Microsoft YaHei\"/><w:sz w:val=\"22\"/></w:rPr><w:t xml:space=\"preserve\">").append(escape(text)).append("</w:t></w:r></w:p>");
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
