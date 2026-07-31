package io.kbrag.domain.model;

import io.kbrag.domain.entity.WebCredential;
import io.kbrag.domain.enums.WebAuthType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * The resolved, ready-to-inject form of a site credential: one header for one host.
 *
 * <p>Resolution happens once, here, so both fetchers stay dumb: they compare the host and put the
 * header on the request, and neither knows what BASIC or a Bearer token is. That is also what makes
 * the injection testable without a database - a test constructs the record directly.
 *
 * <p>{@link #appliesTo(URI)} matches the host <b>exactly</b> (port included when the credential
 * names one). Never by suffix: a suffix match is how a credential of {@code wiki.example.com} ends
 * up sent to {@code evil.example.com}. The fetchers consult it on every hop and every sub-request,
 * which is what keeps a redirect from walking the header to a foreign host.
 *
 * @param host        exact host the header may be sent to, lower case, optionally {@code host:port}
 * @param headerName  header to inject
 * @param headerValue header value, carries the secret
 *
 * @author owlzhangfq@gmail.com
 */
public record FetchCredential(String host, String headerName, String headerValue) {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BASIC_PREFIX = "Basic ";
    private static final char HOST_PORT_SEPARATOR = ':';

    /**
     * Resolves a stored credential into its injectable form.
     *
     * @param credential stored row, enabled and non null
     * @return resolved credential
     */
    public static FetchCredential of(WebCredential credential) {
        String host = credential.getHost().toLowerCase(Locale.ROOT);
        if (credential.getAuthType() == WebAuthType.BASIC) {
            String pair = credential.getUsername() + ":" + credential.getSecret();
            String encoded = Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
            return new FetchCredential(host, AUTHORIZATION, BASIC_PREFIX + encoded);
        }
        return new FetchCredential(host, credential.getHeaderName(), credential.getSecret());
    }

    /**
     * Whether the header may be sent to this URI.
     *
     * <p>When the credential host names a port, the URI must carry the same explicit port; when it
     * does not, any port of the same host is accepted - the site, not the listener, owns the login.
     *
     * @param uri request target
     * @return {@code true} when the hosts match exactly
     */
    public boolean appliesTo(URI uri) {
        String target = uri.getHost();
        if (target == null) {
            return false;
        }
        target = target.toLowerCase(Locale.ROOT);
        int separator = host.indexOf(HOST_PORT_SEPARATOR);
        if (separator < 0) {
            return host.equals(target);
        }
        return host.substring(0, separator).equals(target)
                && String.valueOf(uri.getPort()).equals(host.substring(separator + 1));
    }
}
