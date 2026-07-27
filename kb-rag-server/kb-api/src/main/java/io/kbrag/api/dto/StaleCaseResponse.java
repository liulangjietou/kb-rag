package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.eval.EvalCaseStalenessService;

import java.util.List;

/**
 * One row of the evidence review workbench, requirement section 4.5 / web contract addendum.
 *
 * @param evalCase       the stale case, full field view
 * @param staleEvidences evidence that no longer matches, each with up to 3 replacement candidates
 *
 * @author owlzhangfq@gmail.com
 */
public record StaleCaseResponse(
        @JsonProperty("case") EvalCaseResponse evalCase,
        @JsonProperty("stale_evidences") List<StaleEvidenceView> staleEvidences) {

    /**
     * Maps a service detail onto its response.
     *
     * @param detail stale case detail
     * @return response
     */
    public static StaleCaseResponse from(EvalCaseStalenessService.StaleCaseDetail detail) {
        return new StaleCaseResponse(
                EvalCaseResponse.from(detail.evalCase()),
                detail.staleEvidences().stream().map(StaleEvidenceView::from).toList());
    }

    /**
     * One evidence that no longer matches, together with its replacement candidates.
     *
     * @param evidence   the stored, now unmatched evidence
     * @param candidates up to 3 candidate chunks, ranked by overlap ratio
     */
    public record StaleEvidenceView(EvalEvidenceResponse evidence, List<CandidateView> candidates) {

        private static StaleEvidenceView from(EvalCaseStalenessService.StaleEvidenceDetail detail) {
            return new StaleEvidenceView(
                    EvalEvidenceResponse.from(detail.evidence()),
                    detail.candidates().stream().map(CandidateView::from).toList());
        }
    }

    /**
     * One replacement candidate.
     *
     * @param docId        candidate's owning document
     * @param chunkId      candidate chunk business id
     * @param span         candidate chunk text, usable verbatim as the replacement span
     * @param overlapRatio overlap ratio against the stale span
     */
    public record CandidateView(
            @JsonProperty("doc_id") String docId,
            @JsonProperty("chunk_id") String chunkId,
            String span,
            @JsonProperty("overlap_ratio") double overlapRatio) {

        private static CandidateView from(EvalCaseStalenessService.CandidateMatch match) {
            return new CandidateView(match.docId(), match.chunkId(), match.span(), match.overlapRatio());
        }
    }
}
