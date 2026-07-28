package io.kbrag.domain.enums;

/**
 * API boundary a recorded retrieval entered through.
 *
 * <p>Deliberately not "who ran it": evaluation runs and the release gate reuse the same retrieval
 * pipeline but never pass an API boundary, which is what keeps offline traffic out of the insight
 * report without a filter column.
 *
 * @author owlzhangfq@gmail.com
 */
public enum InsightSource {

    /** Console debug page search. */
    CONSOLE,

    /** Open API search or chat retrieval stage. */
    OPEN_API
}
