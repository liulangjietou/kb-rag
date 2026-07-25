package io.kbrag.domain.enums;

/**
 * Availability of a dictionary entry.
 *
 * <p>Disabling is preferred over deleting when an operator wants to test the retrieval impact of a
 * term: the row keeps its history and can be switched back on, while the served dictionary only
 * contains the enabled entries.
 */
public enum DictStatus {

    /** Served in the remote dictionary file. */
    ENABLED,

    /** Kept in the table but not served. */
    DISABLED
}
