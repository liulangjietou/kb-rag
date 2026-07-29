package io.kbrag.api.dto;

import io.kbrag.domain.model.HealthStatus;

/**
 * Connection probe view of one external source, the M14 contract section 2.3.
 *
 * @param up     {@code true} when the store answered and the bucket exists
 * @param detail short probe detail shown in the console
 *
 * @author owlzhangfq@gmail.com
 */
public record ExtSourceTestResponse(boolean up, String detail) {

    /**
     * Maps a probe outcome onto its view.
     *
     * @param status probe outcome
     * @return view
     */
    public static ExtSourceTestResponse from(HealthStatus status) {
        return new ExtSourceTestResponse(status.isUp(), status.getDetail());
    }
}
