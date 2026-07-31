package io.kbrag.domain.enums;

/**
 * What the extraction decided to do with one memory node, the M19 contract.
 *
 * <p>Returned to the caller of AddMemory so an agent can tell "a new fact was learned" from "a
 * known fact was revised" - the distinction matters to agents that mirror memories locally.
 *
 * @author owlzhangfq@gmail.com
 */
public enum MemoryEventType {

    /** A new memory node was written. */
    ADD,

    /** An existing node of the same entity was revised in place. */
    UPDATE
}
