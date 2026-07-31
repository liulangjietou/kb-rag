package io.kbrag.app.memory;

/**
 * One SearchMemory call as the application layer receives it, the M19 contract.
 *
 * <p>The library comes from the authenticated key, never from the payload.
 *
 * @param userId              memory entity id
 * @param query               current question
 * @param fragmentRuleId      restricts recall to one fragment rule, {@code null} for all
 * @param maxResults          most nodes returned, already clamped to the contract range
 * @param intentRecognition   whether to let the model veto the recall
 * @param rewrite             whether to rewrite the query before recall
 * @param rerank              whether to rerank the candidates
 * @param similarityThreshold drops reranked results below it, only honoured when rerank is on
 * @author owlzhangfq@gmail.com
 */
public record MemorySearchCommand(String userId, String query, String fragmentRuleId,
                                  int maxResults, boolean intentRecognition, boolean rewrite,
                                  boolean rerank, Double similarityThreshold) {
}
