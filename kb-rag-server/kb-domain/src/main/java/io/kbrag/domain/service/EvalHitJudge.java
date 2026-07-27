package io.kbrag.domain.service;

import io.kbrag.domain.model.CaseJudgment;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one evaluation case's evidence against one run's top {@code K} into a {@link CaseJudgment},
 * requirement section 4.6.
 *
 * <p>A pure function over plain text and identifiers, deliberately independent from
 * {@code RetrievalNodeView}: the evaluation runner is responsible for turning a run's returned nodes
 * into {@code candidateTextsPerRank} (children texts when parent child splitting is on, the node's own
 * text otherwise, requirement section 4.6 "aggregate coverage on the child set") or into
 * {@code docIdPerRank}, so this class can be unit tested with hand built lists instead of a mocked
 * retrieval pipeline.
 *
 * <p><b>Case level hit versus per rank relevance.</b> {@link CaseJudgment#hit()} answers "was this
 * case answered by the top {@code K}", using the same aggregate coverage union the report calls a
 * hit; it is computed incrementally over growing prefixes of the ranking so {@code hitRank} is the
 * smallest prefix at which any evidence first crosses the threshold. {@link
 * CaseJudgment#relevancePerRank()} answers a different, per item question - "does this one ranked unit
 * carry any relation to the evidence at all" - which is what {@code Precision@K} and {@code NDCG@K}
 * need and what the aggregate, unioned hit condition cannot supply on its own.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class EvalHitJudge {

    private final OverlapRatioCalculator overlapRatioCalculator;

    /**
     * Judges a span anchored case.
     *
     * @param spans                evidence spans of the case, one per declared evidence
     * @param candidateTextsPerRank per rank candidate texts, in rank order, one list per returned unit;
     *                              a two level knowledge base supplies the child texts of that unit
     * @param threshold             aggregate coverage threshold, {@code kb.eval.overlap-threshold}
     * @return judgment against this case's declared evidences
     */
    public CaseJudgment judgeSpanCase(List<String> spans, List<List<String>> candidateTextsPerRank,
                                      double threshold) {
        int k = candidateTextsPerRank.size();
        int totalEvidence = CollectionUtils.size(spans);
        int evidenceHits = 0;
        Integer caseHitRank = null;
        for (String span : spans) {
            Integer firstHitRank = firstHitRank(span, candidateTextsPerRank, threshold);
            if (firstHitRank != null) {
                evidenceHits++;
                caseHitRank = caseHitRank == null ? firstHitRank : Math.min(caseHitRank, firstHitRank);
            }
        }
        List<Boolean> relevancePerRank = new ArrayList<>(k);
        for (List<String> rankTexts : candidateTextsPerRank) {
            relevancePerRank.add(anyPositiveOverlap(rankTexts, spans));
        }
        return new CaseJudgment(caseHitRank != null, caseHitRank, evidenceHits, totalEvidence,
                relevancePerRank, totalEvidence);
    }

    /**
     * Judges a document anchored case, requirement section 4.5 "anchor type", no overlap computation.
     *
     * @param evidenceDocIds relevant document ids the case declares
     * @param docIdPerRank   document id of each returned unit, in rank order, {@code null} for a rank
     *                       with no returned unit
     * @return judgment against this case's declared relevant documents
     */
    public CaseJudgment judgeDocumentCase(List<String> evidenceDocIds, List<String> docIdPerRank) {
        int k = docIdPerRank.size();
        int totalRelevant = CollectionUtils.size(evidenceDocIds);
        int hits = 0;
        Integer caseHitRank = null;
        for (String docId : evidenceDocIds) {
            for (int rank = 1; rank <= k; rank++) {
                if (docId != null && docId.equals(docIdPerRank.get(rank - 1))) {
                    hits++;
                    caseHitRank = caseHitRank == null ? rank : Math.min(caseHitRank, rank);
                    break;
                }
            }
        }
        List<Boolean> relevancePerRank = new ArrayList<>(k);
        for (String docId : docIdPerRank) {
            relevancePerRank.add(docId != null && evidenceDocIds.contains(docId));
        }
        return new CaseJudgment(caseHitRank != null, caseHitRank, hits, totalRelevant,
                relevancePerRank, totalRelevant);
    }

    /**
     * Smallest prefix of the ranking at which the union of the candidates seen so far first reaches
     * the aggregate coverage threshold for one span.
     *
     * @param span                  evidence span
     * @param candidateTextsPerRank per rank candidate texts
     * @param threshold             aggregate coverage threshold
     * @return one based rank, {@code null} when the full ranking never reaches the threshold
     */
    private Integer firstHitRank(String span, List<List<String>> candidateTextsPerRank, double threshold) {
        List<String> accumulated = new ArrayList<>();
        for (int rank = 1; rank <= candidateTextsPerRank.size(); rank++) {
            accumulated.addAll(candidateTextsPerRank.get(rank - 1));
            if (overlapRatioCalculator.aggregateCoverage(accumulated, span) >= threshold) {
                return rank;
            }
        }
        return null;
    }

    /**
     * Tells whether any of the candidate texts of one rank overlaps any of the case's spans at all.
     *
     * @param rankTexts candidate texts of one rank
     * @param spans     evidence spans of the case
     * @return {@code true} when at least one pair shares a non empty common run
     */
    private boolean anyPositiveOverlap(List<String> rankTexts, List<String> spans) {
        if (CollectionUtils.isEmpty(rankTexts) || CollectionUtils.isEmpty(spans)) {
            return false;
        }
        for (String candidate : rankTexts) {
            for (String span : spans) {
                if (overlapRatioCalculator.overlapRatio(candidate, span) > 0.0d) {
                    return true;
                }
            }
        }
        return false;
    }
}
