package io.kbrag.domain.port;

import io.kbrag.domain.model.ExternalAuthOutcome;

/**
 * SAML 2.0 service provider: builds the AuthnRequest redirect and verifies the posted Response.
 *
 * <p>The XML signature against the configured IdP certificate is the single trust anchor; on top of
 * it the assertion conditions - NotOnOrAfter, Audience, InResponseTo - bind the assertion to this
 * deployment and to the request that asked for it. A response failing any check is an invalid
 * credential: SAML's entire security model collapses to "did the IdP sign exactly this".
 *
 * @author owlzhangfq@gmail.com
 */
public interface SamlProcessor {

    /**
     * Whether the service provider is configured and usable.
     *
     * @return {@code true} when a login redirect may be built
     */
    boolean available();

    /**
     * Builds the IdP redirect URL carrying a deflated AuthnRequest.
     *
     * @param requestId  identifier written into the AuthnRequest, later matched against InResponseTo
     * @param relayState opaque anti forgery value echoed back by the IdP
     * @param acsUrl     absolute URL of the assertion consumer endpoint
     * @return redirect URL the browser is sent to
     */
    String loginRedirectUrl(String requestId, String relayState, String acsUrl);

    /**
     * Verifies a posted SAML response and extracts the asserted identity.
     *
     * <p>Never throws for a verification failure, the same discipline as the directory bind.
     *
     * @param samlResponse      base64 encoded response from the form post
     * @param expectedRequestId AuthnRequest id the response must answer, from the consumed state
     * @param acsUrl            absolute URL of the assertion consumer endpoint
     * @return verdict with the asserted identity on success
     */
    ExternalAuthOutcome consume(String samlResponse, String expectedRequestId, String acsUrl);
}
