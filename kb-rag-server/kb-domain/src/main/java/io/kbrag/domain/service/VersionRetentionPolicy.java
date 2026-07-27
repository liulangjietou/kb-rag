package io.kbrag.domain.service;

import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.DocumentVersionStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Chooses which non active versions of a document lose their chunks.
 *
 * <p>Kept as a pure function so the retention window can be reasoned about without a database: the
 * decision is the dangerous part of the cleanup, not the deletion itself, and it has to be provable
 * that an active or pinned version can never appear in the result.
 *
 * <p>Only READY versions are candidates. A version that is still building has nothing to clean up, a
 * failed build has no generation worth keeping, and an archived version has already been through
 * here — re-selecting it would delete its chunks a second time and log a cleanup that removed nothing.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class VersionRetentionPolicy {

    /**
     * Selects the versions that must be archived.
     *
     * @param versions        every version row of the document
     * @param activeVersionId currently active version, never archived
     * @param retainCount     number of newest non active READY versions to keep
     * @param pinnedVersionIds versions a live reference forbids archiving
     * @return versions to archive, newest first among the ones that fell outside the window
     */
    public List<DocumentVersion> selectArchivable(List<DocumentVersion> versions, String activeVersionId,
                                                  int retainCount, Set<String> pinnedVersionIds) {
        if (CollectionUtils.isEmpty(versions)) {
            return List.of();
        }
        List<DocumentVersion> candidates = new ArrayList<>(versions.size());
        for (DocumentVersion version : versions) {
            if (version.getStatus() != DocumentVersionStatus.READY) {
                continue;
            }
            if (version.getVersionId().equals(activeVersionId)) {
                continue;
            }
            if (pinnedVersionIds != null && pinnedVersionIds.contains(version.getVersionId())) {
                log.info("version pinned by a live reference, skip archiving, versionId={}",
                        version.getVersionId());
                continue;
            }
            candidates.add(version);
        }
        // Newest first, so the window keeps the versions an operator is most likely to roll back to.
        // The row id breaks the tie because two versions created in the same second are indistinguishable
        // by timestamp and an unstable order would archive a different one on every pass.
        candidates.sort(Comparator.comparing(VersionRetentionPolicy::createdAtOf)
                .thenComparing(VersionRetentionPolicy::idOf)
                .reversed());
        if (candidates.size() <= retainCount) {
            return List.of();
        }
        return List.copyOf(candidates.subList(retainCount, candidates.size()));
    }

    /**
     * Creation timestamp of a version, substituting the epoch when it was never recorded so a row
     * without a timestamp sorts as the oldest instead of breaking the comparison.
     *
     * @param version version row
     * @return creation timestamp
     */
    private static LocalDateTime createdAtOf(DocumentVersion version) {
        return version.getCreatedAt() == null ? LocalDateTime.MIN : version.getCreatedAt();
    }

    /**
     * Row id of a version, substituting zero for a row that was never persisted.
     *
     * @param version version row
     * @return surrogate key
     */
    private static Long idOf(DocumentVersion version) {
        return version.getId() == null ? 0L : version.getId();
    }
}
