package io.kbrag.domain.port;

import io.kbrag.domain.model.ExternalAuthOutcome;

/**
 * OpenID Connect relying party: builds the authorization redirect and verifies the callback.
 *
 * <p>The verification is the whole trust decision of this protocol: the id_token signature against
 * the IdP's published JWKS, plus issuer, audience and expiry. A callback that fails any of those is
 * an invalid credential, never a technicality - accepting an unverified token would let anyone who
 * can reach the callback endpoint mint a session.
 *
 * @author owlzhangfq@gmail.com
 */
public interface OidcClient {

    /**
     * Whether the relying party is configured and usable.
     *
     * @return {@code true} when a login redirect may be built
     */
    boolean available();

    /**
     * Builds the URL of the IdP authorization endpoint for a login redirect.
     *
     * @param state       opaque anti forgery value, stored server side and checked on the callback
     * @param redirectUri absolute callback URL registered at the IdP
     * @return authorization URL the browser is sent to
     */
    String authorizationUrl(String state, String redirectUri);

    /**
     * Exchanges the callback code and verifies the resulting id_token.
     *
     * <p>Never throws for a verification failure: the caller decides how a bad assertion and an
     * unreachable IdP differ in audit and lockout, the same discipline as the directory bind.
     *
     * @param code        authorization code from the callback
     * @param redirectUri the exact redirect URI used on the login redirect
     * @return verdict with the asserted identity on success
     */
    ExternalAuthOutcome exchange(String code, String redirectUri);
}
