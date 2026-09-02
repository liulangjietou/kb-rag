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
 * Multimodal pdf behaviour (M3-CONTRACTS.md §2.1): scanned-page detection and rendering, embedded
 * image extraction, deduplication, and the cap protections.
 *
 * @author owlzhangfq@gmail.com
 */
class ParsePdfImagesTest extends ParseEndpointTestBase {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[\\[IMAGE:([^\\]]+)]]");

    /**
     * Every placeholder token in the markdown has exactly one images[] entry with that id, and vice
     * versa - the invariant kb-rag-server relies on to replace tokens in place.
     */
    private static void assertPlaceholdersMatchImages(JsonNode data) {
        List<String> placeholderIds = placeholderIdsIn(data.get("markdown").asText());
        List<String> imageIds = new ArrayList<>();
        data.get("images").forEach(image -> imageIds.add(image.get("image_id").asText()));

        assertEquals(imageIds.stream().sorted().toList(), placeholderIds.stream().sorted().toList());
        assertEquals(placeholderIds.size(), new java.util.HashSet<>(placeholderIds).size(),
                "placeholder ids must be unique");
    }

    private static List<String> placeholderIdsIn(String markdown) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(markdown);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    @Test
    void scannedPageIsRenderedToPng() throws Exception {
        JsonNode body = postParse("scanned.pdf", ParserTestSupport.scannedPdfBytes(1), "pdf");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");

        assertEquals(1, data.get("pages").size());
        assertTrue(data.get("pages").get(0).get("scanned").asBoolean());

        assertEquals(1, data.get("images").size());
        JsonNode image = data.get("images").get(0);
        assertEquals("page_render", image.get("kind").asText());
        assertEquals("image/png", image.get("media_type").asText());
        assertEquals(1, image.get("page_no").asInt());
        assertFalse(image.get("content_base64").asText().isEmpty());

        assertPlaceholdersMatchImages(data);
        assertTrue(data.get("warnings").isEmpty());
    }

    @Test
    void embeddedImageIsExtracted() throws Exception {
        JsonNode body = postParse("with_image.pdf", ParserTestSupport.pdfWithEmbeddedImageBytes(), "pdf");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");

        // Enough text on the page that it is not treated as scanned.
        assertFalse(data.get("pages").get(0).get("scanned").asBoolean());

        assertEquals(1, data.get("images").size());
        JsonNode image = data.get("images").get(0);
        assertEquals("embedded", image.get("kind").asText());
        assertTrue(List.of("image/png", "image/jpeg").contains(image.get("media_type").asText()));
        assertEquals(1, image.get("page_no").asInt());

        assertPlaceholdersMatchImages(data);
        assertTrue(data.get("warnings").isEmpty());
    }

    @Test
    void imageCountCapSkipsExtraImagesWithWarning() throws Exception {
        properties.setMaxImagesPerDoc(0);

        JsonNode body = postParse("with_image.pdf", ParserTestSupport.pdfWithEmbeddedImageBytes(), "pdf");

        // The image was skipped, but the rest of the document still parses: M3-CONTRACTS.md §2.1
        // "超限跳过并写 warnings，不失败整篇".
        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        assertTrue(data.get("images").isEmpty());
        assertFalse(data.get("markdown").asText().contains("[[IMAGE:"));
        assertEquals(1, data.get("warnings").size());
        assertTrue(data.get("warnings").get(0).asText().contains("limit"));
    }

    @Test
    void imageByteCapSkipsOversizedImageWithWarning() throws Exception {
        properties.setMaxImageBytes(1);

        JsonNode body = postParse("with_image.pdf", ParserTestSupport.pdfWithEmbeddedImageBytes(), "pdf");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");
        assertTrue(data.get("images").isEmpty());
        assertFalse(data.get("markdown").asText().contains("[[IMAGE:"));
        assertEquals(1, data.get("warnings").size());
        assertTrue(data.get("warnings").get(0).asText().contains("bytes"));
    }

    @Test
    void aRepeatedImageIsReportedOnce() throws Exception {
        // A header logo drawn on every page is one image object, so it must yield one images[] entry
        // and one placeholder - not one per page, which would cost kb-rag-server a vision call per
        // page for a single picture.
        JsonNode body = postParse("repeated_logo.pdf", ParserTestSupport.pdfWithRepeatedImageBytes(4), "pdf");

        assertEquals("OK", body.get("code").asText());
        JsonNode data = body.get("data");

        assertEquals(4, data.get("pages").size());
        assertEquals(1, data.get("images").size(), "the same raster on 4 pages is still one image");
        assertEquals("embedded", data.get("images").get(0).get("kind").asText());
        // The asset is attributed to where the picture first appears.
        assertEquals(1, data.get("images").get(0).get("page_no").asInt());

        assertPlaceholdersMatchImages(data);
        // Deduplication is not a degradation, so it must not surface a warning.
        assertTrue(data.get("warnings").isEmpty());
    }

    @Test
    void scannedPagesPastTheImageCapAreStillReportedAsSkipped() throws Exception {
        properties.setMaxImagesPerDoc(2);

        JsonNode body = postParse("scanned5.pdf", ParserTestSupport.scannedPdfBytes(5), "pdf");

        JsonNode data = body.get("data");
        assertEquals(2, data.get("images").size());
        // The 3 pages over the cap are reported as skipped rather than silently dropped.
        assertEquals(3, data.get("warnings").size());
        data.get("warnings").forEach(warning -> assertTrue(warning.asText().contains("limit")));
    }
}
