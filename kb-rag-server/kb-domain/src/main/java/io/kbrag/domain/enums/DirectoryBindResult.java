package io.kbrag.domain.enums;

/**
 * Outcome of one bind attempt against the corporate directory.
 *
 * <p>Three outcomes rather than a boolean because the two failures must not be handled alike. A rejected
 * password is the user's problem and counts towards the lockout; an unreachable directory is the
 * deployment's problem and must not, or a domain controller outage would lock out every account that
 * retried during it.
 *
 * @author owlzhangfq@gmail.com
 */
public enum DirectoryBindResult {

    /** The directory accepted the credentials. */
    SUCCESS,

    /** The directory answered, and rejected the credentials. */
    INVALID_CREDENTIALS,

    /** The directory could not be reached or answered with a protocol level failure. */
    SERVICE_UNAVAILABLE
}
