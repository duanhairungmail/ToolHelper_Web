package com.toolhelper.validation;

import com.toolhelper.support.ApiException;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.*;
import org.w3c.dom.Document;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.validation.ValidationModels.*;

class ValidationExportTest {
    static final String SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static Map<String, Document> unpack(byte[] bytes) throws Exception {
        Map<String, Document> files = new HashMap<>();
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) files.put(entry.getName(), factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes())));
        }
        return files;
    }
    @Test void xlsxUsesLiteralStringsEscapesXmlAndPreservesLongIdsAndFullDataset() throws Exception {
        var devices = new ArrayList<Device>();
        devices.add(new Device("=HYPERLINK(\"file\")<&\u0001😀", "+1", "900719925474099312345"));
        for (int i = 1; i < 125; i++) devices.add(new Device("设备" + i, "@address", "" + i));
        var files = unpack(ValidationExport.generate("devices", "xlsx", List.of(), devices, List.of()));
        assertTrue(files.containsKey("[Content_Types].xml")); assertTrue(files.containsKey("_rels/.rels")); assertTrue(files.containsKey("xl/_rels/workbook.xml.rels"));
        var sheet = files.get("xl/worksheets/sheet1.xml");
        assertEquals(126, sheet.getElementsByTagNameNS(SHEET, "row").getLength());
        assertEquals(0, sheet.getElementsByTagNameNS(SHEET, "f").getLength());
        assertEquals("inlineStr", sheet.getElementsByTagNameNS(SHEET, "c").item(3).getAttributes().getNamedItem("t").getNodeValue());
        assertTrue(sheet.getDocumentElement().getTextContent().contains("900719925474099312345"));
        assertTrue(sheet.getDocumentElement().getTextContent().contains("=HYPERLINK(\"file\")<&😀"));
        assertEquals("A1:C126", sheet.getElementsByTagNameNS(SHEET, "autoFilter").item(0).getAttributes().getNamedItem("ref").getNodeValue());
    }
    @Test void docxContainsNativeTableRepeatingHeaderAndWhitelistedValues() throws Exception {
        var files = unpack(ValidationExport.generate("topics", "docx", List.of(), List.of(), List.of(new Topic("网关&<1>", "down/1", "up/1"))));
        var document = files.get("word/document.xml");
        assertNotNull(document); assertEquals(1, document.getElementsByTagNameNS(WORD, "tbl").getLength());
        assertEquals(2, document.getElementsByTagNameNS(WORD, "tr").getLength()); assertEquals(1, document.getElementsByTagNameNS(WORD, "tblHeader").getLength());
        assertTrue(document.getDocumentElement().getTextContent().contains("网关&<1>"));
        assertEquals("landscape", document.getElementsByTagNameNS(WORD, "pgSz").item(0).getAttributes().getNamedItemNS(WORD, "orient").getNodeValue());
        assertFalse(files.keySet().stream().anyMatch(name -> name.contains("vba") || name.contains("externalLink")));
    }
    @Test void allThreeViewsSupportBothFormatsAndRejectUnsupportedOrEmptyExport() throws Exception {
        var results = List.of(new Result(1, "summary", "状态", "GET", "/api/getIndexDeviceSummaryList", 200, 12, true, "BUSINESS_OK", "通过"));
        var devices = List.of(new Device("设备", "1", "2")); var topics = List.of(new Topic("网关", "d", "u"));
        for (String view : List.of("results", "devices", "topics")) for (String format : List.of("xlsx", "docx")) assertFalse(unpack(ValidationExport.generate(view, format, results, devices, topics)).isEmpty());
        assertThrows(ApiException.class, () -> ValidationExport.generate("invalid", "xlsx", results, devices, topics));
        assertThrows(ApiException.class, () -> ValidationExport.generate("results", "csv", results, devices, topics));
        assertThrows(ApiException.class, () -> ValidationExport.generate("results", "xlsx", List.of(), devices, topics));
    }
}
