package io.kbrag.app.index;

import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.port.EmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns chunk texts into vectors in provider sized batches and records the outcome on the rows.
 *
 * <p>Extracted because three paths need the exact same behaviour - the indexing pipeline, the chat
 * import and the annotation change pipeline - and a fourth copy would eventually disagree with the
 * others about batching or about which status a chunk carries afterwards. The status is what the
 * compensation scan reads, so a disagreement there is invisible until a vector silently goes missing.
 *
 * <p>Zero key deployments get an empty map and every chunk marked SKIPPED, which is the state that
 * tells the scan there is nothing to chase.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkEmbedder {

    private final EmbeddingProvider embeddingProvider;
    private final ChunkMapper chunkMapper;

    /**
     * Tells whether the vector route is available at all.
     *
     * @return {@code true} when an embedding provider is configured
     */
    public boolean isConfigured() {
        return embeddingProvider.isConfigured();
    }

    /**
     * Embeds chunks and persists the resulting embedding status.
     *
     * @param chunks chunks to embed, kept in sync in memory
     * @return vector per chunk id, empty in zero key mode
     */
    public Map<String, float[]> embed(List<Chunk> chunks) {
        Map<String, float[]> vectors = new HashMap<>();
        if (CollectionUtils.isEmpty(chunks) || !embeddingProvider.isConfigured()) {
            return vectors;
        }
        int batchSize = Math.max(1, embeddingProvider.maxBatchSize());
        for (int start = 0; start < chunks.size(); start += batchSize) {
            List<Chunk> batch = chunks.subList(start, Math.min(chunks.size(), start + batchSize));
            List<String> texts = batch.stream().map(Chunk::getContent).toList();
            List<float[]> embedded = embeddingProvider.embed(texts);
            for (int i = 0; i < batch.size(); i++) {
                Chunk chunk = batch.get(i);
                vectors.put(chunk.getChunkId(), embedded.get(i));
                chunk.setEmbeddingStatus(EmbeddingStatus.DONE);
                chunkMapper.updateById(chunk);
            }
        }
        log.info("chunks embedded, count={}, model={}", vectors.size(), embeddingProvider.model());
        return vectors;
    }
}
