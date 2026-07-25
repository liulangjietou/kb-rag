package io.kbrag.api.controller;

import io.kbrag.api.dto.SearchRequest;
import io.kbrag.api.dto.SearchResponse;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.config.KbProperties;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retrieval debug endpoint of the console.
 */
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final RetrievalService retrievalService;
    private final KbProperties properties;

    /**
     * Runs one retrieval call against a knowledge base.
     *
     * @param kbId    knowledge base business id
     * @param request retrieval payload
     * @return nodes plus degradation markers
     */
    @PostMapping("/api/v1/kb/{kbId}/search")
    public Result<SearchResponse> search(@PathVariable String kbId,
                                         @Valid @RequestBody SearchRequest request) {
        KbProperties.Retrieval config = properties.getRetrieval();
        int recallTopK = clamp(request.recallTopK(), config.getDefaultRecallTopK(), config.getMaxRecallTopK());
        int topN = clamp(request.topN(), config.getDefaultTopN(), config.getMaxTopN());
        return Result.success(SearchResponse.from(
                retrievalService.search(kbId, request.query(), recallTopK, topN)));
    }

    /**
     * Applies the configured default and upper bound to an optional tuning parameter.
     *
     * @param requested requested value, {@code null} for the default
     * @param fallback  configured default
     * @param maximum   configured upper bound
     * @return effective value
     */
    private int clamp(Integer requested, int fallback, int maximum) {
        if (requested == null || requested < 1) {
            return fallback;
        }
        return Math.min(requested, maximum);
    }
}
