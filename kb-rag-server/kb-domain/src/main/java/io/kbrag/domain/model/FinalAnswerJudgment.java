package io.kbrag.domain.model;

/**
 * Structured LLM-as-judge outcome for one generated final answer.
 *
 * <p>Every score uses the same one-to-five scale. A failed judge call is represented by the absence of
 * this value at the application layer, rather than by zero: zero is not on the rubric and must never be
 * averaged into a report as if it were a very poor answer.
 *
 * @param score                rounded mean of the five dimensions
 * @param correctness          factual correctness against the reference answer
 * @param faithfulness         support for every claim in the retrieved passages
 * @param completeness         coverage of the reference answer
 * @param citationCorrectness  whether numbered citations point to supporting passages
 * @param citationCompleteness whether claims needing support carry citations
 * @param refusalCorrect       whether the answer made the expected answer/refuse decision
 * @param reason               concise judge rationale
 *
 * @author owlzhangfq@gmail.com
 */
public record FinalAnswerJudgment(
        int score,
        int correctness,
        int faithfulness,
        int completeness,
        int citationCorrectness,
        int citationCompleteness,
        boolean refusalCorrect,
        String reason) {
}
