package io.kbrag.api.controller;

import io.kbrag.api.dto.SearchRequest;
import io.kbrag.api.dto.SearchResponse;
import io.kbrag.app.insight.SearchInsightService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.app.retrieval.SearchOutcome;
import io.kbrag.common.api.Result;
import io.kbrag.common.context.RequestIdHolder;
import io.kbrag.domain.enums.InsightSource;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Retrieval debug endpoint of the console.
 *
 * <p>Parameter defaulting and clamping live in the application layer rather than here, because the
 * effective value comes from three configuration layers the transport has no business knowing about;
 * this class only validates the shape of the payload.
 *
 * <p>Also the console side insight recording point, the M10 contract section 2.2: recording sits at
 * the API boundary rather than inside the retrieval pipeline, so evaluation runs and gate reruns -
 * which reuse the same pipeline - never appear in the miss report. The write is asynchronous and a
 * failed retrieval records nothing. The M13 search metric shares this boundary and this reasoning.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final RetrievalService retrievalService;
    private final SearchInsightService searchInsightService;
    private final KbMetrics kbMetrics;

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
        long startedAt = System.currentTimeMillis();
        SearchOutcome outcome = retrievalService.search(kbId, request.toCommand());
        int resultCount = CollectionUtils.isEmpty(outcome.getNodes()) ? 0 : outcome.getNodes().size();
        kbMetrics.recordSearch(InsightSource.CONSOLE, System.currentTimeMillis() - startedAt,
                resultCount, outcome.getDegraded());
        searchInsightService.recordAsync(SearchInsightService.InsightRecord.builder()
                .source(InsightSource.CONSOLE)
                .kbIds(List.of(kbId))
                .query(request.query())
                .resultCount(resultCount)
                .topScore(CollectionUtils.isEmpty(outcome.getNodes())
                        ? null : outcome.getNodes().get(0).getScore())
                .degraded(outcome.getDegraded())
                .requestId(RequestIdHolder.get())
                .build());
        return Result.success(SearchResponse.from(outcome));
    }
}
