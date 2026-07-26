package io.kbrag.domain.enums;

/**
 * Kind of manual chunk annotation recorded in {@code t_kb_annotation}.
 *
 * <p>The four values are the four operations a human can perform on a chunk. They are kept as one
 * enum rather than as separate tables because every one of them has to travel through the same
 * change pipeline — MySQL first, then re-embed, then both engines — and because the cross version
 * review list has to present them together.
 *
 * @author owlzhangfq@gmail.com
 */
public enum AnnotationType {

    /** Chunk text was replaced in place. */
    EDIT,

    /** Retrieval switch of a chunk was flipped. */
    TOGGLE,

    /** Several adjacent chunks were replaced by one. */
    MERGE,

    /** One chunk was replaced by several. */
    SPLIT
}
