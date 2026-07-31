package io.kbrag.domain.enums;

/**
 * Authentication scheme of a web site credential, the M18 contract.
 *
 * <p>Two variants cover every header-carried scheme without site specific code: BASIC derives the
 * {@code Authorization: Basic} header from a username and password, HEADER injects one arbitrary
 * header verbatim - which is what a Bearer token ({@code Authorization: Bearer x}) and a session
 * cookie ({@code Cookie: JSESSIONID=x}) both reduce to. A dedicated COOKIE type would add a name
 * for the same mechanism, not a mechanism.
 *
 * @author owlzhangfq@gmail.com
 */
public enum WebAuthType {

    /** Username and password, sent preemptively as {@code Authorization: Basic base64(user:pass)}. */
    BASIC,

    /** One arbitrary header name and value, sent verbatim; covers Bearer tokens and cookies. */
    HEADER
}
