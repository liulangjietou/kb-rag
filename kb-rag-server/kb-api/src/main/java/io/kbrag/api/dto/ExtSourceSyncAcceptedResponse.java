package io.kbrag.api.dto;

/**
 * Acknowledgement of an asynchronously accepted external source sync, the M14 contract
 * section 2.3: the scan runs off the request thread, its outcome lands on the source row.
 *
 * @param accepted always {@code true}; a sync that cannot be accepted fails the request instead
 *
 * @author owlzhangfq@gmail.com
 */
public record ExtSourceSyncAcceptedResponse(boolean accepted) {

    /**
     * Builds the single accepted acknowledgement.
     *
     * @return accepted acknowledgement
     */
    public static ExtSourceSyncAcceptedResponse of() {
        return new ExtSourceSyncAcceptedResponse(true);
    }
}
