package io.kbrag.domain.enums;

/**
 * Lifecycle state of an evaluation case.
 *
 * <p>Deliberately unrelated to {@link InheritStatus}: that enum describes a manual chunk annotation
 * carried across document versions, this one describes whether a case's evidence still matches the
 * knowledge base's active corpus. Reusing one for the other would couple two concepts that change for
 * different reasons.
 *
 * @author owlzhangfq@gmail.com
 */
public enum CaseStatus {

    /** Evidence matches the active version, the case is measured normally. */
    ACTIVE,

    /**
     * Evidence could not be matched in the active version after a document update.
     *
     * <p>Excluded from the hit and miss counts of a run, and surfaced separately so a document update
     * cannot silently pull the metrics down.
     */
    EVIDENCE_STALE,

    /** Retired by an operator; excluded from every run. */
    DEPRECATED
}
