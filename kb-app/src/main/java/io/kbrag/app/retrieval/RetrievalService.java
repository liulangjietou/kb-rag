package io.kbrag.app.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.enums.RetrievalSource;
import io.kbrag.domain.enums.ScoreType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.model.FulltextQuery;
import io.kbrag.domain.model.FusedChunk;
import io.kbrag.domain.model.RetrievalFilter;
import io.kbrag.domain.model.ScoredChunk;
import io.kbrag.domain.model.VectorQuery;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.RrfFusion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieval service of M1: dual route recall plus reciprocal rank fusion.
 *
 * <p>Pipeline. The BM25 route always runs. When an embedding provider is configured the query is
 * embedded and the vector route runs as a second, independent request, even in lite mode where both
 * routes hit the same Elasticsearch index; issuing two requests is what yields two candidate sets with
 * their own scores, which a single hybrid request could not provide. The two lists are then fused by
 * rank, and the fact source rows are loaded from MySQL so the returned text can never be a stale copy
 * living in a search engine.
 *
 * <p>Mandatory filter. Both routes filter on the visible document versions and on the enabled flag
 * engine side. The filter is built here and cannot be influenced by request parameters, otherwise a
 * caller could recall chunks of an archived version.
 *
 * <p>Reported score. M1 has no rerank stage, so there is no single score that is comparable across
 * routes. Each node therefore reports the score of the route that ranked it best together with the
 * matching {@code score_type}, and the fusion score plus every per route score and rank travel in the
 * node metadata for the debug page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final String META_RRF_SCORE = "rrf_score";
    private static final String META_VECTOR_SCORE = "vector_score";
    private static final String META_BM25_SCORE = "bm25_score";
    private static final String META_VECTOR_RANK = "vector_rank";
    private static final String META_BM25_RANK = "bm25_rank";
    private static final String META_CHUNK_SEQ = "chunk_seq";
    private static final int ENABLED = 1;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final FulltextStore fulltextStore;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final IndexAliasManager indexAliasManager;
    private final RrfFusion rrfFusion;
    private final KbProperties properties;

    /**
     * Runs one retrieval call.
     *
     * @param kbId        knowledge base business id
     * @param query       user query
     * @param recallTopK  candidates recalled per route
     * @param topN        number of returned nodes
     * @return ordered nodes plus degradation markers
     */
    public SearchOutcome search(String kbId, String query, int recallTopK, int topN) {
        knowledgeBaseService.require(kbId);
        List<String> degraded = new ArrayList<>();

        List<String> visibleVersionIds = visibleVersionIds(kbId);
        if (CollectionUtils.isEmpty(visibleVersionIds)) {
            log.info("no active document version, kbId={}", kbId);
            return new SearchOutcome(List.of(), degraded);
        }
        RetrievalFilter filter = RetrievalFilter.builder()
                .kbId(kbId)
                .documentVersionIds(visibleVersionIds)
                .enabledOnly(true)
                .build();

        Map<RetrievalSource, List<ScoredChunk>> routeResults = new EnumMap<>(RetrievalSource.class);
        routeResults.put(RetrievalSource.BM25, fulltextStore.searchBm25(
                indexAliasManager.fulltextAlias(kbId),
                FulltextQuery.builder().queryText(query).topK(recallTopK).filter(filter).build()));

        if (embeddingProvider.isConfigured()) {
            float[] queryVector = embeddingProvider.embed(List.of(query)).get(0);
            routeResults.put(RetrievalSource.VECTOR, vectorStore.search(
                    indexAliasManager.vectorAlias(kbId),
                    VectorQuery.builder().queryVector(queryVector).topK(recallTopK).filter(filter).build()));
        } else {
            degraded.add(DegradedReason.VECTOR_ROUTE_UNAVAILABLE.code());
        }

        List<FusedChunk> fused = rrfFusion.fuse(routeResults, properties.getRetrieval().getRrfK());
        List<FusedChunk> selected = fused.size() > topN ? fused.subList(0, topN) : fused;
        List<RetrievalNodeView> nodes = toNodes(selected);
        log.info("search finished, kbId={}, recallTopK={}, topN={}, candidates={}, returned={}, degraded={}",
                kbId, recallTopK, topN, fused.size(), nodes.size(), degraded);
        return new SearchOutcome(nodes, degraded);
    }

    /**
     * Collects the version visibility set of a knowledge base.
     *
     * <p>Management console calls have no application version context, so the set is the current
     * active version of every document.
     *
     * @param kbId knowledge base business id
     * @return active document version ids
     */
    private List<String> visibleVersionIds(String kbId) {
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .isNotNull(Document::getCurrentVersionId));
        // TODO(M2): cache the visibility set per knowledge base once document counts grow.
        return documents.stream().map(Document::getCurrentVersionId).toList();
    }

    private List<RetrievalNodeView> toNodes(List<FusedChunk> fused) {
        if (CollectionUtils.isEmpty(fused)) {
            return List.of();
        }
        List<String> chunkIds = fused.stream().map(FusedChunk::getChunkId).toList();
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getChunkId, chunkIds)
                .eq(Chunk::getEnabled, ENABLED));
        Map<String, Chunk> chunkById = new HashMap<>();
        for (Chunk chunk : chunks) {
            chunkById.put(chunk.getChunkId(), chunk);
        }

        List<RetrievalNodeView> nodes = new ArrayList<>(fused.size());
        for (FusedChunk candidate : fused) {
            Chunk chunk = chunkById.get(candidate.getChunkId());
            if (chunk == null) {
                // The engine still carries a chunk MySQL no longer owns; the compensation scan removes it.
                log.info("skip candidate missing in fact source, chunkId={}", candidate.getChunkId());
                continue;
            }
            RetrievalSource primary = candidate.getPrimarySource();
            Double primaryScore = candidate.getRouteScores().get(primary);
            nodes.add(RetrievalNodeView.builder()
                    .docId(chunk.getDocId())
                    .documentVersionId(chunk.getDocumentVersionId())
                    .chunkId(chunk.getChunkId())
                    .chunkType(chunk.getChunkType().code())
                    .content(chunk.getContent())
                    .score(primaryScore == null ? 0.0d : primaryScore)
                    .scoreType(primary == RetrievalSource.VECTOR
                            ? ScoreType.COSINE.code() : ScoreType.BM25_RANK.code())
                    .retrievalSource(primary.code())
                    .metadata(buildMetadata(candidate, chunk))
                    .imageUrls(List.of())
                    .previewUrl(null)
                    .build());
        }
        return nodes;
    }

    private Map<String, Object> buildMetadata(FusedChunk candidate, Chunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(META_RRF_SCORE, candidate.getRrfScore());
        metadata.put(META_CHUNK_SEQ, chunk.getSeq());
        Double vectorScore = candidate.getRouteScores().get(RetrievalSource.VECTOR);
        if (vectorScore != null) {
            metadata.put(META_VECTOR_SCORE, vectorScore);
            metadata.put(META_VECTOR_RANK, candidate.getRouteRanks().get(RetrievalSource.VECTOR));
        }
        Double bm25Score = candidate.getRouteScores().get(RetrievalSource.BM25);
        if (bm25Score != null) {
            metadata.put(META_BM25_SCORE, bm25Score);
            metadata.put(META_BM25_RANK, candidate.getRouteRanks().get(RetrievalSource.BM25));
        }
        return metadata;
    }
}
