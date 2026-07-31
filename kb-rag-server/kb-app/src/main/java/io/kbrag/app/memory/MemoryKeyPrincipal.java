package io.kbrag.app.memory;

import lombok.Getter;
import lombok.ToString;

/**
 * The authenticated caller of one memory open API request, the M19 contract.
 *
 * <p>Carries no key material, exactly like the knowledge open API principal: after authentication
 * nothing downstream has a legitimate reason to hold the credential. It does carry the library id,
 * because a memory key is bound to exactly one library and that binding is the isolation predicate
 * every downstream query filters on.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
public final class MemoryKeyPrincipal {

    /** Memory key row business id, for log lines and the rate limiter bucket. */
    private final String keyId;

    /** The one library this key may read and write. */
    private final String libraryId;

    /** Purpose note of the key, for log lines. */
    private final String name;

    /** Token bucket rate of this key. */
    private final int qpsLimit;

    public MemoryKeyPrincipal(String keyId, String libraryId, String name, int qpsLimit) {
        this.keyId = keyId;
        this.libraryId = libraryId;
        this.name = name;
        this.qpsLimit = qpsLimit;
    }
}
