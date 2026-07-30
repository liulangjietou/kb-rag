package io.kbrag.api.dto;

/**
 * Which browser redirect single sign on protocols this deployment offers.
 *
 * <p>Answered before authentication so the login page can render the right buttons; each flag is
 * one boolean about the deployment's own wiring, never about any account.
 *
 * @param oidc whether the OpenID Connect entry point is configured
 * @param saml whether the SAML 2.0 entry point is configured
 * @param cas  whether the CAS entry point is configured
 *
 * @author owlzhangfq@gmail.com
 */
public record SsoProvidersResponse(boolean oidc, boolean saml, boolean cas) {
}
