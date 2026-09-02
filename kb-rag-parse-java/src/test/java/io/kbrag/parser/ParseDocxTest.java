package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docx: paragraphs, tables and embedded images, all on one logical page.
 *
 * @author owlzhangfq@gmail.com
 */
class ParseDocxTest extends ParseEndpointTestBase {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[\\[IMAGE:([^\\]]+)]]");

    @Test
    void parseDocxReturnsExpectedStructure() throws Exception {
        JsonNode body = postParse("sample.docx",
                ParserTestSupport.docxBytes("Title", "Hello kb-rag DOCX"), "docx");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        String markdown = data.get("markdown").asText();
        assertTrue(markdown.contains("Title"));
        assertTrue(markdown.contains("Hello kb-rag DOCX"));
        // Table content is rendered too.
        assertTrue(markdown.contains("Alice"));
        assertEquals(1, data.get("pages").size());
        assertTrue(data.get("pages").get(0).get("text").asText().contains("Hello kb-rag DOCX"));
        assertTrue(data.get("images").isEmpty());
    }

    @Test
    void headingStyleBecomesAMarkdownHeading() throws Exception {
        JsonNode body = postParse("sample.docx",
                ParserTestSupport.docxBytes("Title", "body"), "docx");

        assertTrue(body.get("data").get("markdown").asText().startsWith("# Title"));
    }

    @Test
    void embeddedImageIsExtracted() throws Exception {
        JsonNode body = postParse("with_image.docx",
                ParserTestSupport.docxWithImageBytes("Hello kb-rag DOCX with an embedded image"), "docx");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");

        assertEquals(1, data.get("pages").size());
        assertFalse(data.get("pages").get(0).get("scanned").asBoolean());

        assertEquals(1, data.get("images").size());
        JsonNode image = data.get("images").get(0);
        assertEquals("embedded", image.get("kind").asText());
        assertEquals("image/png", image.get("media_type").asText());
        assertEquals(1, image.get("page_no").asInt());
        assertFalse(image.get("content_base64").asText().isEmpty());

        List<String> placeholderIds = new ArrayList<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(data.get("markdown").asText());
        while (matcher.find()) {
            placeholderIds.add(matcher.group(1));
        }
        assertEquals(List.of(image.get("image_id").asText()), placeholderIds);
    }
}
