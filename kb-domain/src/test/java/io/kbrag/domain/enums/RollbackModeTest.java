package io.kbrag.domain.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the rollback cost verdict, which the console turns into either an immediate switch or a
 * progress dialog. Advertising the wrong one activates a version that owns no chunk.
 *
 * @author owlzhangfq@gmail.com
 */
class RollbackModeTest {

    @Test
    void shouldSwitchInstantlyToAReadyVersionThatStillOwnsItsChunks() {
        assertEquals(RollbackMode.INSTANT, RollbackMode.resolve(DocumentVersionStatus.READY, 12L));
        assertFalse(RollbackMode.resolve(DocumentVersionStatus.READY, 12L).needsRebuild());
    }

    @Test
    void shouldRebuildAnArchivedVersion() {
        assertEquals(RollbackMode.REBUILD, RollbackMode.resolve(DocumentVersionStatus.ARCHIVED, 0L));
        assertTrue(RollbackMode.resolve(DocumentVersionStatus.ARCHIVED, 0L).needsRebuild());
    }

    @Test
    void shouldRebuildAReadyVersionWhoseChunksAreGone() {
        // The status alone is not enough: an earlier cleanup or a manual repair can leave a READY row
        // behind with nothing to serve, and switching onto it would return an empty document.
        assertEquals(RollbackMode.REBUILD, RollbackMode.resolve(DocumentVersionStatus.READY, 0L));
    }

    @Test
    void shouldTreatTheActiveVersionAsInstant() {
        assertEquals(RollbackMode.INSTANT, RollbackMode.resolve(DocumentVersionStatus.ACTIVE, 3L));
    }

    @Test
    void shouldRebuildAVersionThatNeverFinishedBuilding() {
        assertEquals(RollbackMode.REBUILD, RollbackMode.resolve(DocumentVersionStatus.BUILDING, 0L));
        assertEquals(RollbackMode.REBUILD, RollbackMode.resolve(DocumentVersionStatus.BUILD_FAILED, 2L));
    }
}
