package io.kbrag.domain.enums;

/**
 * Provenance of a memory node, the M19 contract.
 *
 * <p>Recorded so the console can tell a model's inference from a caller's assertion: an extracted
 * memory can be wrong in ways a directly written one cannot, and the detail page labels them
 * differently for exactly that reason.
 *
 * @author owlzhangfq@gmail.com
 */
public enum MemoryNodeSource {

    /** Distilled from a conversation by the LLM extraction pipeline. */
    EXTRACTED,

    /** Written verbatim by the caller through {@code custom_content}. */
    CUSTOM
}
