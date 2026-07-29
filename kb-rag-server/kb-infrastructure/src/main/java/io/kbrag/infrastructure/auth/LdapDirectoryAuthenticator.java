package io.kbrag.infrastructure.auth;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.DirectoryBindResult;
import io.kbrag.domain.port.DirectoryAuthenticator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

/**
 * Verifies console logins against a corporate AD or LDAP directory with a simple bind.
 *
 * <p>A successful bind <em>is</em> the verification: the domain controller compares the password, so this
 * service never sees a hash, never stores one and has no password of its own to keep in sync. That is the
 * whole point of routing single sign on through the directory rather than mirroring accounts.
 *
 * <p>The two failure modes are separated by exception type, and the distinction matters more than it
 * looks. {@link AuthenticationException} means the controller answered and said no, so the attempt is the
 * user's own doing and may count towards the lockout. Any other {@link NamingException} means a timeout,
 * an unreachable controller or a protocol fault - counting those would let one outage lock out every
 * account that happened to retry during it.
 *
 * <p>Plain JNDI rather than Spring LDAP: a single bind is all that is needed, and JNDI ships with the JDK,
 * so single sign on costs this deployment no extra dependency.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class LdapDirectoryAuthenticator implements DirectoryAuthenticator {

    private static final String CTX_FACTORY = "com.sun.jndi.ldap.LdapCtxFactory";
    private static final String AUTH_SIMPLE = "simple";
    private static final String PROP_CONNECT_TIMEOUT = "com.sun.jndi.ldap.connect.timeout";
    private static final String PROP_READ_TIMEOUT = "com.sun.jndi.ldap.read.timeout";

    private final KbProperties properties;

    public LdapDirectoryAuthenticator(KbProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean available() {
        KbProperties.Auth.Ldap ldap = properties.getAuth().getLdap();
        return ldap.isEnabled() && ldap.getUrl() != null && !ldap.getUrl().isBlank();
    }

    @Override
    public DirectoryBindResult bind(String username, String password) {
        if (!available()) {
            log.error("directory bind rejected, single sign on is not configured");
            return DirectoryBindResult.SERVICE_UNAVAILABLE;
        }
        // An empty password must never reach the controller: LDAP reads an empty credential as an
        // anonymous bind, which many directories accept, and that would turn a blank field into a
        // successful login.
        if (password == null || password.isEmpty()) {
            return DirectoryBindResult.INVALID_CREDENTIALS;
        }

        KbProperties.Auth.Ldap ldap = properties.getAuth().getLdap();
        String principal = bindPrincipal(username, ldap.getDomainSuffix());

        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, CTX_FACTORY);
        env.put(Context.PROVIDER_URL, ldap.getUrl());
        env.put(Context.SECURITY_AUTHENTICATION, AUTH_SIMPLE);
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put(PROP_CONNECT_TIMEOUT, String.valueOf(ldap.getConnectTimeoutMs()));
        env.put(PROP_READ_TIMEOUT, String.valueOf(ldap.getReadTimeoutMs()));

        InitialDirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            log.info("directory bind succeeded, principal={}", principal);
            return DirectoryBindResult.SUCCESS;
        } catch (AuthenticationException e) {
            // AD answers this as "AcceptSecurityContext error, data 52e" for a wrong password, and with
            // other data codes for a disabled or expired account. They are not distinguished: telling a
            // caller which one it was would confirm that the account exists.
            log.info("directory bind rejected, principal={}", principal);
            return DirectoryBindResult.INVALID_CREDENTIALS;
        } catch (NamingException e) {
            log.error("directory bind failed, principal={}, url={}", principal, ldap.getUrl(), e);
            return DirectoryBindResult.SERVICE_UNAVAILABLE;
        } finally {
            closeQuietly(ctx);
        }
    }

    /**
     * Builds the user principal name sent to the directory.
     *
     * <p>A name the user already typed with a suffix is left alone rather than suffixed twice, because
     * people copy their address out of a mail client and a double suffix fails as "wrong password".
     */
    private String bindPrincipal(String username, String domainSuffix) {
        if (domainSuffix == null || domainSuffix.isBlank() || username.contains("@")) {
            return username;
        }
        return username + domainSuffix;
    }

    private void closeQuietly(InitialDirContext ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.close();
        } catch (NamingException e) {
            // The bind verdict is already decided; a failing close would only replace a valid answer.
            log.error("directory context close failed", e);
        }
    }
}
