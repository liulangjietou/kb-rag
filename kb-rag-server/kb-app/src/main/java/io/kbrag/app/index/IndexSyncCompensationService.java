package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.ChunkIndexSync;
import io.kbrag.domain.enums.IndexSyncStatus;
import io.kbrag.domain.mapper.ChunkIndexSyncMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.port.EmbeddingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repairs the divergence between the chunk fact source and the search engines.
 *
 * <p>Why a scan rather than a transaction. MySQL and a search engine cannot share one transaction, so
 * a write that spans both has a window where the process can die: the row is committed, the engine
 * document is not. The synchronization table turns that window into recorded evidence — a row stuck in
 * PENDING, or explicitly FAILED — and this scan converts the evidence back into a write. Eventual
 * consistency is therefore designed for, not tolerated.
 *
 * <p><b>Two symptoms, one cure.</b> FAILED means the write was attempted and refused; PENDING past its
 * timeout means the writer never came back to confirm. Both mean the same thing to the engine: the
 * document may be missing. Retrying an upsert is idempotent, so the scan does not need to know which
 * of the two happened.
 *
 * <p><b>Grouping by physical index.</b> Rows are grouped by the index they target, not by chunk,
 * because the target decides the work: only an index that declares a vector field needs an embedding
 * call, and the vector is not kept in MySQL, so it has to be recomputed. Grouping the other way would
 * re-embed a chunk once per target.
 *
 * <p>A row that keeps failing is abandoned after the configured number of attempts and reported
 * through an error log. Retrying forever would turn a permanent problem, such as a deleted index or a
 * revoked credential, into an unbounded load that hides the very failure it is trying to fix.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexSyncCompensationService {

    private final ChunkIndexSyncMapper chunkIndexSyncMapper;
    private final ChunkMapper chunkMapper;
    private final IndexAliasManager indexAliasManager;
    private final ChunkIndexWriter chunkIndexWriter;
    private final EmbeddingProvider embeddingProvider;
    private final KbProperties properties;

    /**
     * Periodic entry point.
     */
    @Scheduled(fixedDelayString = "${kb.sync.compensation-interval-ms:30000}")
    public void scan() {
        if (!properties.getSync().isCompensationEnabled()) {
            return;
        }
        try {
            int repaired = compensate();
            if (repaired > 0) {
                log.info("index sync compensation finished, repaired={}", repaired);
            }
        } catch (Exception e) {
            log.error("index sync compensation failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * Runs one compensation pass.
     *
     * @return number of chunks successfully rewritten
     */
    public int compensate() {
        List<ChunkIndexSync> pending = loadPending();
        if (CollectionUtils.isEmpty(pending)) {
            return 0;
        }
        Map<String, List<ChunkIndexSync>> byIndex = groupByPhysicalIndex(pending);
        int repaired = 0;
        for (Map.Entry<String, List<ChunkIndexSync>> entry : byIndex.entrySet()) {
            repaired += compensateIndex(entry.getKey(), entry.getValue());
        }
        return repaired;
    }

    /**
     * Selects the rows that need a retry.
     *
     * @return rows below the retry ceiling, newest last, capped by the configured batch size
     */
    private List<ChunkIndexSync> loadPending() {
        KbProperties.Sync sync = properties.getSync();
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(sync.getPendingTimeoutMinutes());
        return chunkIndexSyncMapper.selectList(new LambdaQueryWrapper<ChunkIndexSync>()
                .lt(ChunkIndexSync::getRetryCount, sync.getMaxRetry())
                .and(wrapper -> wrapper
                        .eq(ChunkIndexSync::getStatus, IndexSyncStatus.FAILED)
                        .or(inner -> inner
                                .eq(ChunkIndexSync::getStatus, IndexSyncStatus.PENDING)
                                .lt(ChunkIndexSync::getUpdatedAt, staleBefore)))
                .orderByAsc(ChunkIndexSync::getId)
                .last("limit " + sync.getBatchSize()));
    }

    /**
     * Groups the rows by the physical index they target.
     *
     * @param rows rows needing a retry
     * @return rows per physical index name, insertion ordered so a scan is reproducible
     */
    Map<String, List<ChunkIndexSync>> groupByPhysicalIndex(List<ChunkIndexSync> rows) {
        Map<String, List<ChunkIndexSync>> byIndex = new LinkedHashMap<>();
        for (ChunkIndexSync row : rows) {
            byIndex.computeIfAbsent(row.getPhysicalIndexName(), name -> new ArrayList<>()).add(row);
        }
        return byIndex;
    }

    /**
     * Replays the writes of one physical index.
     *
     * @param physicalIndexName physical index name
     * @param rows              rows targeting that index
     * @return number of chunks successfully rewritten
     */
    private int compensateIndex(String physicalIndexName, List<ChunkIndexSync> rows) {
        List<String> chunkIds = rows.stream().map(ChunkIndexSync::getChunkId).toList();
        Map<String, Chunk> chunkById = loadChunks(chunkIds);

        List<Chunk> chunks = new ArrayList<>(rows.size());
        for (ChunkIndexSync row : rows) {
            Chunk chunk = chunkById.get(row.getChunkId());
            if (chunk == null) {
                // The fact source dropped the chunk while the write was pending: nothing to replay,
                // and the engine copy is removed by the deletion path, so the row is simply forgotten.
                chunkIndexSyncMapper.deleteById(row.getId());
                continue;
            }
            chunks.add(chunk);
        }
        if (CollectionUtils.isEmpty(chunks)) {
            return 0;
        }

        IndexTarget target = resolveTarget(chunks.get(0).getKbId(), physicalIndexName);
        if (target == null) {
            log.error("no write target matches the physical index, errorCode={}, index={}, chunks={}",
                    ErrorCode.INTERNAL_ERROR, physicalIndexName, chunks.size());
            return 0;
        }

        try {
            Map<String, float[]> vectors = target.carriesVector() ? embed(chunks) : Map.of();
            chunkIndexWriter.writeTarget(target, chunks, vectors);
            log.info("index sync compensated, index={}, chunks={}, reEmbedded={}",
                    physicalIndexName, chunks.size(), target.carriesVector());
            return chunks.size();
        } catch (Exception e) {
            recordFailure(rows, physicalIndexName, e);
            return 0;
        }
    }

    /**
     * Recomputes the embeddings of the chunks being replayed.
     *
     * <p>The vector is never persisted in MySQL, so a vector carrying target always needs a fresh
     * embedding call; there is nothing to reuse.
     *
     * @param chunks chunks being replayed
     * @return vector per chunk id
     */
    private Map<String, float[]> embed(List<Chunk> chunks) {
        Map<String, float[]> vectors = new HashMap<>(chunks.size());
        if (!embeddingProvider.isConfigured()) {
            return vectors;
        }
        int batchSize = Math.max(1, embeddingProvider.maxBatchSize());
        for (int start = 0; start < chunks.size(); start += batchSize) {
            List<Chunk> batch = chunks.subList(start, Math.min(chunks.size(), start + batchSize));
            List<float[]> embedded = embeddingProvider.embed(batch.stream().map(Chunk::getContent).toList());
            for (int i = 0; i < batch.size(); i++) {
                vectors.put(batch.get(i).getChunkId(), embedded.get(i));
            }
        }
        return vectors;
    }

    /**
     * Increments the retry counter and abandons the rows that reached the ceiling.
     *
     * @param rows              rows that were being replayed
     * @param physicalIndexName physical index name
     * @param cause             failure cause
     */
    private void recordFailure(List<ChunkIndexSync> rows, String physicalIndexName, Exception cause) {
        int maxRetry = properties.getSync().getMaxRetry();
        for (ChunkIndexSync row : rows) {
            int retryCount = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
            chunkIndexWriter.markFailed(row.getChunkId(), physicalIndexName, retryCount);
            if (retryCount >= maxRetry) {
                log.error("index sync abandoned after {} retries, errorCode={}, index={}, chunkId={}",
                        retryCount, ErrorCode.INTERNAL_ERROR, physicalIndexName, row.getChunkId());
            }
        }
        log.error("index sync compensation batch failed, errorCode={}, index={}, chunks={}",
                ErrorCode.INTERNAL_ERROR, physicalIndexName, rows.size(), cause);
    }

    private IndexTarget resolveTarget(String kbId, String physicalIndexName) {
        for (IndexTarget target : indexAliasManager.resolveTargets(kbId)) {
            if (target.physicalIndexName().equals(physicalIndexName)) {
                return target;
            }
        }
        return null;
    }

    private Map<String, Chunk> loadChunks(List<String> chunkIds) {
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getChunkId, chunkIds));
        Map<String, Chunk> chunkById = new HashMap<>(chunks.size());
        for (Chunk chunk : chunks) {
            chunkById.put(chunk.getChunkId(), chunk);
        }
        return chunkById;
    }
}
