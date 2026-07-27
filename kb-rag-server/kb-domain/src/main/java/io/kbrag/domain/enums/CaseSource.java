package io.kbrag.domain.enums;

/**
 * How an evaluation case entered the data set.
 *
 * @author owlzhangfq@gmail.com
 */
public enum CaseSource {

    /** Typed in by hand in the annotation workbench. */
    MANUAL,

    /** Collected with one click from the retrieval debug page. */
    DEBUG_PAGE,

    /** Brought in by the demo data set importer. */
    IMPORTED
}
