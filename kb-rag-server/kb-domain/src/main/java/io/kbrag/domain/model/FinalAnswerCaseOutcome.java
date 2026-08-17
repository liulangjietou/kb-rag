package io.kbrag.domain.model;

/**
 * One case's final-answer result as reporting and release gating consume it.
 *
 * @param caseId               evaluation case business id
 * @param score                overall answer score, {@code null} when judging failed or was not applicable
 * @param correctness          correctness score
 * @param faithfulness         faithfulness score
 * @param completeness         completeness score
 * @param citationCorrectness  citation correctness score
 * @param citationCompleteness citation completeness score
 * @param refusalCorrect       correct answer/refuse decision
 * @param generationLatencyMs  generation wall time
 * @param judgeRequested       whether this case required a final-answer judgment
 * @param degraded             whether retrieval still carried a degradation after retries
 *
 * @author owlzhangfq@gmail.com
 */
public record FinalAnswerCaseOutcome(
        String caseId,
        Integer score,
        Integer correctness,
        Integer faithfulness,
        Integer completeness,
        Integer citationCorrectness,
        Integer citationCompleteness,
        Boolean refusalCorrect,
        Integer generationLatencyMs,
        boolean judgeRequested,
        boolean degraded) {

    /**
     * Tells whether this case produced a complete structured judgment.
     *
     * @return {@code true} when every metric required by reporting is present
     */
    public boolean judged() {
        return score != null && correctness != null && faithfulness != null && completeness != null
                && citationCorrectness != null && citationCompleteness != null && refusalCorrect != null;
    }
}
