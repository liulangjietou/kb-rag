package io.kbrag.domain.service;

import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.model.ParentChunk;
import io.kbrag.domain.model.SplitChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two level splitter: that both levels come from the same strategy, that children stay
 * inside their parent, and that the sequence numbering keeps the document readable in order.
 */
class ParentChildSplitterTest {

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
