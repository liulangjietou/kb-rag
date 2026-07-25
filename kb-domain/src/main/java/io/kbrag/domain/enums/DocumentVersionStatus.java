package io.kbrag.domain.enums;

/**
 * Lifecycle of a single document version.
 *
 * <p>BUILDING -&gt; (BUILD_FAILED | READY) -&gt; ACTIVE, an active version steps back to READY when a
 * newer version is activated and is ARCHIVED once it falls outside the retention window.
 */
public enum DocumentVersionStatus {

    /** Pipeline is producing chunks for this version. */
    BUILDING,

    /** Pipeline failed, the previous active version keeps serving traffic. */
    BUILD_FAILED,

    /** Chunks are complete, the version can be activated instantly. */
    READY,

    /** Version currently pointed at by {@code t_kb_document.current_version_id}. */
    ACTIVE,

    /** Chunks were cleaned up, only the original file and parse artifacts remain. */
    ARCHIVED
}
