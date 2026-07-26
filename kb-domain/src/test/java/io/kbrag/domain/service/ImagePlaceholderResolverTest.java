package io.kbrag.domain.service;

import io.kbrag.domain.model.ProxiedContent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the substitution: the proxy lands where its image stood, the recorded offsets point at it, and a
 * placeholder without a proxy leaves no marker behind.
 *
 * @author owlzhangfq@gmail.com
 */
class ImagePlaceholderResolverTest {

    private final ImagePlaceholderResolver resolver = new ImagePlaceholderResolver();

    @Test
    void shouldSpliceTheProxyWhereThePlaceholderStood() {
        ProxiedContent content = resolver.resolve(
                "before the figure\n[[IMAGE:img_1]]\nafter the figure",
                Map.of("img_1", target("img_a", "kb/1/img_1.png", "a bar chart of quarterly revenue")));

        assertFalse(content.getMarkdown().contains("[[IMAGE:"), "the marker must not survive");
        assertTrue(content.getMarkdown().contains(ImagePlaceholderResolver.PROXY_LABEL
                + "a bar chart of quarterly revenue"));
        int proxyAt = content.getMarkdown().indexOf(ImagePlaceholderResolver.PROXY_LABEL);
        assertTrue(content.getMarkdown().indexOf("before the figure") < proxyAt);
        assertTrue(proxyAt < content.getMarkdown().indexOf("after the figure"));
    }

    @Test
    void shouldRecordOffsetsPointingAtTheInsertedText() {
        ProxiedContent content = resolver.resolve("head [[IMAGE:img_1]] tail",
                Map.of("img_1", target("img_a", "kb/1/img_1.png", "chart description")));

        assertEquals(1, content.getPlacements().size());
        ProxiedContent.ImagePlacement placement = content.getPlacements().get(0);
        String inserted = content.getMarkdown().substring(placement.getStart(), placement.getEnd());
        assertTrue(inserted.contains("chart description"));
        assertEquals("img_a", placement.getImageId());
        assertEquals("kb/1/img_1.png", placement.getObjectKey());
    }

    @Test
    void shouldKeepTheReadingOrderOfSeveralImages() {
        Map<String, ImagePlaceholderResolver.ProxyTarget> proxies = new LinkedHashMap<>();
        proxies.put("img_1", target("img_a", "kb/a.png", "first figure"));
        proxies.put("img_2", target("img_b", "kb/b.png", "second figure"));

        ProxiedContent content = resolver.resolve(
                "one\n[[IMAGE:img_2]]\ntwo\n[[IMAGE:img_1]]\nthree", proxies);

        // The order follows the document, not the map: img_2 appears first in the text.
        assertEquals("img_b", content.getPlacements().get(0).getImageId());
        assertEquals("img_a", content.getPlacements().get(1).getImageId());
        assertTrue(content.getPlacements().get(0).getEnd() <= content.getPlacements().get(1).getStart());
    }

    @Test
    void shouldDropAPlaceholderWithoutAProxy() {
        ProxiedContent content = resolver.resolve("head\n[[IMAGE:img_1]]\ntail", Map.of());

        // A vision model that was not configured or failed must not leak an internal marker into an answer.
        assertFalse(content.getMarkdown().contains("[[IMAGE:"));
        assertTrue(content.getMarkdown().contains("head"));
        assertTrue(content.getMarkdown().contains("tail"));
        assertTrue(content.getPlacements().isEmpty());
    }

    @Test
    void shouldDropAPlaceholderWhoseProxyIsBlank() {
        ProxiedContent content = resolver.resolve("head [[IMAGE:img_1]] tail",
                Map.of("img_1", target("img_a", "kb/a.png", "   ")));

        assertFalse(content.getMarkdown().contains("[[IMAGE:"));
        assertTrue(content.getPlacements().isEmpty());
    }

    @Test
    void shouldLeaveTextWithoutPlaceholdersUntouched() {
        ProxiedContent content = resolver.resolve("plain body text", Map.of());

        assertEquals("plain body text", content.getMarkdown());
        assertTrue(content.getPlacements().isEmpty());
    }

    @Test
    void shouldTolerateBlankInput() {
        assertEquals("", resolver.resolve(null, Map.of()).getMarkdown());
        assertEquals("", resolver.resolve("", Map.of()).getMarkdown());
    }

    @Test
    void shouldBuildThePlaceholderOfAnImageId() {
        assertEquals("[[IMAGE:img_7]]", resolver.placeholderOf("img_7"));
    }

    private ImagePlaceholderResolver.ProxyTarget target(String imageId, String objectKey, String proxy) {
        return new ImagePlaceholderResolver.ProxyTarget(imageId, objectKey, proxy);
    }
}
