package io.kbrag.domain.enums;

/**
 * Anchoring granularity of an evaluation case, see requirement section 4.5.
 *
 * <p>A span level case is anchored to an exact text excerpt inside a document, and its hit judgment
 * is a character level overlap computation. A document level case exists because an image derived
 * chunk carries no text worth quoting as a span; it is anchored to the whole document and its hit
 * judgment only asks whether any chunk of that document was recalled. The two kinds are never mixed
 * in the same metric bucket, because a document level "hit" and a span level "hit" answer different
 * questions.
 *
 * @author owlzhangfq@gmail.com
 */
public enum AnchorType {

    /** Anchored to an exact text excerpt of a document. */
    SPAN,

    /** Anchored to the whole document, used when no span can be quoted. */
    DOCUMENT
}
