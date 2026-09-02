package io.kbrag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-page markdown contract (M14-CONTRACTS.md §F3).
 *
 * <p>kb-rag-server's page-splitting strategy used to cut {@code pages[].text}, the plain text as
 * extracted. That text carries neither the page heading nor the placeholder lines, so a page chunk
 * could never be linked to an image, and the whole cleaning stage - header/footer removal, watermarks,
 * regex replacements, masking - had been applied to the merged markdown only, never to what that
 * strategy actually indexed.
 *
 * <p>{@code pages[].markdown} is this page's slice of the merged markdown, which is what lets the
 * server clean page by page and reassemble losslessly. The invariant these tests protect: joining
 * every page's slice with a blank line reproduces the document markdown exactly.
 *
 * @author owlzhangfq@gmail.com
 */
class ParsePageMarkdownTest extends ParseEndpointTestBase {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[\\[IMAGE:([^\\]]+)]]");
    private static final String PAGE_SEPARATOR = "\n\n";

    /** The invariant kb-rag-server's page assembler depends on. */
    private static void assertPagesReassemble(JsonNode data) {
        List<String> slices = new ArrayList<>();
        data.get("pages").forEach(page -> slices.add(page.get("markdown").asText()));
        assertEquals(data.get("markdown").asText(), String.join(PAGE_SEPARATOR, slices));
    }

    @Test
    void pdfPagesCarryTheirOwnMarkdownSlice() throws Exception {
        JsonNode data = postParse("multi.pdf", ParserTestSupport.multiPagePdfBytes(3), "pdf").get("data");

        assertEquals(3, data.get("pages").size());
        for (int i = 0; i < 3; i++) {
            JsonNode page = data.get("pages").get(i);
            assertTrue(page.get("markdown").asText().startsWith("## Page " + (i + 1)));
            assertTrue(page.get("markdown").asText().contains("Page " + (i + 1) + " body text"));
            // The plain text stays what it was: header/footer detection compares pages against each
            // other and must keep seeing the extracted text.
            assertFalse(page.get("text").asText().contains("## Page"));
        }
        assertPagesReassemble(data);
    }

    @Test
    void pdfPageMarkdownCarriesTheImagePlaceholder() throws Exception {
        // The reason the field exists: on the old route the placeholder lived only in the merged
        // markdown, so every page chunk indexed without its image.
        JsonNode data = postParse("with-image.pdf",
                ParserTestSupport.pdfWithEmbeddedImageBytes(), "pdf").get("data");

        List<String> pagePlaceholders = placeholderIdsIn(data.get("pages").get(0).get("markdown").asText());
        List<String> imageIds = new ArrayList<>();
        data.get("images").forEach(image -> imageIds.add(image.get("image_id").asText()));

        assertEquals(imageIds, pagePlaceholders);
        assertTrue(placeholderIdsIn(data.get("pages").get(0).get("text").asText()).isEmpty());
        assertPagesReassemble(data);
    }

    @Test
    void txtSinglePageMarkdownIsTheWholeDocument() throws Exception {
        JsonNode data = postParse("sample.txt",
                "Hello kb-rag TXT\nsecond line".getBytes(StandardCharsets.UTF_8), "txt").get("data");

        assertEquals(data.get("markdown").asText(), data.get("pages").get(0).get("markdown").asText());
        assertPagesReassemble(data);
    }

    @Test
    void htmlSinglePageMarkdownIsTheWholeDocument() throws Exception {
        JsonNode data = postParse("sample.html",
                "<html><body><h1>Title</h1><p>Body text</p></body></html>".getBytes(StandardCharsets.UTF_8),
                "html").get("data");

        assertEquals(data.get("markdown").asText(), data.get("pages").get(0).get("markdown").asText());
        assertPagesReassemble(data);
    }

    @Test
    void docxSinglePageMarkdownKeepsWhatThePlainTextLoses() throws Exception {
        JsonNode data = postParse("sample.docx",
                ParserTestSupport.docxBytes("Title", "Hello kb-rag DOCX"), "docx").get("data");

        JsonNode page = data.get("pages").get(0);
        assertEquals(data.get("markdown").asText(), page.get("markdown").asText());
        // The plain text of a docx page is not its markdown: headings and tables survive only in the
        // latter, which is precisely why the page strategy has to cut the markdown and not the text.
        assertNotEquals(page.get("text").asText(), page.get("markdown").asText());
        assertPagesReassemble(data);
    }

    @Test
    void excelSheetsEachCarryTheirOwnMarkdown() throws Exception {
        JsonNode data = postParse("sample.xlsx", ParserTestSupport.xlsxBytes(), "xlsx").get("data");

        data.get("pages").forEach(page ->
                assertTrue(page.get("markdown").asText().startsWith("## Sheet:")));
        assertPagesReassemble(data);
    }

    private static List<String> placeholderIdsIn(String markdown) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(markdown);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }
}
