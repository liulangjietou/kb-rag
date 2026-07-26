package io.kbrag.domain.port;

import java.util.Set;

/**
 * Tells which document versions must never be archived because something still points at them.
 *
 * <p>The requirement forbids cleaning up a version that is referenced by the index snapshot of an
 * application version that has not been retired. Application versions arrive in a later milestone, so
 * this port exists to keep the retention logic final: the cleanup asks the question now and answers
 * it with an empty set, and the milestone that introduces snapshots only has to supply another
 * implementation.
 *
 * @author owlzhangfq@gmail.com
 */
public interface VersionPinChecker {

    /**
     * Versions of one document that are pinned by a live reference.
     *
     * @param docId document business id
     * @return pinned document version ids, empty when nothing pins any version
     */
    Set<String> pinnedVersionIds(String docId);
}
