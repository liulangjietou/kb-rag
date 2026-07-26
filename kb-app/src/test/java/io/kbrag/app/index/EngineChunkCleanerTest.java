package io.kbrag.app.index;

import io.kbrag.domain.mapper.ChunkIndexSyncMapper;
import io.kbrag.domain.port.GraphStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Covers when the engine side deletion happens relative to the database transaction, which is the whole
 * point of the collaborator: the deletion is irreversible and the transaction around it is not.
 *
 * @author owlzhangfq@gmail.com
 */
class EngineChunkCleanerTest {

    private static final String KB_ID = "kb_test";
    private static final List<String> CHUNK_IDS = List.of("ck_1", "ck_2");

    private IndexAliasManager indexAliasManager;
    private ChunkIndexSyncMapper chunkIndexSyncMapper;
    private GraphStore graphStore;
    private EngineChunkCleaner cleaner;

    @BeforeEach
    void setUp() {
        indexAliasManager = mock(IndexAliasManager.class);
        chunkIndexSyncMapper = mock(ChunkIndexSyncMapper.class);
        graphStore = mock(GraphStore.class);
        cleaner = new EngineChunkCleaner(indexAliasManager, chunkIndexSyncMapper, graphStore);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldHoldTheEngineDeletionUntilTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        cleaner.removeAfterCommit(KB_ID, CHUNK_IDS);

        // Still inside the transaction: a rollback from here on must leave the engines untouched,
        // because restoring the chunk rows cannot restore a deleted engine document.
        verify(indexAliasManager, never()).deleteEverywhere(anyString(), any());

        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(indexAliasManager, times(1)).deleteEverywhere(KB_ID, CHUNK_IDS);
        verify(chunkIndexSyncMapper, times(1)).delete(any());
    }

    @Test
    void shouldNeverDeleteWhenTheTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();

        cleaner.removeAfterCommit(KB_ID, CHUNK_IDS);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(org.springframework.transaction.support
                        .TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(indexAliasManager, never()).deleteEverywhere(anyString(), any());
    }

    @Test
    void shouldRunInlineOutsideATransaction() {
        // The indexing pipeline runs on an executor thread with no transaction to wait for.
        cleaner.removeAfterCommit(KB_ID, CHUNK_IDS);

        verify(indexAliasManager, times(1)).deleteEverywhere(KB_ID, CHUNK_IDS);
    }

    @Test
    void shouldIgnoreAnEmptyChunkList() {
        cleaner.removeAfterCommit(KB_ID, List.of());
        cleaner.remove(KB_ID, List.of());

        verify(indexAliasManager, never()).deleteEverywhere(anyString(), any());
        verify(chunkIndexSyncMapper, never()).delete(any());
    }

    @Test
    void shouldSwallowFailuresOfTheSelfHealingPath() {
        org.mockito.Mockito.doThrow(new IllegalStateException("engine unreachable"))
                .when(indexAliasManager).deleteEverywhere(anyString(), any());

        // A search merely noticed the inconsistency; failing to repair it must not fail the search.
        cleaner.removeAsync(KB_ID, CHUNK_IDS);

        verify(indexAliasManager, times(1)).deleteEverywhere(KB_ID, CHUNK_IDS);
    }

    @Test
    void shouldRemoveTheChunksFromTheKnowledgeGraphAsWell() {
        // Requirement section 3: from M7 the delete cascade includes Neo4j. It rides this collaborator so a
        // document deletion, a version rebuild and the retrieval self healing pass provably clear the same
        // set of derived stores.
        cleaner.remove(KB_ID, CHUNK_IDS);

        verify(graphStore).deleteChunks(KB_ID, CHUNK_IDS);
    }

    @Test
    void shouldNotTouchTheGraphWhenThereIsNothingToRemove() {
        cleaner.remove(KB_ID, List.of());

        verify(graphStore, never()).deleteChunks(anyString(), any());
    }
}
