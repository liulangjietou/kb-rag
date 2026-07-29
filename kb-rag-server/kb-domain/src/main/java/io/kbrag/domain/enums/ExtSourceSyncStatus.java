package io.kbrag.domain.enums;

/**
 * Source level outcome of one external source sync pass, the M14 contract section 2.2.
 *
 * <p>PARTIAL exists because one pass touches many objects: some may ingest while others fail or
 * the listing was truncated at the per-source cap, and the operator needs to know the pass both
 * did work and left work behind - neither SUCCESS nor FAILED says that.
 *
 * @author owlzhangfq@gmail.com
 */
public enum ExtSourceSyncStatus {

    /** Every listed object either ingested, was unchanged or was legitimately skipped. */
    SUCCESS,

    /** The pass ran but at least one object failed or the listing hit the per-source cap. */
    PARTIAL,

    /** A source level error stopped the pass before objects were visited, see last_error. */
    FAILED
}
