package io.kbrag.domain.service;

import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.model.PageRange;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the page strategy: one chunk per page, the page number on every chunk, and an over long page
 * re-split without losing which page it came from.
 *
 * <p>The strategy is cut from the cleaned markdown and the boundaries {@link PagedContentAssembler}
 * recorded in it, never from the parse artifact - that is what keeps a page chunk identical in kind to
 * what every other strategy produces, masking and image proxies included.
 *
 * @author owlzhangfq@gmail.com
 */
class PageSplitterTest {

    private final SimpleTokenEstimator tokenEstimator = new SimpleTokenEstimator();
    private final PageSplitter splitter =
            new PageSplitter(new FixedLengthTextSplitter(tokenEstimator), tokenEstimator);

    @Test
    void shouldEmitOneChunkPerPage() {
        String markdown = "第一页正文\n\n第二页正文\n\n第三页正文";

        List<SplitChunk> chunks = splitter.split(markdown,
                List.of(range(1, 0, 5), range(2, 7, 12), range(3, 14, 19)), params(200));

        assertEquals(3, chunks.size());
        assertEquals("第一页正文", chunks.get(0).getContent());
        assertEquals("第三页正文", chunks.get(2).getContent());
        assertEquals("2", chunks.get(1).getMetadata().get(ChunkMetadataKeys.PAGE_NO));
    }

    @Test
    void shouldNumberChunksSequentiallyAcrossPages() {
        List<SplitChunk> chunks = splitter.split("第一页正文\n\n第二页正文",
                List.of(range(1, 0, 5), range(2, 7, 12)), params(200));

        assertEquals(0, chunks.get(0).getSeq());
        assertEquals(1, chunks.get(1).getSeq());
    }

    @Test
    void shouldTreatTheWholeTextAsOnePageWithoutBoundaries() {
        // A format with no page concept - a plain text or an html file - reports no boundaries. The
        // strategy still has to produce the document rather than nothing.
        List<SplitChunk> chunks = splitter.split("没有分页的正文", List.of(), params(200));

        assertEquals(1, chunks.size());
        assertEquals("没有分页的正文", chunks.get(0).getContent());
        assertEquals("1", chunks.get(0).getMetadata().get(ChunkMetadataKeys.PAGE_NO));
    }

    @Test
    void shouldReSplitAnOverLongPageKeepingItsPageNumber() {
        String longPage = "句子。".repeat(200);
        String markdown = "短页正文\n\n" + longPage;

        List<SplitChunk> chunks = splitter.split(markdown,
                List.of(range(1, 0, 4), range(2, 6, markdown.length())), params(50));

        assertTrue(chunks.size() > 2, "the over long page must be cut into several chunks");
        // An over long page is still one page, just several chunks: dropping the number would make the
        // page filter miss exactly the pages that carry the most text.
        for (SplitChunk chunk : chunks.subList(1, chunks.size())) {
            assertEquals("2", chunk.getMetadata().get(ChunkMetadataKeys.PAGE_NO));
        }
    }

    @Test
    void shouldCutOnlyWhatTheBoundaryAddresses() {
        // The proof that the strategy consumes the cleaned text and nothing else: what sits between two
        // ranges - the separator the assembly inserted - belongs to no chunk.
        List<SplitChunk> chunks = splitter.split("第一页正文\n\n第二页正文",
                List.of(range(1, 0, 5), range(2, 7, 12)), params(200));

        for (SplitChunk chunk : chunks) {
            assertFalse(chunk.getContent().contains("\n\n"), "a chunk must not span the page separator");
        }
    }

    @Test
    void shouldSkipABoundaryThatDoesNotAddressTheText() {
        // Defensive against a preview stored against a different text: a range reaching past the end is
        // dropped rather than throwing, so the remaining pages still index.
        List<SplitChunk> chunks = splitter.split("第一页正文",
                List.of(range(1, 0, 5), range(2, 10, 40)), params(200));

        assertEquals(1, chunks.size());
        assertEquals("第一页正文", chunks.get(0).getContent());
    }

    @Test
    void shouldReturnNothingForABlankDocument() {
        assertTrue(splitter.split("   ", List.of(range(1, 0, 3)), params(200)).isEmpty());
        assertTrue(splitter.split(null, List.of(), params(200)).isEmpty());
    }

    private PageRange range(int pageNo, int start, int end) {
        return new PageRange(pageNo, start, end);
    }

    private SplitParams params(int maxTokens) {
        return SplitParams.of(maxTokens, 0);
    }
}
