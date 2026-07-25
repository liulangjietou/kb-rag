package io.kbrag.api.controller;

import io.kbrag.api.dto.SearchRequest;
import io.kbrag.api.dto.SearchResponse;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.common.api.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retrieval debug endpoint of the console.
 *
 * <p>Parameter defaulting and clamping live in the application layer rather than here, because the
 * effective value comes from three configuration layers the transport has no business knowing about;
 * this class only validates the shape of the payload.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final RetrievalService retrievalService;

    /**
     * Runs one retrieval call against a knowledge base.
     *
     * @param kbId    knowledge base business id
     * @param request retrieval payload
     * @return nodes, degradation markers and the applied parameter summary
     */
    @PostMapping("/api/v1/kb/{kbId}/search")
    public Result<SearchResponse> search(@PathVariable String kbId,
                                         @Valid @RequestBody SearchRequest request) {
        return Result.success(SearchResponse.from(retrievalService.search(kbId, request.toCommand())));
    }
}
