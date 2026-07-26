package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How many document versions one knowledge base contributed to the frozen visibility set of an application
 * version, requirement section 4.7.
 *
 * <p><b>The count, not the ids.</b> The console renders "3 document versions frozen" next to the physical index
 * name; shipping the identifiers instead would grow the version list response with the corpus size of every
 * linked base to display a number.
 *
 * @param kbId         knowledge base business id
 * @param versionCount document versions the release froze for this base
 *
 * @author owlzhangfq@gmail.com
 */
public record VisibleVersionCountResponse(
        @JsonProperty("kb_id") String kbId,
        @JsonProperty("version_count") int versionCount) {
}
