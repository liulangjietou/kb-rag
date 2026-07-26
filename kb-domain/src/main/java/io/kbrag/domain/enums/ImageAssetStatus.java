package io.kbrag.domain.enums;

/**
 * Lifecycle of the textual proxy of an image asset.
 *
 * <p>SKIPPED and FAILED are kept apart on purpose: skipped means no vision model was configured and
 * the deployment is behaving as designed, failed means a configured model refused the call. Only the
 * second one is worth retrying, and the distinction is what lets a later backfill pass pick the right
 * rows.
 *
 * @author owlzhangfq@gmail.com
 */
public enum ImageAssetStatus {

    /** Stored in object storage, textual proxy not produced yet. */
    PENDING,

    /** Textual proxy available. */
    DONE,

    /** No vision provider configured, the image carries no text. */
    SKIPPED,

    /** The vision provider was configured but the call failed. */
    FAILED
}
