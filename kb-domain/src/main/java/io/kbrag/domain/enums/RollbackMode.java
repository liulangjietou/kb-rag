package io.kbrag.domain.enums;

/**
 * Cost of switching the active version of a document to a given target.
 *
 * <p>The distinction exists because the retention policy eventually deletes the chunks of old
 * versions while keeping their original file and their parse artifact. A version that still owns its
 * chunks is a pointer switch; a version whose chunks were cleaned up has to be produced again before
 * it can serve anything. The console needs to know which of the two it is about to trigger, so the
 * decision is expressed once here instead of being re-derived by every caller.
 *
 * @author owlzhangfq@gmail.com
 */
public enum RollbackMode {

    /** Chunks are still present, activation is an atomic pointer switch. */
    INSTANT,

    /** Chunks were cleaned up, activation needs a rebuild from the stored parse artifact first. */
    REBUILD;

    /**
     * Resolves the mode of one target version.
     *
     * <p>Both conditions are required rather than the status alone: a READY version whose chunks were
     * removed by an earlier cleanup, a failed build, or a manual repair would otherwise be advertised
     * as an instant rollback and would switch the document onto an empty version.
     *
     * @param status     lifecycle state of the target version
     * @param chunkCount number of chunk rows the target version still owns
     * @return {@link #INSTANT} when the version can serve traffic as it is, {@link #REBUILD} otherwise
     */
    public static RollbackMode resolve(DocumentVersionStatus status, long chunkCount) {
        boolean servable = status == DocumentVersionStatus.READY || status == DocumentVersionStatus.ACTIVE;
        return servable && chunkCount > 0 ? INSTANT : REBUILD;
    }

    /**
     * Tells whether activating the target needs a rebuild task.
     *
     * @return {@code true} for {@link #REBUILD}
     */
    public boolean needsRebuild() {
        return this == REBUILD;
    }
}
