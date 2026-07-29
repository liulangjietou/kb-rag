package io.kbrag.domain.enums;

/**
 * Per object outcome of one external source sync, the M14 contract section 2.2.
 *
 * <p>The four states mirror {@link WebSourceFetchStatus} on purpose: an object is a page with an
 * etag instead of a body hash, and the operator reads both tables with the same eye.
 *
 * @author owlzhangfq@gmail.com
 */
public enum ExtSourceItemStatus {

    /** The object was fetched and fed into the upload chain. */
    SUCCESS,

    /** The object's etag matches the previous ingest; nothing was written. */
    UNCHANGED,

    /** The ingest was not attempted or its result discarded, for a reason recorded in last_error. */
    SKIPPED,

    /** The fetch or the intake failed, for a reason recorded in last_error. */
    FAILED
}
