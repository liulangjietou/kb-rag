package io.kbrag.domain.port;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tells which document versions must never be archived because something still points at them.
 *
 * <p>The requirement forbids cleaning up a version that is referenced by the index snapshot of an
 * application version that has not been retired (requirement sections 4.1 and 4.7). Archiving drops the
 * chunks from MySQL, and MySQL is the fact source every retrieval reads its text from, so an archived
 * version would leave a snapshot able to recall chunk ids whose text no longer exists - the rollback
 * promise of section 4.7 would be honoured by an empty answer.
 *
 * <p><b>Why the references, not just a flag.</b> The console has to explain a disabled cleanup button, so
 * the primitive is the reference list and the boolean question is derived from it. One implementation, one
 * query, no chance of the two answers disagreeing.
 *
 * @author owlzhangfq@gmail.com
 */
public interface VersionPinChecker {

    /**
     * References onto the versions of one document.
     *
     * @param docId document business id
     * @return application version business ids per pinned document version id, empty when nothing pins
     */
    Map<String, List<String>> pinnedBy(String docId);

    /**
     * Versions of one document that are pinned by a live reference.
     *
     * @param docId document business id
     * @return pinned document version ids, empty when nothing pins any version
     */
    default Set<String> pinnedVersionIds(String docId) {
        return pinnedBy(docId).keySet();
    }
}
