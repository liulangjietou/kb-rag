package io.kbrag.domain.enums;

/**
 * Asynchronous task categories persisted in {@code t_kb_task}.
 *
 * @author owlzhangfq@gmail.com
 */
public enum TaskType {

    /** Parse a document version through the parser service. */
    PARSE,

    /** Split, embed and write a document version to the search engines. */
    INDEX,

    /** Rebuild a whole knowledge base index and switch the alias. */
    REBUILD,

    /** Drop superseded physical indices and their sync records. */
    CLEANUP,

    /**
     * Extract entities and relations of a knowledge base into the graph, requirement section 4.9.
     *
     * <p>No sibling {@code GRAPH_CLEANUP} exists on purpose: graph removal is triggered by the removal of
     * the chunks it traces back to, which already runs through one collaborator, so a task type of its own
     * would only duplicate a lifecycle that is not the operator's to schedule.
     */
    GRAPH_EXTRACT
}
