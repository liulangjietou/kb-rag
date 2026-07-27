package io.kbrag.api.controller;

import io.kbrag.api.dto.RetrievalFeedbackRequest;
import io.kbrag.common.api.Result;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Good/bad feedback on one debug page result, requirement section 4.5.
 *
 * <p>See the M4b contract section 2 and {@link RetrievalFeedbackRequest} for why this endpoint does
 * not persist anything: a {@code GOOD} verdict is already covered by
 * {@code POST /eval-datasets/{datasetId}/cases/from-retrieval}, which the debug page calls once an
 * operator names a target data set, and a standalone {@code BAD} table has no consumer this milestone.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
public class RetrievalFeedbackController {

    /**
     * Records a feedback signal without persisting it.
     *
     * @param request feedback payload
     * @return empty payload
     */
    @PostMapping("/api/v1/retrieval-feedback")
    public Result<Void> feedback(@Valid @RequestBody RetrievalFeedbackRequest request) {
        log.info("retrieval feedback received, kbId={}, chunkId={}, verdict={}",
                request.kbId(), request.chunkId(), request.verdict());
        return Result.success(null);
    }
}
