package io.kbrag.app.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.GraphChunkRelevance;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.service.ParentTextRedactor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes the units that survived the pipeline as the transport neutral nodes a caller receives.
 *
 * <p>Split out of {@link RetrievalService} because the two answer different questions. The service
 * decides <em>which</em> passages win - it orders the stages, runs the routes, merges the bases and
 * applies the threshold. This class decides <em>how</em> a winner is described: which chunk's text is
 * returned for a two level unit, what the score keys are called, which passages are cut out of a
 * returned parent, and how a stored image key becomes a link the caller can open. The split follows the
 * collaborators rather than a line count: {@link ParentTextRedactor} and {@link ObjectStorage} are read
 * here and nowhere else in the pipeline, and every display only metadata key below is written here and
 * read by nothing - which is why they do not belong next to the stage ordering.
 *
 * <p>MySQL stays the fact source on this side of the split as well: a parent's text is read from the
 * database rather than from a search engine, because parents are never indexed.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalNodeAssembler {

    private static final String META_FUSED_SCORE = "fused_score";
    private static final String META_VECTOR_SCORE = "vector_score";
    private static final String META_BM25_SCORE = "bm25_score";
    private static final String META_NORM_VECTOR_SCORE = "norm_vector_score";
    private static final String META_NORM_BM25_SCORE = "norm_bm25_score";
    private static final String META_RERANK_SCORE = "rerank_score";
    private static final String META_VECTOR_RANK = "vector_rank";
    private static final String META_BM25_RANK = "bm25_rank";

    /**
     * Graph route detail, requirement section 4.9: the relevance the in base ranking used, the hop count
     * that produced it and the entity names that reached the chunk. Present only on a node the graph route
     * contributed, so a caller can tell a three route call from a two route one node by node.
     */
    private static final String META_GRAPH_SCORE = "graph_score";
    private static final String META_GRAPH_HOPS = "graph_hops";
    private static final String META_GRAPH_ENTITIES = "graph_entities";
    private static final String META_CHUNK_SEQ = "chunk_seq";
    private static final String META_CHILD_IDS = "child_ids";
    private static final String META_DISABLED_CHILD_IDS = "disabled_child_ids";

    /**
     * Disabled children whose passage was actually cut out of the returned parent text, requirement
     * section 4.5. Present only when a cut happened, so its absence means "the parent is complete" - a
     * parent that could not be redacted precisely carries {@code disabled_child_ids} and no count.
     */
    private static final String META_REDACTED_CHILD_COUNT = "redacted_child_count";

    /**
     * Chat window chunks this result absorbed because their message ranges overlapped it, requirement
     * section 4.2. Present only on a node that absorbed at least one, so its absence means "nothing was
     * merged" rather than "this deployment does not merge".
     */
    private static final String META_MERGED_WINDOW_CHUNK_IDS = "merged_window_chunk_ids";
    private static final String META_CHILDREN = RetrievalMetadataKeys.CHILDREN;
    private static final String META_CHILD_CHUNK_ID = "chunk_id";
    private static final String META_CHILD_CONTENT = RetrievalMetadataKeys.CHILD_CONTENT;
    private static final String META_CHILD_SCORE = "score";
    private static final String META_CHILD_SCORE_TYPE = "score_type";

    /**
     * Knowledge base a node came from. Present on every node, single base calls included: a caller that
     * has to branch on whether the key exists cannot use it, and the value is a fact of the chunk row
     * rather than a property of the routing stage.
     */
    private static final String META_KB_ID = "kb_id";

    private final ChunkMapper chunkMapper;
    private final ScoreThresholdPolicy scoreThresholdPolicy;
    private final ParentTextRedactor parentTextRedactor;
    private final ObjectStorage objectStorage;
    private final KbProperties properties;

    /**
     * The pipeline verdicts a node is described against, gathered so the stages that produced them stay
     * out of the assembler's signature.
     *
     * @param decision               threshold decision of this search
     * @param orderingMode           strategy that produced the final ordering: the in base one on a single
     *                               base call, reciprocal rank fusion once bases were merged
     * @param disabledChildrenByUnit disabled children to report per unit
     * @param nearDuplicates         outcome of the near duplicate window merge
     */
    public record AssemblyContext(ScoreThresholdPolicy.ThresholdDecision decision,
                                  FusionMode orderingMode,
                                  Map<String, List<DisabledChildVisibility.DisabledChild>>
                                          disabledChildrenByUnit,
                                  NearDuplicateWindowMerger.Outcome nearDuplicates) {
    }

    /**
     * Turns the surviving units into the transport neutral nodes.
     *
     * @param units   surviving units in ranking order
     * @param context pipeline verdicts these nodes are described against
     * @return ordered nodes
     */
    public List<RetrievalNodeView> assemble(List<RetrievalUnit> units, AssemblyContext context) {
        if (CollectionUtils.isEmpty(units)) {
            return List.of();
        }
        Map<String, Chunk> parentById = loadParents(units);
        List<RetrievalNodeView> nodes = new ArrayList<>(units.size());
        for (RetrievalUnit unit : units) {
            RetrievalCandidate best = unit.best();
            ScoreThresholdPolicy.ReportedScore reported =
                    scoreThresholdPolicy.report(best, context.decision(), context.orderingMode());
            Chunk answerChunk = unit.isParent()
                    ? parentById.getOrDefault(unit.getUnitId(), best.getChunk())
                    : best.getChunk();
            List<DisabledChildVisibility.DisabledChild> disabledChildren =
                    context.disabledChildrenByUnit().get(unit.getUnitId());
            ParentTextRedactor.Redaction redaction = redact(answerChunk, disabledChildren);
            nodes.add(RetrievalNodeView.builder()
                    .docId(answerChunk.getDocId())
                    .documentVersionId(answerChunk.getDocumentVersionId())
                    .chunkId(answerChunk.getChunkId())
                    .chunkType(answerChunk.getChunkType().code())
                    .content(redaction.text())
                    .score(reported.getValue())
                    .scoreType(reported.getType().code())
                    .retrievalSource(best.getFused().getPrimarySource().code())
                    .metadata(buildMetadata(unit, answerChunk, context, disabledChildren, redaction,
                            // Keyed by the best member rather than by the unit: the merge judged candidates,
                            // and on a two level base the unit id is a parent that was never a candidate.
                            context.nearDuplicates().mergedIdsOf(best.chunkId())))
                    .imageUrls(presignedImageUrls(unit, answerChunk))
                    .previewUrl(null)
                    .build());
        }
        return nodes;
    }

    /**
     * Cuts the excluded passages out of a returned parent text, requirement section 4.5.
     *
     * <p>Reached only for a parent the knowledge base decided to return - the strict switch removed the
     * others before this point - and only when the disabled children still know where they sit. Otherwise
     * the redactor hands the text back untouched, which is the pre M9 behaviour.
     *
     * @param answerChunk      chunk whose text is returned
     * @param disabledChildren excluded children of that chunk, {@code null} when there are none
     * @return text to return with the number of passages that were cut
     */
    private ParentTextRedactor.Redaction redact(
            Chunk answerChunk, List<DisabledChildVisibility.DisabledChild> disabledChildren) {
        if (CollectionUtils.isEmpty(disabledChildren)) {
            return new ParentTextRedactor.Redaction(answerChunk.getContent(), 0);
        }
        return parentTextRedactor.redact(answerChunk.getContent(), disabledChildren.stream()
                .map(DisabledChildVisibility.DisabledChild::toSpan).toList());
    }

    /**
     * Turns the stored image keys of a result into time limited pre signed URLs.
     *
     * <p>The keys are minted per response rather than stored as URLs: a link that lived in the index would
     * be public for as long as the index exists, and it would already be expired by the time it is read.
     *
     * <p>A two level result collects the keys of its matched children as well as its own, because the parent
     * text is what is returned and the image that made a child match is part of that text.
     *
     * @param unit        merged unit being returned
     * @param answerChunk chunk whose text is returned
     * @return pre signed URLs, empty when the result derives from no image
     */
    private List<String> presignedImageUrls(RetrievalUnit unit, Chunk answerChunk) {
        List<String> keys = new ArrayList<>(imageKeysOf(answerChunk));
        if (unit.isParent()) {
            for (RetrievalCandidate member : unit.getMembers()) {
                for (String key : imageKeysOf(member.getChunk())) {
                    if (!keys.contains(key)) {
                        keys.add(key);
                    }
                }
            }
        }
        if (CollectionUtils.isEmpty(keys)) {
            return List.of();
        }
        Duration ttl = Duration.ofMinutes(properties.getMinio().getPresignedTtlMinutes());
        List<String> urls = new ArrayList<>(keys.size());
        for (String key : keys) {
            try {
                urls.add(objectStorage.presignedUrl(key, ttl));
            } catch (Exception e) {
                // A thumbnail that cannot be signed must not fail a search: the passage is the answer.
                log.info("image could not be presigned for a search result, object={}", key);
            }
        }
        return urls;
    }

    /**
     * Reads the image keys a chunk recorded.
     *
     * @param chunk fact source row
     * @return object storage keys, empty when the chunk derives from no image
     */
    private List<String> imageKeysOf(Chunk chunk) {
        Object value = storedMetadata(chunk).get(ChunkMetadataKeys.IMAGE_URLS);
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item != null) {
                keys.add(String.valueOf(item));
            }
        }
        return keys;
    }

    /**
     * Parses the metadata column of a chunk.
     *
     * @param chunk fact source row
     * @return metadata document, empty when the column is blank
     */
    private Map<String, Object> storedMetadata(Chunk chunk) {
        if (chunk == null || chunk.getMetadata() == null || chunk.getMetadata().isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = JsonUtil.parse(chunk.getMetadata(),
                new TypeReference<Map<String, Object>>() {
                });
        return parsed == null ? Map.of() : parsed;
    }

    /**
     * Loads the parent rows of the two level units.
     *
     * <p>Parents are never indexed, so their text only exists in MySQL; this is the single reason the
     * engines can stay child only while the caller still receives a passage large enough to read.
     *
     * @param units merged units
     * @return parent chunk per parent chunk id
     */
    private Map<String, Chunk> loadParents(List<RetrievalUnit> units) {
        List<String> parentIds = units.stream()
                .filter(RetrievalUnit::isParent)
                .map(RetrievalUnit::getUnitId)
                .toList();
        if (CollectionUtils.isEmpty(parentIds)) {
            return Map.of();
        }
        List<Chunk> parents = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getChunkId, parentIds));
        Map<String, Chunk> parentById = new HashMap<>(parents.size());
        for (Chunk parent : parents) {
            parentById.put(parent.getChunkId(), parent);
        }
        return parentById;
    }

    private Map<String, Object> buildMetadata(RetrievalUnit unit, Chunk answerChunk,
                                              AssemblyContext context,
                                              List<DisabledChildVisibility.DisabledChild> disabledChildren,
                                              ParentTextRedactor.Redaction redaction,
                                              List<String> mergedWindowChunkIds) {
        Map<String, Object> metadata = new LinkedHashMap<>(scoreMetadata(unit.best()));
        // Read from the fact source row rather than from the routing decision: a node has to be traceable
        // to its base even when routing never ran, and the row is the only place that cannot disagree.
        metadata.put(META_KB_ID, answerChunk.getKbId());
        metadata.put(META_CHUNK_SEQ, answerChunk.getSeq());
        // The stored document facts travel next to the scores so a chat result card can show its
        // conversation, its senders and its time without a second round trip. The image keys are excluded:
        // they leave through image_urls as pre signed links and the raw keys are of no use to a caller.
        storedMetadata(answerChunk).forEach((key, value) -> {
            if (!ChunkMetadataKeys.IMAGE_URLS.equals(key)) {
                metadata.putIfAbsent(key, value);
            }
        });
        if (CollectionUtils.isNotEmpty(mergedWindowChunkIds)) {
            metadata.put(META_MERGED_WINDOW_CHUNK_IDS, mergedWindowChunkIds);
        }
        if (!unit.isParent()) {
            return metadata;
        }
        if (CollectionUtils.isNotEmpty(disabledChildren)) {
            // Reported whether or not the text could be cut: the caller has to be able to tell that
            // something inside this parent was excluded, and the two cases differ only by the count below.
            metadata.put(META_DISABLED_CHILD_IDS, disabledChildren.stream()
                    .map(DisabledChildVisibility.DisabledChild::chunkId).toList());
        }
        if (redaction.applied()) {
            metadata.put(META_REDACTED_CHILD_COUNT, redaction.redactedChildCount());
        }
        metadata.put(META_CHILD_IDS, unit.getMembers().stream().map(RetrievalCandidate::chunkId).toList());
        List<Map<String, Object>> children = new ArrayList<>(unit.getMembers().size());
        for (RetrievalCandidate member : unit.getMembers()) {
            ScoreThresholdPolicy.ReportedScore reported =
                    scoreThresholdPolicy.report(member, context.decision(), context.orderingMode());
            Map<String, Object> child = new LinkedHashMap<>();
            child.put(META_CHILD_CHUNK_ID, member.chunkId());
            child.put(META_CHILD_CONTENT, member.getChunk().getContent());
            child.put(META_CHILD_SCORE, reported.getValue());
            child.put(META_CHILD_SCORE_TYPE, reported.getType().code());
            child.putAll(scoreMetadata(member));
            children.add(child);
        }
        metadata.put(META_CHILDREN, children);
        return metadata;
    }

    /**
     * Per route evidence of one candidate, shared by the node metadata and by the child detail list.
     *
     * <p>Both the raw and the normalised score are exposed when they exist: the raw value is what the
     * engine reported and is the only one comparable with a manual query, while the normalised value
     * is what the weighted strategy actually summed, and a debug page that shows one without the other
     * cannot explain a ranking.
     *
     * @param candidate candidate being described
     * @return score keys, ordered for readability
     */
    private Map<String, Object> scoreMetadata(RetrievalCandidate candidate) {
        FusedChunk fused = candidate.getFused();
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put(META_FUSED_SCORE, fused.getFusedScore());
        putIfPresent(scores, META_VECTOR_SCORE, fused.routeScore(RetrievalSource.VECTOR));
        putIfPresent(scores, META_VECTOR_RANK, fused.getRouteRanks().get(RetrievalSource.VECTOR));
        putIfPresent(scores, META_NORM_VECTOR_SCORE, fused.normalizedScore(RetrievalSource.VECTOR));
        putIfPresent(scores, META_BM25_SCORE, fused.routeScore(RetrievalSource.BM25));
        putIfPresent(scores, META_BM25_RANK, fused.getRouteRanks().get(RetrievalSource.BM25));
        putIfPresent(scores, META_NORM_BM25_SCORE, fused.normalizedScore(RetrievalSource.BM25));
        putIfPresent(scores, META_RERANK_SCORE, candidate.getRerankScore());
        GraphChunkRelevance graph = candidate.getGraphEvidence();
        if (graph != null) {
            // The relevance is reported from the graph evidence rather than from the route score map: the
            // two are the same number, and reading it from the object that also carries the hop count keeps
            // the three keys of the debug row provably consistent with one another.
            scores.put(META_GRAPH_SCORE, graph.score());
            scores.put(META_GRAPH_HOPS, graph.hops());
            scores.put(META_GRAPH_ENTITIES, graph.entityNames());
        }
        return scores;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
