package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.retrieval.AppliedInfo;

import java.util.List;

/**
 * What the pipeline actually did, shown in the debug console header.
 *
 * @param rewriteUsedQuery   query the recall stage ran with
 * @param fusionMode         fusion strategy that produced the ordering
 * @param thresholdAppliedOn score the threshold acted on, {@code none} when nothing was filtered
 * @param routedKbIds        knowledge bases this call searched; every linked base when routing is off or
 *                           fell back
 *
 * @author owlzhangfq@gmail.com
 */
public record AppliedResponse(
        @JsonProperty("rewrite_used_query") String rewriteUsedQuery,
        @JsonProperty("fusion_mode") String fusionMode,
        @JsonProperty("threshold_applied_on") String thresholdAppliedOn,
        @JsonProperty("routed_kb_ids") List<String> routedKbIds) {

    /**
     * Maps an application view onto the transport shape.
     *
     * @param applied application view
     * @return transport response
     */
    public static AppliedResponse from(AppliedInfo applied) {
        return new AppliedResponse(
                applied.getRewriteUsedQuery(),
                applied.getFusionMode(),
                applied.getThresholdAppliedOn(),
                applied.getRoutedKbIds() == null ? List.of() : applied.getRoutedKbIds());
    }
}
