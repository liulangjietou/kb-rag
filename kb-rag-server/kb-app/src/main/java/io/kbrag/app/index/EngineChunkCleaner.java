package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.entity.ChunkIndexSync;
import io.kbrag.domain.mapper.ChunkIndexSyncMapper;
import io.kbrag.domain.port.GraphStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Removes chunks from every search engine of a knowledge base and forgets their synchronization rows.
 *
 * <p>MySQL is the fact source, so a chunk that no longer exists there must not survive in a derived
 * index. Three situations converge on this class: a document or knowledge base is deleted, a version
 * is rebuilt, and retrieval discovers an engine hit whose fact source row is gone. Keeping the
 * removal in one collaborator is what stops the third case from re-implementing the first two with a
 * subtly different definition of "everywhere".
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EngineChunkCleaner {

    /** Chunk ids per engine call and per {@code IN} predicate. */
    private static final int BATCH_SIZE = 500;

    private final IndexAliasManager indexAliasManager;
    private final ChunkIndexSyncMapper chunkIndexSyncMapper;
    private final GraphStore graphStore;

    /**
     * Removes chunks from every derived store and drops their synchronization rows.
     *
     * <p><b>The graph is one of those derived stores</b> (requirement section 3, "the delete cascade
     * includes Neo4j from M7"). It joins here rather than getting a cascade of its own for the reason this
     * class exists at all: a document deletion, a version rebuild and the retrieval self healing pass all
     * mean the same thing - these chunk ids are gone - and a second definition of "everywhere" would
     * eventually cover one of the three and not the others. A deployment with no graph gets the disabled
     * store and this line does nothing.
     *
     * @param kbId     knowledge base business id
     * @param chunkIds chunk ids to remove, ignored when empty
     */
    public void remove(String kbId, List<String> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        for (int start = 0; start < chunkIds.size(); start += BATCH_SIZE) {
            List<String> batch = chunkIds.subList(start, Math.min(chunkIds.size(), start + BATCH_SIZE));
            indexAliasManager.deleteEverywhere(kbId, batch);
            chunkIndexSyncMapper.delete(new LambdaQueryWrapper<ChunkIndexSync>()
                    .in(ChunkIndexSync::getChunkId, batch));
        }
        graphStore.deleteChunks(kbId, chunkIds);
        log.info("engine chunks removed, kbId={}, count={}", kbId, chunkIds.size());
    }

    /**
     * Removes chunks from the engines once the surrounding transaction has committed.
     *
     * <p><b>Why the deletion must not join the transaction.</b> Deleting an engine document is
     * irreversible, while every MySQL statement around it is not. Running both together means a failure
     * later in the transaction rolls the chunk rows back into existence while the engine copies stay
     * deleted, and the resulting hole is invisible to the compensation scan: it looks for rows marked
     * FAILED or stuck PENDING, and a restored row still says SYNCED. Waiting for the commit inverts the
     * failure into the harmless direction — the engine may briefly keep a document MySQL no longer owns,
     * which the retrieval path detects on the next hit and repairs itself.
     *
     * <p>Outside a transaction the removal simply runs inline, which is what the indexing pipeline needs
     * since it has no transaction to wait for.
     *
     * @param kbId     knowledge base business id
     * @param chunkIds chunk ids to remove, ignored when empty
     */
    public void removeAfterCommit(String kbId, List<String> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            remove(kbId, chunkIds);
            return;
        }
        List<String> pending = List.copyOf(chunkIds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    remove(kbId, pending);
                } catch (Exception e) {
                    // The rows are already gone from the fact source, so the caller has nothing left to
                    // undo; retrieval self healing removes whatever survived in the engines.
                    log.error("post commit engine chunk cleanup failed, errorCode={}, kbId={}, count={}",
                            ErrorCode.INTERNAL_ERROR, kbId, pending.size(), e);
                }
            }
        });
    }

    /**
     * Same as {@link #remove(String, List)} but off the caller thread.
     *
     * <p>Used by the retrieval self healing path: a search must not pay for the repair of an
     * inconsistency it merely happened to notice, and a failure to repair must not fail the search,
     * which is why the exception is swallowed after being logged.
     *
     * @param kbId     knowledge base business id
     * @param chunkIds chunk ids to remove
     */
    @Async(AsyncConfig.INDEX_EXECUTOR)
    public void removeAsync(String kbId, List<String> chunkIds) {
        try {
            remove(kbId, chunkIds);
        } catch (Exception e) {
            log.error("async engine chunk cleanup failed, errorCode={}, kbId={}, count={}",
                    ErrorCode.INTERNAL_ERROR, kbId, chunkIds.size(), e);
        }
    }
}
