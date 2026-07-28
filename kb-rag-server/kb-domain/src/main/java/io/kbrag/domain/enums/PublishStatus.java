package io.kbrag.domain.enums;

/**
 * Editorial state of a document, orthogonal to {@link ProcessStatus}: the pipeline decides whether
 * the corpus <em>can</em> serve the document, this state decides whether it <em>may</em>.
 *
 * <p>Transitions: {@code DRAFT | REJECTED -> PENDING_REVIEW -> PUBLISHED | REJECTED}. PUBLISHED is
 * terminal - taking a published document out of retrieval is an expiry or a trash operation, not a
 * review rollback, so the review trail never contradicts what readers already saw.
 *
 * @author owlzhangfq@gmail.com
 */
public enum PublishStatus {

    /** Uploaded under a review requiring knowledge base, waiting for the author to submit. */
    DRAFT,

    /** Submitted, waiting for a reviewer's verdict. */
    PENDING_REVIEW,

    /** Admitted to retrieval. Terminal. Also the implicit state of every document that predates M11. */
    PUBLISHED,

    /** Turned down with a note; the author may revise and resubmit. */
    REJECTED
}
