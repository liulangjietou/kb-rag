package io.kbrag.domain.service;

import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.model.ParentChunk;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the two level splitter: that both levels come from the same strategy, that children stay
 * inside their parent, and that the sequence numbering keeps the document readable in order.
 *
 * @author owlzhangfq@gmail.com
 */
class ParentChildSplitterTest {

    /** Source text of the offset consistency cases, short enough to fit one parent and one child. */
    private static final String SOURCE = "原始文本原始文本";

    private final ParentChildSplitter splitter =
            new ParentChildSplitter(new FixedLengthTextSplitter(new SimpleTokenEstimator()));

    @Test
    void shouldProduceChildrenInsideEveryParent() {
        List<ParentChunk> groups = splitter.split(sentences(60), params(true, 200, 60, 10));

        assertFalse(groups.isEmpty());
        for (ParentChunk group : groups) {
            assertFalse(group.getChildren().isEmpty());
            String parentText = normalize(group.getParent().getContent());
            for (SplitChunk child : group.getChildren()) {
                // A child that is not a substring of its parent would make the returned parent text a
                // different passage from the one that matched.
                assertTrue(parentText.contains(normalize(child.getContent())),
                        "child text must come from its parent");
            }
        }
    }

    @Test
    void shouldNumberChildrenAcrossTheWholeDocument() {
        List<ParentChunk> groups = splitter.split(sentences(60), params(true, 200, 60, 10));

        int expected = 0;
        for (ParentChunk group : groups) {
            for (SplitChunk child : group.getChildren()) {
                assertEquals(expected++, child.getSeq());
            }
        }
        assertTrue(expected > groups.size(), "a parent should hold more than one child");
    }

    @Test
    void shouldNumberParentsInReadingOrder() {
        List<ParentChunk> groups = splitter.split(sentences(60), params(true, 200, 60, 10));

        for (int i = 0; i < groups.size(); i++) {
            assertEquals(i, groups.get(i).getParent().getSeq());
        }
    }

    @Test
    void shouldKeepParentsWithinTheirBudget() {
        ParentChildParams params = params(true, 120, 40, 5);
        SimpleTokenEstimator estimator = new SimpleTokenEstimator();

        for (ParentChunk group : splitter.split(sentences(80), params)) {
            assertTrue(estimator.estimate(group.getParent().getContent()) <= params.getParentMaxTokens());
            for (SplitChunk child : group.getChildren()) {
                assertTrue(estimator.estimate(child.getContent()) <= params.getChildMaxTokens());
            }
        }
    }

    @Test
    void shouldReturnNothingForBlankText() {
        assertTrue(splitter.split("   ", params(true, 200, 60, 10)).isEmpty());
    }

    @Test
    void shouldRejectAChildLargerThanItsParent() {
        assertThrows(IllegalArgumentException.class,
                () -> splitter.split("text", params(true, 100, 200, 10)));
    }

    @Test
    void shouldRejectAnOverlapThatCannotAdvance() {
        assertThrows(IllegalArgumentException.class,
                () -> splitter.split("text", params(true, 400, 100, 100)));
    }

    @Test
    void shouldReportOffsetsThatCutTheChildTextOutOfItsParent() {
        List<ParentChunk> groups = splitter.split(sentences(60), params(true, 200, 60, 10));

        int checked = 0;
        for (ParentChunk group : groups) {
            String parentText = group.getParent().getContent();
            for (SplitChunk child : group.getChildren()) {
                // The precise redaction of a disabled child cuts exactly this slice out of the parent, so
                // the identity below is the whole contract of the offsets.
                assertEquals(child.getContent(),
                        parentText.substring(child.getStartOffset(), child.getEndOffset()));
                checked++;
            }
        }
        assertTrue(checked > groups.size(), "a parent should hold more than one child");
    }

    @Test
    void shouldDropOffsetsWhenTheParentSliceDiffersFromTheChildText() {
        // The consistency gate against a regression in the offsets the fixed length strategy reports:
        // an offset that no longer describes the text returned would cut the wrong sentence out of every
        // answer, which is worse than returning the whole parent.
        ParentChildSplitter rewriting = new ParentChildSplitter(
                fakeSplitter(SOURCE, new SplitChunk(0, "改写后的文本", 6, null, 0, 6)));

        SplitChunk child = rewriting.split(SOURCE, params(true, 200, 60, 10))
                .get(0).getChildren().get(0);

        assertNull(child.getStartOffset());
        assertNull(child.getEndOffset());
    }

    @Test
    void shouldDropOffsetsThatReachOutsideTheParentText() {
        ParentChildSplitter overreaching = new ParentChildSplitter(
                fakeSplitter(SOURCE, new SplitChunk(0, SOURCE, 8, null, 0, 99)));

        SplitChunk child = overreaching.split(SOURCE, params(true, 200, 60, 10))
                .get(0).getChildren().get(0);

        assertNull(child.getStartOffset());
        assertNull(child.getEndOffset());
    }

    /**
     * A stub whose parent pass returns the input untouched and whose child pass returns exactly the
     * chunk the test wants to describe, offsets included.
     *
     * @param text  text both passes receive
     * @param child chunk returned by the child pass
     * @return splitter stub
     */
    private FixedLengthTextSplitter fakeSplitter(String text, SplitChunk child) {
        FixedLengthTextSplitter stub = mock(FixedLengthTextSplitter.class);
        when(stub.split(anyString(), any(SplitParams.class))).thenReturn(
                List.of(new SplitChunk(0, text, text.length(), null, 0, text.length())),
                List.of(child));
        return stub;
    }

    private ParentChildParams params(boolean enabled, int parentMax, int childMax, int childOverlap) {
        ParentChildParams params = new ParentChildParams();
        params.setEnabled(enabled);
        params.setParentMaxTokens(parentMax);
        params.setChildMaxTokens(childMax);
        params.setChildOverlap(childOverlap);
        return params;
    }

    private String sentences(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append("知识库检索需要把文档切成合适的片段").append(i).append("。");
        }
        return builder.toString();
    }

    /**
     * Removes the whitespace the splitter trims at chunk boundaries so a substring check compares text
     * rather than formatting.
     *
     * @param text chunk text
     * @return text without whitespace
     */
    private String normalize(String text) {
        return text.replaceAll("\\s+", "");
    }
}
