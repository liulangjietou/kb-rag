package io.kbrag.domain.model;

import io.kbrag.domain.enums.DirectoryBindResult;

/**
 * Verdict of one single sign on assertion check, aligned with the directory bind verdicts.
 *
 * <p>The three states carry the same operational meaning across every protocol: an invalid assertion
 * is the caller's own doing, an unreachable identity provider is an outage and must never count
 * towards any lockout, and success carries the asserted identity. Reusing {@link DirectoryBindResult}
 * keeps that distinction a single vocabulary instead of one enum per protocol saying the same thing.
 *
 * @param result   verdict
 * @param identity asserted identity, {@code null} unless the verdict is {@code SUCCESS}
 *
 * @author owlzhangfq@gmail.com
 */
public record ExternalAuthOutcome(DirectoryBindResult result, ExternalIdentity identity) {

    /**
     * A verified assertion.
     *
     * @param identity identity the provider asserted
     * @return outcome
     */
    public static ExternalAuthOutcome success(ExternalIdentity identity) {
        return new ExternalAuthOutcome(DirectoryBindResult.SUCCESS, identity);
    }

    /**
     * An assertion that failed verification: bad signature, expired, wrong audience.
     *
     * @return outcome
     */
    public static ExternalAuthOutcome invalid() {
        return new ExternalAuthOutcome(DirectoryBindResult.INVALID_CREDENTIALS, null);
    }

    /**
     * The identity provider could not be reached or answered outside its protocol.
     *
     * @return outcome
     */
    public static ExternalAuthOutcome unavailable() {
        return new ExternalAuthOutcome(DirectoryBindResult.SERVICE_UNAVAILABLE, null);
    }
}
