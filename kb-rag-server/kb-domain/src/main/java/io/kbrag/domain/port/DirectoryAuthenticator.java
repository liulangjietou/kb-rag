package io.kbrag.domain.port;

import io.kbrag.domain.model.DirectoryBindOutcome;

/**
 * Verifies a login name and password against the corporate directory.
 *
 * <p>Primarily authentication. The directory is asked whether the person is who they claim to be; it
 * may additionally report which groups the person belongs to, but only as raw distinguished names.
 * Turning a group into a console role stays a decision of this system's own configured mapping - an
 * unmapped group grants nothing, so reorganising an organisational unit cannot silently hand out
 * authorisation here. Roles live in {@code t_kb_user_role} and are granted by the application layer.
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
     * <p>When group synchronisation is enabled, a successful bind also carries the group distinguished
     * names read through the user's own authenticated connection. A failing group lookup degrades to an
     * empty list and never turns a successful bind into a failure: locking people out because an
     * attribute read timed out would trade a stale role set for no access at all.
     *
     * @param username login name without the domain suffix
     * @param password plaintext password, never logged and never stored
     * @return bind verdict with the groups read during the attempt
     */
    DirectoryBindOutcome bind(String username, String password);
}
