package io.kbrag.domain.service;

import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.model.ParentChunk;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Two level splitter built by composing the single level strategy with itself.
 *
 * <p>The document is first cut into parents with no overlap, then every parent is cut into children
 * with the configured overlap. Reusing the existing strategy for both passes is not just economy: it
 * guarantees a child boundary is always a boundary the single level splitter would also have chosen,
 * so switching parent child on or off cannot change how sentences are broken.
 *
 * <p>Parents carry no overlap on purpose. Overlapping parents would duplicate text in the answer the
 * caller receives, and the overlap that matters for recall is the one between children, which is the
 * level the engines actually score.
 *
 * <p>Sequence numbers are assigned per level in reading order: parents get {@code 0..n-1} and
 * children get a document wide sequence, so the engine side {@code chunk_seq} still orders the whole
 * document and neighbouring children remain adjacent across a parent boundary.
 *
 * <p><b>Child offsets inside their parent, M9 requirement section 4.5.</b> The second pass runs on the
 * parent text, so the offsets the strategy reports are already relative to the parent - no reverse
 * lookup is performed anywhere. They are nevertheless verified here before they leave: the parent slice
 * has to be character for character the child text, and a strategy that rewrites its input (the LLM
 * semantic one) reports none at all. A mismatch drops the offsets rather than storing a plausible looking
 * pair, because a wrong offset would cut the wrong sentence out of an answer while a missing one only
 * falls back to returning the whole parent.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParentChildSplitter {

    /** Strategy code persisted in the split fingerprint. */
    public static final String STRATEGY_CODE = "parent_child";

    private final TextSplitter textSplitter;

    /**
     * Splits a text into parents and their children.
     *
     * @param text   source text, blank input yields an empty list
     * @param params parent child parameters, must be enabled
     * @return parents in reading order, each with its children
     */
    public List<ParentChunk> split(String text, ParentChildParams params) {
        SplitParams parentParams = SplitParams.of(params.getParentMaxTokens(), 0);
        SplitParams childParams = SplitParams.of(params.getChildMaxTokens(), params.getChildOverlap());
        if (params.getChildMaxTokens() > params.getParentMaxTokens()) {
            throw new IllegalArgumentException("child_max_tokens must not exceed parent_max_tokens");
        }

        List<SplitChunk> parents = textSplitter.split(text, parentParams);
        List<ParentChunk> result = new ArrayList<>(parents.size());
        int childSeq = 0;
        for (SplitChunk parent : parents) {
            List<SplitChunk> rawChildren = textSplitter.split(parent.getContent(), childParams);
            List<SplitChunk> children = new ArrayList<>(rawChildren.size());
            for (SplitChunk child : rawChildren) {
                boolean consistent = sliceMatches(parent.getContent(), child.getContent(),
                        child.getStartOffset(), child.getEndOffset());
                if (!consistent) {
                    log.info("child offsets dropped because the parent slice differs from the child text, "
                                    + "parentSeq={}, childSeq={}, start={}, end={}",
                            parent.getSeq(), childSeq, child.getStartOffset(), child.getEndOffset());
                }
                children.add(new SplitChunk(childSeq++, child.getContent(), child.getTokenCount(),
                        child.getMetadata(),
                        consistent ? child.getStartOffset() : null,
                        consistent ? child.getEndOffset() : null));
            }
            result.add(new ParentChunk(parent, children));
        }
        return result;
    }

    /**
     * Strategy identifier persisted in the split fingerprint.
     *
     * @return strategy code
     */
    public String strategy() {
        return STRATEGY_CODE;
    }

    /**
     * The single consistency gate of the offset feature: the parent slice must be the child text.
     *
     * @param parentText parent chunk text the offsets address
     * @param childText  child chunk text before normalisation
     * @param start      start offset reported by the strategy, {@code null} when it reports none
     * @param end        exclusive end offset reported by the strategy
     * @return {@code true} when the offsets may be stored
     */
    private boolean sliceMatches(String parentText, String childText, Integer start, Integer end) {
        if (parentText == null || childText == null || start == null || end == null) {
            return false;
        }
        if (start < 0 || start >= end || end > parentText.length()) {
            return false;
        }
        return parentText.substring(start, end).equals(childText);
    }
}
