package io.kbrag.domain.port;

/**
 * Token counting abstraction used by the splitters.
 *
 * <p>Every length parameter of the indexing pipeline is expressed in tokens, so the estimator is
 * an interface from the start: M1 ships a cheap heuristic and a later milestone can plug in the
 * real tokenizer of the selected embedding model without touching any splitter.
 */
public interface TokenEstimator {

    /**
     * Estimates the token count of a text.
     *
     * @param text input text, {@code null} or empty yields 0
     * @return estimated token count
     */
    int estimate(String text);

    /**
     * Returns the longest prefix of {@code text} whose estimated token count does not exceed the
     * budget.
     *
     * @param text      input text
     * @param maxTokens token budget, must be positive
     * @return number of characters that fit into the budget
     */
    int prefixLengthWithinBudget(String text, int maxTokens);
}
