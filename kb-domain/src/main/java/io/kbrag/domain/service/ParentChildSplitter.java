package io.kbrag.domain.service;

import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.model.ParentChunk;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import lombok.RequiredArgsConstructor;
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
 * @author owlzhangfq@gmail.com
 */
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
                children.add(new SplitChunk(childSeq++, child.getContent(), child.getTokenCount()));
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
}
