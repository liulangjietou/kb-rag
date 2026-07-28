package io.kbrag.domain.enums;

/**
 * Outcome of one sync attempt of a registered web source, the M12 contract section 3.4.
 *
 * <p>UNCHANGED and SKIPPED are successes of a kind - the fetch pipeline worked and decided that
 * writing anything would be wrong - and they are distinct states because the operator reacts
 * differently to "the page did not change" and "your document sits in the recycle bin".
 *
 * @author owlzhangfq@gmail.com
 */
public enum WebSourceFetchStatus {

    /** The page was fetched and fed into the upload chain. */
    SUCCESS,

    /** The page was fetched but its content hash matches the previous fetch; nothing was written. */
    UNCHANGED,

    /** The fetch was not attempted or its result discarded, for a reason recorded in last_error. */
    SKIPPED,

    /** The fetch or the intake failed, for a reason recorded in last_error. */
    FAILED
}
