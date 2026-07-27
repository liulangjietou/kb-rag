package io.kbrag.domain.enums;

/**
 * Whether an annotation of an older document version has been carried over to a newer one.
 *
 * <p>Annotations bind to a document version and are deliberately not inherited wholesale: a new
 * version may have completely different chunk boundaries, so replaying an edit blindly would apply
 * it to text the operator never saw. Only the disable annotations can be inherited automatically,
 * and only when the chunk text hash matches exactly.
 *
 * @author owlzhangfq@gmail.com
 */
public enum InheritStatus {

    /** Annotation applies to its own version only and has no counterpart in a newer one. */
    NOT_INHERITED,

    /** Row was created by the automatic disable inheritance of a newer version. */
    AUTO_INHERITED,

    /** Operator performed the equivalent operation again on a newer version. */
    REDONE
}
