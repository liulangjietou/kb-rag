package io.kbrag.app.retrieval;

/**
 * Keys of {@link RetrievalNodeView#getMetadata()} that a collaborator outside
 * {@link RetrievalService} needs to read rather than merely forward to a caller.
 *
 * <p>Kept apart from the many other metadata keys {@link RetrievalService} writes: those are display
 * only and read by nothing inside the service, while the two named here are what the evaluation
 * runner needs to find a two level unit's child texts for the child level aggregate coverage
 * judgment, requirement section 4.6.
 *
 * @author owlzhangfq@gmail.com
 */
public final class RetrievalMetadataKeys {

    /** List of per child score maps, present only when the node merged a parent's children. */
    public static final String CHILDREN = "children";

    /** Child text inside one entry of {@link #CHILDREN}. */
    public static final String CHILD_CONTENT = "content";

    private RetrievalMetadataKeys() {
    }
}
