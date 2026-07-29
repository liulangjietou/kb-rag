package io.kbrag.domain.port;

import io.kbrag.domain.enums.DirectoryBindResult;

/**
 * Verifies a login name and password against the corporate directory.
 *
 * <p>Authentication only. The directory is asked whether the person is who they claim to be, never what
 * they are allowed to do: group membership in an enterprise directory is maintained by a team with its
 * own reasons, so deriving console roles from it would hand authorisation of this system to whoever
 * reorganises an organisational unit. Roles live in {@code t_kb_user_role} and are granted here.
 *
 * <p>No account is provisioned by this port either. It reports a verdict; deciding whether a first time
 * directory login gets an account is a policy of the application layer.
 *
 * @author owlzhangfq@gmail.com
 */
public interface DirectoryAuthenticator {

    /**
     * Whether the directory integration is configured and usable.
     *
     * <p>Read before offering single sign on so an unconfigured deployment can reject the request up
     * front instead of timing out against an address that was never set.
     *
     * @return {@code true} when a bind may be attempted
     */
    boolean available();

    /**
     * Attempts a bind with the supplied credentials.
     *
     * <p>Implementations must never signal failure by throwing: the caller has to tell a wrong password
     * apart from an unreachable directory in order to decide whether the attempt counts towards the
     * account lockout.
     *
     * @param username login name without the domain suffix
     * @param password plaintext password, never logged and never stored
     * @return bind verdict
     */
    DirectoryBindResult bind(String username, String password);
}
