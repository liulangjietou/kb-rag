package io.kbrag.app.eval;

/**
 * Operator decision of the evidence review workbench, requirement section 4.5.
 *
 * @author owlzhangfq@gmail.com
 */
public enum EvalRecheckAction {

    /** Replaces the evidence with a freshly picked one and puts the case back to {@code ACTIVE}. */
    REANCHOR,

    /** Retires the case; it is excluded from every future run. */
    DEPRECATE
}
