package io.kbrag.infrastructure.auth;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.DirectoryBindResult;
import io.kbrag.domain.model.DirectoryBindOutcome;
import io.kbrag.domain.port.DirectoryAuthenticator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

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
 * <p>When group synchronisation is enabled, the group membership is read through the user's own freshly
 * authenticated connection rather than a service account: introducing a long lived directory password
 * only to read attributes the person can already see would be a pure addition to the secrets that can
 * leak. A failing lookup degrades to an empty group list and never fails the login.
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
    private static final String ATTR_MEMBER_OF = "memberOf";
    private static final String ATTR_DEFAULT_NAMING_CONTEXT = "defaultNamingContext";
    private static final String ROOT_DSE = "";
    private static final String FILTER_BY_UPN = "(userPrincipalName={0})";

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
    public DirectoryBindOutcome bind(String username, String password) {
        if (!available()) {
            log.error("directory bind rejected, single sign on is not configured");
            return DirectoryBindOutcome.failure(DirectoryBindResult.SERVICE_UNAVAILABLE);
        }
        // An empty password must never reach the controller: LDAP reads an empty credential as an
        // anonymous bind, which many directories accept, and that would turn a blank field into a
        // successful login.
        if (password == null || password.isEmpty()) {
            return DirectoryBindOutcome.failure(DirectoryBindResult.INVALID_CREDENTIALS);
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
            List<String> groupDns = ldap.getGroupSync().isEnabled()
                    ? lookupGroups(ctx, principal)
                    : List.of();
            return DirectoryBindOutcome.success(groupDns);
        } catch (AuthenticationException e) {
            // AD answers this as "AcceptSecurityContext error, data 52e" for a wrong password, and with
            // other data codes for a disabled or expired account. They are not distinguished: telling a
            // caller which one it was would confirm that the account exists.
            log.info("directory bind rejected, principal={}", principal);
            return DirectoryBindOutcome.failure(DirectoryBindResult.INVALID_CREDENTIALS);
        } catch (NamingException e) {
            log.error("directory bind failed, principal={}, url={}", principal, ldap.getUrl(), e);
            return DirectoryBindOutcome.failure(DirectoryBindResult.SERVICE_UNAVAILABLE);
        } finally {
            closeQuietly(ctx);
        }
    }

    /**
     * Reads the group membership of the freshly authenticated account.
     *
     * <p>Degrades to an empty list on any directory error. The bind already succeeded, and turning an
     * attribute read timeout into a login failure would lock people out to protect a role cache.
     */
    private List<String> lookupGroups(InitialDirContext ctx, String principal) {
        try {
            Attributes attributes = principal.contains("@")
                    ? findEntryByUpn(ctx, principal)
                    : ctx.getAttributes(principal, new String[]{ATTR_MEMBER_OF});
            return memberOf(attributes);
        } catch (NamingException e) {
            log.error("directory group lookup failed, degrading to no groups, principal={}", principal, e);
            return List.of();
        }
    }

    /**
     * Locates the entry of a user principal name bind.
     *
     * <p>A UPN is not a distinguished name, so the entry cannot be read directly; instead the root DSE
     * names the default naming context and a subtree search by UPN finds the account. Both reads run on
     * the user's own connection - Active Directory lets an authenticated account read its own entry.
     */
    private Attributes findEntryByUpn(InitialDirContext ctx, String principal) throws NamingException {
        Attributes rootDse = ctx.getAttributes(ROOT_DSE, new String[]{ATTR_DEFAULT_NAMING_CONTEXT});
        Attribute namingContext = rootDse.get(ATTR_DEFAULT_NAMING_CONTEXT);
        if (namingContext == null) {
            log.error("directory exposes no default naming context, group lookup skipped");
            return null;
        }
        String searchBase = String.valueOf(namingContext.get());
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{ATTR_MEMBER_OF});
        // The filter argument form escapes the value, so a crafted login name cannot inject a filter.
        NamingEnumeration<SearchResult> results =
                ctx.search(searchBase, FILTER_BY_UPN, new Object[]{principal}, controls);
        try {
            return results.hasMore() ? results.next().getAttributes() : null;
        } finally {
            results.close();
        }
    }

    private List<String> memberOf(Attributes attributes) throws NamingException {
        if (attributes == null) {
            return List.of();
        }
        Attribute memberOf = attributes.get(ATTR_MEMBER_OF);
        if (memberOf == null) {
            return List.of();
        }
        List<String> groupDns = new ArrayList<>(memberOf.size());
        NamingEnumeration<?> values = memberOf.getAll();
        try {
            while (values.hasMore()) {
                groupDns.add(String.valueOf(values.next()));
            }
        } finally {
            values.close();
        }
        return groupDns;
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
