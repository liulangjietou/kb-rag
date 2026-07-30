package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.port.EmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Turns chunk texts into vectors in provider sized batches and records the outcome on the rows.
 *
 * <p>Extracted because three paths need the exact same behaviour - the indexing pipeline, the chat
 * import and the annotation change pipeline - and a fourth copy would eventually disagree with the
 * others about batching or about which status a chunk carries afterwards. The status is what the
 * compensation scan reads, so a disagreement there is invisible until a vector silently goes missing.
 *
 * <p><b>Batches run concurrently.</b> One batch is one round trip and batches share nothing, so running
 * them one after another made the embedding stage the longest wait of a document's indexing: 500 chunks
 * at a batch size of 10 is 50 sequential round trips. The concurrency is bounded by a shared pool rather
 * than a per call one, which is what keeps several documents indexing at once from together exceeding
 * the provider's rate limit. A single batch skips the pool entirely - the annotation paths embed one
 * chunk at a time, and scheduling that costs more than it saves.
 *
 * <p><b>The status write is per batch, not per chunk.</b> Every chunk of a finished batch carries the
 * same status, so it is one statement over the batch's ids instead of one row update each - 500 chunks
 * used to mean 500 UPDATEs. Writing the one column rather than the whole row also keeps the concurrent
 * batches off each other's optimistic lock version.
 *
 * <p>Zero key deployments get an empty map and every chunk marked SKIPPED, which is the state that
 * tells the scan there is nothing to chase.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ChunkEmbedder {

    /** Batch count below which the pool is not worth the scheduling. */
    private static final int SINGLE_BATCH = 1;

    private final EmbeddingProvider embeddingProvider;
    private final ChunkMapper chunkMapper;
    private final Executor embedExecutor;

    /**
     * Wired explicitly rather than through Lombok so the embedding pool can be qualified.
     */
    public ChunkEmbedder(EmbeddingProvider embeddingProvider, ChunkMapper chunkMapper,
                         @Qualifier(AsyncConfig.EMBED_EXECUTOR) Executor embedExecutor) {
        this.embeddingProvider = embeddingProvider;
        this.chunkMapper = chunkMapper;
        this.embedExecutor = embedExecutor;
    }

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
     * <p>A failing batch fails the whole call, exactly as the sequential version did: the caller turns
     * that into a failed version, and a half embedded version must never be presented as indexed. The
     * cause is unwrapped from its completion wrapper on the way out, because the pipeline reads the
     * error code off it to tell a parse failure from an index failure.
     *
     * @param chunks chunks to embed, kept in sync in memory
     * @return vector per chunk id, empty in zero key mode
     */
    public Map<String, float[]> embed(List<Chunk> chunks) {
        Map<String, float[]> vectors = new ConcurrentHashMap<>();
        if (CollectionUtils.isEmpty(chunks) || !embeddingProvider.isConfigured()) {
            return vectors;
        }
        List<List<Chunk>> batches = batches(chunks);
        if (batches.size() == SINGLE_BATCH) {
            embedBatch(batches.get(0), vectors);
        } else {
            embedConcurrently(batches, vectors);
        }
        log.info("chunks embedded, count={}, batches={}, model={}",
                vectors.size(), batches.size(), embeddingProvider.model());
        return vectors;
    }

    /**
     * Spreads the batches over the shared pool and waits for all of them.
     *
     * @param batches provider sized batches of one call
     * @param vectors vector sink, written from several threads
     */
    private void embedConcurrently(List<List<Chunk>> batches, Map<String, float[]> vectors) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(batches.size());
        for (List<Chunk> batch : batches) {
            futures.add(CompletableFuture.runAsync(() -> embedBatch(batch, vectors), embedExecutor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            throw unwrap(e);
        }
    }

    /**
     * Embeds one provider sized batch and marks its chunks in one statement.
     *
     * @param batch   chunks of one provider request
     * @param vectors vector sink
     */
    private void embedBatch(List<Chunk> batch, Map<String, float[]> vectors) {
        List<String> texts = batch.stream().map(Chunk::getContent).toList();
        List<float[]> embedded = embeddingProvider.embed(texts);
        List<String> chunkIds = new ArrayList<>(batch.size());
        for (int index = 0; index < batch.size(); index++) {
            Chunk chunk = batch.get(index);
            vectors.put(chunk.getChunkId(), embedded.get(index));
            chunk.setEmbeddingStatus(EmbeddingStatus.DONE);
            chunkIds.add(chunk.getChunkId());
        }
        chunkMapper.update(null, new LambdaUpdateWrapper<Chunk>()
                .set(Chunk::getEmbeddingStatus, EmbeddingStatus.DONE.name())
                .in(Chunk::getChunkId, chunkIds));
    }

    /**
     * Splits the chunks into provider sized batches.
     *
     * @param chunks chunks of one call
     * @return batches, each within the provider's request limit
     */
    private List<List<Chunk>> batches(List<Chunk> chunks) {
        int batchSize = Math.max(1, embeddingProvider.maxBatchSize());
        List<List<Chunk>> batches = new ArrayList<>((chunks.size() / batchSize) + 1);
        for (int start = 0; start < chunks.size(); start += batchSize) {
            batches.add(chunks.subList(start, Math.min(chunks.size(), start + batchSize)));
        }
        return batches;
    }

    /**
     * Restores the exception the caller expects from a completion wrapper.
     *
     * <p>The pipeline branches on the error code of a {@code BizException} to decide which failure state
     * to persist; handing it a {@code CompletionException} would both lose that branch and write the
     * wrapper's class name into the operator visible fail reason.
     *
     * @param e wrapper thrown by the join
     * @return cause as a runtime exception, or the wrapper when there is nothing to unwrap
     */
    private RuntimeException unwrap(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return cause == null ? e : new IllegalStateException(cause);
    }
}
