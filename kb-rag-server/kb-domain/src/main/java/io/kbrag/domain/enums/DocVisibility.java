package io.kbrag.domain.enums;

/**
 * Visibility of one document inside its knowledge base (M16 document level data scope).
 *
 * <p>Two states only. A finer ladder - confidential, secret, per user grants - was considered and
 * rejected: every additional state multiplies the read paths that must agree on it, and the M15
 * knowledge base scope already answers "which bases". This enum only answers the remaining question,
 * "which rows inside a base", and a yes/no per role is the smallest shape that does.
 *
 * @author owlzhangfq@gmail.com
 */
public enum DocVisibility {

    /** The document follows the visibility of its knowledge base, the default of every row. */
    INHERIT,

    /** Only callers holding a role granted through {@code t_kb_doc_acl} may read the content. */
    RESTRICTED
}
