package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies the chunk generation of one document version onto another.
 *
 * <p>Used when a repeated upload matches an earlier build on all four reuse elements without being a
 * duplicate of the active version, which happens whenever a configuration change is reverted. Copying
 * the rows skips the two expensive stages, parsing and splitting, and guarantees the restored version
 * is byte identical to the one it came from rather than merely produced by the same parameters.
 *
 * <p><b>New identifiers, not shared rows.</b> A chunk belongs to exactly one version, because that is
 * what lets retrieval isolate versions with a single engine side filter and what lets an old version be
 * cleaned up without touching the live one. So the rows are duplicated and the parent links are
 * rewritten onto the new identifiers.
 *
 * <p><b>Recorded deviation.</b> The contract asks for the vectors to be reused as well. No port reads a
 * vector back out of an engine - they are write only projections - so the copied chunks are handed to
 * the normal indexing step, which embeds them again when a provider is configured. The parse and split
 * stages are genuinely skipped, and in a deployment without an embedding key nothing is recomputed at
 * all. Adding a vector read to both engine ports for this one case would cost more than the embedding
 * of a single document.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionArtifactReuser {

    private static final int ENABLED = 1;

    private final ChunkMapper chunkMapper;
    private final BizIdGenerator bizIdGenerator;

    /**
     * Duplicates every chunk of a source version onto a target version.
     *
     * @param document            owning document
     * @param target              version receiving the copies
     * @param sourceVersionId     version the rows are copied from
     * @param embeddingConfigured {@code true} when the vector route is available
     * @return copied chunks that have to reach the engines, parents excluded
     */
    public List<Chunk> copyChunks(Document document, DocumentVersion target, String sourceVersionId,
                                  boolean embeddingConfigured) {
        List<Chunk> source = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocumentVersionId, sourceVersionId)
                .orderByAsc(Chunk::getSeq)
                .orderByAsc(Chunk::getId));
        if (CollectionUtils.isEmpty(source)) {
            return List.of();
        }
        Set<String> parentIds = parentIdsOf(source);
        Map<String, String> newIdByOldId = new HashMap<>(source.size());
        for (Chunk original : source) {
            newIdByOldId.put(original.getChunkId(), bizIdGenerator.chunkId());
        }

        List<Chunk> indexable = new ArrayList<>(source.size());
        for (Chunk original : source) {
            boolean parent = parentIds.contains(original.getChunkId());
            Chunk copy = copyOf(document, target, original, newIdByOldId, embeddingConfigured && !parent);
            chunkMapper.insert(copy);
            if (!parent) {
                indexable.add(copy);
            }
        }
        log.info("chunk generation reused, docId={}, targetVersionId={}, sourceVersionId={}, "
                        + "copied={}, indexable={}",
                document.getDocId(), target.getVersionId(), sourceVersionId, source.size(), indexable.size());
        return indexable;
    }

    /**
     * Builds one copy.
     *
     * <p>The parent link is rewritten through the identifier map; a link that cannot be resolved is
     * dropped rather than pointing at the source version, which would make the copied child belong to
     * two versions at once and would resurrect the source version through the parent lookup.
     *
     * @param document            owning document
     * @param target              version receiving the copy
     * @param original            row being copied
     * @param newIdByOldId        identifier map of the whole generation
     * @param embeddingConfigured {@code true} when this copy has to be embedded
     * @return unsaved copy
     */
    private Chunk copyOf(Document document, DocumentVersion target, Chunk original,
                         Map<String, String> newIdByOldId, boolean embeddingConfigured) {
        Chunk copy = new Chunk();
        copy.setChunkId(newIdByOldId.get(original.getChunkId()));
        copy.setKbId(document.getKbId());
        copy.setDocId(document.getDocId());
        copy.setDocumentVersionId(target.getVersionId());
        copy.setContent(original.getContent());
        copy.setChunkTextHash(original.getChunkTextHash());
        copy.setParentId(original.getParentId() == null ? null : newIdByOldId.get(original.getParentId()));
        copy.setSeq(original.getSeq());
        copy.setChunkType(original.getChunkType());
        copy.setEnabled(original.getEnabled() == null ? ENABLED : original.getEnabled());
        copy.setMetadata(original.getMetadata());
        copy.setEmbeddingStatus(embeddingConfigured ? EmbeddingStatus.PENDING : EmbeddingStatus.SKIPPED);
        return copy;
    }

    /**
     * Identifiers of the chunks that act as a parent inside a generation.
     *
     * @param chunks whole generation
     * @return parent chunk ids, empty for a single level document
     */
    private Set<String> parentIdsOf(List<Chunk> chunks) {
        Set<String> parentIds = new HashSet<>();
        for (Chunk chunk : chunks) {
            if (chunk.getParentId() != null && !chunk.getParentId().isBlank()) {
                parentIds.add(chunk.getParentId());
            }
        }
        return parentIds;
    }
}
