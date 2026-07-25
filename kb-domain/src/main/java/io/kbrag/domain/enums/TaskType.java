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
    CLEANUP
}
