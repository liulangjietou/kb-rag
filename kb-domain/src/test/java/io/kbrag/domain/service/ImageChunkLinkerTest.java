package io.kbrag.domain.service;

import io.kbrag.domain.model.ProxiedContent;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the link between a chunk and the images its text derives from.
 *
 * <p>The last case runs the real splitter over a document with two figures, which is what proves the
 * offset recovery holds against the chunk boundaries the splitter actually produces rather than against
 * hand written ones.
 *
 * @author owlzhangfq@gmail.com
 */
class ImageChunkLinkerTest {

    private final ImageChunkLinker linker = new ImageChunkLinker();
    private final ImagePlaceholderResolver resolver = new ImagePlaceholderResolver();
    private final FixedLengthTextSplitter splitter =
            new FixedLengthTextSplitter(new SimpleTokenEstimator());

    @Test
    void shouldLinkOnlyTheChunkHoldingTheProxy() {
        String markdown = "first chunk text. IMAGE PROXY HERE. second chunk text.";
        List<ProxiedContent.ImagePlacement> placements = List.of(
                new ProxiedContent.ImagePlacement("img_a", "kb/a.png",
                        markdown.indexOf("IMAGE PROXY HERE"),
                        markdown.indexOf("IMAGE PROXY HERE") + "IMAGE PROXY HERE".length()));

        Map<Integer, List<String>> linked = linker.link(markdown, placements,
                List.of("first chunk text.", "IMAGE PROXY HERE.", "second chunk text."));

        assertEquals(1, linked.size());
        assertEquals(List.of("kb/a.png"), linked.get(1));
    }

    @Test
    void shouldLinkSeveralImagesToOneChunk() {
        String markdown = "one PROXY_A and PROXY_B together. tail.";
        List<ProxiedContent.ImagePlacement> placements = List.of(
                placement("img_a", "kb/a.png", markdown, "PROXY_A"),
                placement("img_b", "kb/b.png", markdown, "PROXY_B"));

        Map<Integer, List<String>> linked = linker.link(markdown, placements,
                List.of("one PROXY_A and PROXY_B together.", "tail."));

        assertEquals(List.of("kb/a.png", "kb/b.png"), linked.get(0));
        assertTrue(linked.get(1) == null, "the tail chunk holds no proxy");
    }

    @Test
    void shouldLinkAProxyStraddlingTwoChunks() {
        String markdown = "head. LONG PROXY TEXT split. tail.";
        List<ProxiedContent.ImagePlacement> placements = List.of(
                new ProxiedContent.ImagePlacement("img_a", "kb/a.png",
                        markdown.indexOf("LONG"), markdown.indexOf("split.") + "split.".length()));

        Map<Integer, List<String>> linked = linker.link(markdown, placements,
                List.of("head. LONG PROXY", " TEXT split. tail."));

        // Both chunks contain part of the description, so both derive from the image.
        assertEquals(List.of("kb/a.png"), linked.get(0));
        assertEquals(List.of("kb/a.png"), linked.get(1));
    }

    @Test
    void shouldReturnNothingWithoutPlacements() {
        assertTrue(linker.link("body", List.of(), List.of("body")).isEmpty());
        assertTrue(linker.link("body", null, List.of("body")).isEmpty());
    }

    @Test
    void shouldReturnNothingWithoutChunks() {
        assertTrue(linker.link("body", List.of(placement("img_a", "kb/a.png", "body", "body")),
                List.of()).isEmpty());
    }

    @Test
    void shouldSkipAChunkThatIsNotASubstringOfTheSource() {
        Map<Integer, List<String>> linked = linker.link("body PROXY tail",
                List.of(placement("img_a", "kb/a.png", "body PROXY tail", "PROXY")),
                List.of("text that never appeared in the source"));

        // Reporting a wrong image is worse than reporting none: the console would show an unrelated
        // thumbnail next to the passage.
        assertTrue(linked.isEmpty());
    }

    @Test
    void shouldSurviveTheRealSplitterOverADocumentWithTwoFigures() {
        String body = "。".repeat(400);
        String markdown = resolver.resolve(
                "opening paragraph。" + body + "[[IMAGE:img_1]]" + body + "[[IMAGE:img_2]]closing。",
                Map.of("img_1", new ImagePlaceholderResolver.ProxyTarget("img_a", "kb/a.png",
                                "the first figure shows a pipeline diagram"),
                        "img_2", new ImagePlaceholderResolver.ProxyTarget("img_b", "kb/b.png",
                                "the second figure shows a score distribution")))
                .getMarkdown();
        ProxiedContent content = resolver.resolve(markdown, Map.of());
        List<ProxiedContent.ImagePlacement> placements = List.of(
                placement("img_a", "kb/a.png", markdown, "the first figure"),
                placement("img_b", "kb/b.png", markdown, "the second figure"));

        List<SplitChunk> chunks = splitter.split(content.getMarkdown(), SplitParams.of(200, 20));
        Map<Integer, List<String>> linked = linker.link(markdown, placements,
                chunks.stream().map(SplitChunk::getContent).toList());

        assertTrue(chunks.size() > 2, "the document has to be cut into several chunks to be meaningful");
        List<String> allKeys = linked.values().stream().flatMap(List::stream).distinct().sorted().toList();
        assertEquals(List.of("kb/a.png", "kb/b.png"), allKeys);
        // Each figure belongs to a bounded neighbourhood, never to the whole document.
        assertTrue(linked.size() < chunks.size(), "a figure must not be attached to every chunk");
    }

    private ProxiedContent.ImagePlacement placement(String imageId, String objectKey,
                                                    String markdown, String needle) {
        int start = markdown.indexOf(needle);
        return new ProxiedContent.ImagePlacement(imageId, objectKey, start, start + needle.length());
    }
}
