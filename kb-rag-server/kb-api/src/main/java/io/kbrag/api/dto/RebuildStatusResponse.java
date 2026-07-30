package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.index.RebuildService;

/**
 * Rebuild catch-up view of a knowledge base.
 *
 * <p>The console polls this instead of remembering what it submitted. A rebuild outlives the page that
 * started it - it runs on the index executor and reports through the database - so the only state that
 * survives a navigation, a refresh or a second operator opening the same base is the one derived here.
 *
 * @param staleCount      documents still awaiting a rebuild under the current configuration
 * @param inProgressCount those currently running through the pipeline
 * @param failedCount     those whose rebuild failed and need an operator
 *
 * @author owlzhangfq@gmail.com
 */
public record RebuildStatusResponse(
        @JsonProperty("stale_count") int staleCount,
        @JsonProperty("in_progress_count") int inProgressCount,
        @JsonProperty("failed_count") int failedCount) {

    /**
     * Maps the application view onto its response.
     *
     * @param status rebuild catch-up status
     * @return view
     */
    public static RebuildStatusResponse from(RebuildService.RebuildStatus status) {
        return new RebuildStatusResponse(status.staleCount(), status.inProgressCount(), status.failedCount());
    }
}
