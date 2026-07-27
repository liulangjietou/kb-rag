package io.kbrag.app.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.model.ChatMessageSpan;
import io.kbrag.domain.model.FusedChunk;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collapses overlapping chat windows that recall the same passage.
 *
 * <p><b>Why this exists.</b> An overlapping aggregation window puts the same turns in two chunks on
 * purpose, so a query matching those turns recalls both. Without this stage the caller receives two
 * results that quote each other, both of them consuming a slot of {@code top_n} and a slot of the answer
 * budget for one piece of knowledge. The duplicate is created by the import parameter, so it is undone
 * here rather than left for a caller to notice.
 *
 * <p><b>Where it runs, and why not one stage earlier or later.</b> After fusion, because only a fused
 * ranking says which of two overlapping windows is the better hit; before rerank, because a duplicate
 * carried into the cross encoder is paid for twice and would then be discarded anyway.
 *
 * <p><b>Deliberately not part of {@link ParentChildMerger}.</b> Both stages reduce a candidate list, and
 * that is the whole of what they share. Parent merging groups children of one document into the passage a
 * reader gets back and keeps every member visible; this stage drops a candidate outright because another
 * candidate already contains it. Folding the two together would put "which document does this belong to"
 * and "have I already said this" behind one abstraction, and the parent grouping would then have to learn
 * about a metadata key that only the chat import writes.
 *
 * <p><b>A chunk without a message span is never touched.</b> That is one predicate, not a special case
 * list: an uploaded document carries no {@code msg_span}, so it can neither absorb nor be absorbed, and a
 * knowledge base holding no chat import runs this stage as an ordered walk that keeps everything.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class NearDuplicateWindowMerger {

    /**
     * Overlap ratio from which two windows of one conversation count as the same hit.
     *
     * <p>Half the smaller window. Below that the two windows genuinely carry different turns and dropping
     * one would lose content; at or above it the shorter window has more in common with its neighbour than
     * not, and the neighbour ranked higher.
     */
    static final double MERGE_RATIO_THRESHOLD = 0.5d;

    /**
     * Number of absorbed chunk ids reported per surviving result.
     *
     * <p>Capped because the list exists so a debug page can explain a ranking, not so a caller can
     * reconstruct the recall set: a query matching a long conversation can absorb dozens of windows, and
     * the response would then carry more bookkeeping than answer.
     */
    static final int MAX_MERGED_IDS = 5;

    /**
     * Drops the near duplicate windows of a ranked candidate list.
     *
     * @param ranked    candidates ordered by descending fusion score
     * @param chunkById fact source row per candidate chunk id
     * @return surviving candidates in the same order, plus the absorbed ids per survivor
     */
    public Outcome merge(List<FusedChunk> ranked, Map<String, Chunk> chunkById) {
        if (CollectionUtils.isEmpty(ranked)) {
            return Outcome.of(ranked, Map.of());
        }
        List<FusedChunk> kept = new ArrayList<>(ranked.size());
        List<Survivor> windows = new ArrayList<>();
        Map<String, List<String>> mergedIdsByChunk = new LinkedHashMap<>();
        int absorbed = 0;

        for (FusedChunk candidate : ranked) {
            ChatMessageSpan span = spanOf(chunkById.get(candidate.getChunkId()));
            if (span == null) {
                kept.add(candidate);
                continue;
            }
            Survivor winner = findAbsorbing(windows, span);
            if (winner == null) {
                kept.add(candidate);
                windows.add(new Survivor(candidate.getChunkId(), span));
                continue;
            }
            absorbed++;
            // The candidate leaves the ranking whether or not its id still fits in the report: the cap is
            // about how much bookkeeping the response carries, not about which results survive.
            List<String> mergedIds = mergedIdsByChunk.computeIfAbsent(winner.chunkId(),
                    key -> new ArrayList<>());
            if (mergedIds.size() < MAX_MERGED_IDS) {
                mergedIds.add(candidate.getChunkId());
            }
        }
        if (absorbed > 0) {
            log.info("near duplicate chat windows merged, candidates={}, kept={}, absorbed={}",
                    ranked.size(), kept.size(), absorbed);
        }
        return Outcome.of(kept, mergedIdsByChunk);
    }

    /**
     * Finds the surviving window that already covers a candidate.
     *
     * <p>Compared against the survivors rather than against every earlier candidate: an absorbed window is
     * no longer in the ranking, so letting it absorb a third one would make the outcome depend on which of
     * two equally overlapping neighbours happened to come first.
     *
     * @param windows surviving windows in ranking order
     * @param span    span of the candidate being judged
     * @return best ranked window that absorbs the candidate, {@code null} when none does
     */
    private Survivor findAbsorbing(List<Survivor> windows, ChatMessageSpan span) {
        for (Survivor survivor : windows) {
            if (survivor.span().overlapRatio(span) >= MERGE_RATIO_THRESHOLD) {
                return survivor;
            }
        }
        return null;
    }

    /**
     * Reads the message span a chunk recorded.
     *
     * @param chunk fact source row, {@code null} when the candidate has none
     * @return span, {@code null} when the chunk is not an aggregation window
     */
    private ChatMessageSpan spanOf(Chunk chunk) {
        if (chunk == null || chunk.getMetadata() == null || chunk.getMetadata().isBlank()) {
            return null;
        }
        Map<String, Object> metadata = JsonUtil.parse(chunk.getMetadata(),
                new TypeReference<Map<String, Object>>() {
                });
        if (metadata == null) {
            return null;
        }
        return ChatMessageSpan.from(metadata.get(ChunkMetadataKeys.SESSION_ID),
                metadata.get(ChunkMetadataKeys.MSG_SPAN));
    }

    /**
     * One window that survived, with the span later candidates are judged against.
     *
     * @param chunkId chunk business id
     * @param span    message range the window covers
     */
    private record Survivor(String chunkId, ChatMessageSpan span) {
    }

    /**
     * What the stage produced.
     *
     * @param kept             surviving candidates in ranking order
     * @param mergedIdsByChunk absorbed chunk ids per surviving chunk id, capped per survivor
     */
    public record Outcome(List<FusedChunk> kept, Map<String, List<String>> mergedIdsByChunk) {

        private static Outcome of(List<FusedChunk> kept, Map<String, List<String>> mergedIdsByChunk) {
            return new Outcome(kept, mergedIdsByChunk);
        }

        /**
         * Absorbed chunk ids of one surviving result.
         *
         * @param chunkId surviving chunk business id
         * @return absorbed ids, empty when the result absorbed nothing
         */
        public List<String> mergedIdsOf(String chunkId) {
            List<String> ids = mergedIdsByChunk.get(chunkId);
            return ids == null ? List.of() : ids;
        }
    }
}
