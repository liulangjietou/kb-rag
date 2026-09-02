package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tabular formats: xlsx (one page per sheet) and csv (one page).
 *
 * @author owlzhangfq@gmail.com
 */
class ParseTabularTest extends ParseEndpointTestBase {

    @Test
    void parseXlsxReturnsExpectedStructure() throws Exception {
        JsonNode body = postParse("sample.xlsx", ParserTestSupport.xlsxBytes(), "xlsx");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        assertTrue(data.get("markdown").asText().contains("Sheet1"));
        assertTrue(data.get("markdown").asText().contains("Alice"));
        assertEquals(1, data.get("pages").size());
        assertTrue(data.get("pages").get(0).get("text").asText().contains("Alice"));
        assertTrue(data.get("images").isEmpty());
    }

    @Test
    void xlsxWholeNumbersDoNotGrowADecimalPoint() throws Exception {
        // POI hands every numeric cell back as a double; rendering "30.0" into the corpus where the
        // sheet showed "30" would be a silent corruption of every table this service emits.
        JsonNode body = postParse("sample.xlsx", ParserTestSupport.xlsxBytes(), "xlsx");

        String markdown = body.get("data").get("markdown").asText();
        assertTrue(markdown.contains("| Alice | 30 |"), markdown);
        assertFalse(markdown.contains("30.0"));
    }

    @Test
    void parseCsvReturnsExpectedStructure() throws Exception {
        JsonNode body = postParse("sample.csv", ParserTestSupport.csvBytes(), "csv");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        assertTrue(data.get("markdown").asText().contains("Alice"));
        assertTrue(data.get("markdown").asText().contains("Name"));
        assertEquals(1, data.get("pages").size());
        assertTrue(data.get("pages").get(0).get("text").asText().contains("Bob"));
        assertTrue(data.get("images").isEmpty());
    }

    @Test
    void csvDelimiterIsDetected() throws Exception {
        byte[] semicolonCsv = "Name;Age\r\nAlice;30\r\nBob;25\r\n".getBytes(StandardCharsets.UTF_8);

        JsonNode body = postParse("semicolon.csv", semicolonCsv, "csv");

        String markdown = body.get("data").get("markdown").asText();
        assertTrue(markdown.contains("| Name | Age |"), markdown);
        assertTrue(markdown.contains("| Alice | 30 |"), markdown);
    }

    @Test
    void csvWithACommaInsideAQuotedCellStaysCommaSeparated() throws Exception {
        byte[] csv = "Name,Note\r\nAlice,\"a; b; c\"\r\nBob,\"d; e; f\"\r\n".getBytes(StandardCharsets.UTF_8);

        JsonNode body = postParse("quoted.csv", csv, "csv");

        String markdown = body.get("data").get("markdown").asText();
        assertTrue(markdown.contains("| Alice | a; b; c |"), markdown);
    }
}
