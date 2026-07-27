package io.kbrag.domain.service;

import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.DocumentVersionStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which versions the retention window gives up. The selection is the dangerous half of the
 * cleanup: an active or pinned version in the result would delete the chunks something is serving.
 *
 * @author owlzhangfq@gmail.com
 */
class VersionRetentionPolicyTest {

    private static final String ACTIVE_VERSION_ID = "dv_active";
    private static final int RETAIN_THREE = 3;

    private final VersionRetentionPolicy policy = new VersionRetentionPolicy();

    @Test
    void shouldKeepTheNewestReadyVersionsAndGiveUpTheRest() {
        List<DocumentVersion> versions = List.of(
                active(),
                ready(1L, "dv_oldest"),
                ready(2L, "dv_old"),
                ready(3L, "dv_recent"),
                ready(4L, "dv_newest"));

        List<DocumentVersion> archivable = policy.selectArchivable(versions, ACTIVE_VERSION_ID,
                RETAIN_THREE, Set.of());

        assertEquals(List.of("dv_oldest"),
                archivable.stream().map(DocumentVersion::getVersionId).toList());
    }

    @Test
    void shouldNeverSelectTheActiveVersion() {
        List<DocumentVersion> versions = List.of(active(), ready(1L, "dv_one"));

        List<DocumentVersion> archivable = policy.selectArchivable(versions, ACTIVE_VERSION_ID, 1, Set.of());

        assertTrue(archivable.isEmpty());
    }

    @Test
    void shouldNeverSelectAPinnedVersion() {
        // A version referenced by a live index snapshot must survive the window, otherwise the snapshot
        // it backs starts returning nothing.
        List<DocumentVersion> versions = List.of(
                active(), ready(1L, "dv_pinned"), ready(2L, "dv_free"), ready(3L, "dv_newer"));

        List<DocumentVersion> archivable = policy.selectArchivable(versions, ACTIVE_VERSION_ID, 1,
                Set.of("dv_pinned"));

        assertEquals(List.of("dv_free"), archivable.stream().map(DocumentVersion::getVersionId).toList());
    }

    @Test
    void shouldIgnoreVersionsThatHaveNothingToClean() {
        // A build in progress, a failed build and an already archived version are all outside the window:
        // none of them owns a generation worth keeping, and re-archiving would log a cleanup of nothing.
        List<DocumentVersion> versions = List.of(
                active(),
                status(1L, "dv_building", DocumentVersionStatus.BUILDING),
                status(2L, "dv_failed", DocumentVersionStatus.BUILD_FAILED),
                status(3L, "dv_archived", DocumentVersionStatus.ARCHIVED),
                ready(4L, "dv_ready"));

        List<DocumentVersion> archivable = policy.selectArchivable(versions, ACTIVE_VERSION_ID, 0, Set.of());

        assertEquals(List.of("dv_ready"), archivable.stream().map(DocumentVersion::getVersionId).toList());
    }

    @Test
    void shouldReturnNothingForADocumentWithoutVersions() {
        assertTrue(policy.selectArchivable(List.of(), ACTIVE_VERSION_ID, RETAIN_THREE, Set.of()).isEmpty());
    }

    @Test
    void shouldOrderByCreationTimeSoTheOldestGoesFirst() {
        // Deliberately inserted out of order: the selection has to depend on the timestamp and not on the
        // order the rows happen to come back in.
        List<DocumentVersion> versions = List.of(
                active(),
                ready(9L, "dv_newest"),
                ready(1L, "dv_oldest"),
                ready(5L, "dv_middle"));

        List<DocumentVersion> archivable = policy.selectArchivable(versions, ACTIVE_VERSION_ID, 1, Set.of());

        assertEquals(List.of("dv_middle", "dv_oldest"),
                archivable.stream().map(DocumentVersion::getVersionId).toList());
    }

    private DocumentVersion active() {
        DocumentVersion version = status(100L, ACTIVE_VERSION_ID, DocumentVersionStatus.ACTIVE);
        version.setActiveFlag(1);
        return version;
    }

    private DocumentVersion ready(long minutesFromEpoch, String versionId) {
        return status(minutesFromEpoch, versionId, DocumentVersionStatus.READY);
    }

    private DocumentVersion status(long minutesFromEpoch, String versionId,
                                   DocumentVersionStatus status) {
        DocumentVersion version = new DocumentVersion();
        version.setId(minutesFromEpoch);
        version.setVersionId(versionId);
        version.setDocId("doc_1");
        version.setStatus(status);
        version.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(minutesFromEpoch));
        return version;
    }
}
