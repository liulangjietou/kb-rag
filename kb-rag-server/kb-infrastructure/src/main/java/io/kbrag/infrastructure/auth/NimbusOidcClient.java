package io.kbrag.infrastructure.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ExternalAuthOutcome;
import io.kbrag.domain.model.ExternalIdentity;
import io.kbrag.domain.port.OidcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * OIDC relying party on the JDK HTTP client and the Nimbus JOSE library.
 *
 * <p>Every endpoint comes from the discovery document at
 * {@code {issuer}/.well-known/openid-configuration}: hand configured endpoints drift from what the
 * IdP actually serves, and the discovery document is the IdP's own statement. It is fetched once
 * and kept for the process lifetime - the endpoints of an issuer change on the IdP's release
 * schedule, which in practice means a redeploy on this side too.
 *
 * <p>The id_token is verified against the IdP's published JWKS plus issuer, audience and expiry
 * before a single claim is read. The code flow means the token arrives over the back channel, but
 * the verification is still what stops a compromised or misrouted token endpoint from minting
 * console sessions.
 *
 * <p>Client credentials go into the token request body ({@code client_secret_post}) rather than a
 * Basic header: the header form requires an extra URL encoding pass that several major IdPs apply
 * inconsistently, and the body form is accepted by all of them.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NimbusOidcClient implements OidcClient {

    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";
    private static final String FIELD_AUTHORIZATION_ENDPOINT = "authorization_endpoint";
    private static final String FIELD_TOKEN_ENDPOINT = "token_endpoint";
    private static final String FIELD_JWKS_URI = "jwks_uri";
    private static final String FIELD_ID_TOKEN = "id_token";
    private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_EMAIL = "email";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final int STATUS_OK = 200;
    private static final int STATUS_SERVER_ERROR = 500;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final KbProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Discovery endpoints, fetched once; {@code volatile} because logins race on first use. */
    private volatile Endpoints endpoints;

    @Override
    public boolean available() {
        KbProperties.Auth.Oidc oidc = properties.getAuth().getOidc();
        return oidc.isEnabled()
                && notBlank(oidc.getIssuer())
                && notBlank(oidc.getClientId())
                && notBlank(oidc.getClientSecret())
                && notBlank(properties.getAuth().getSso().getWebBaseUrl());
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        KbProperties.Auth.Oidc oidc = properties.getAuth().getOidc();
        Endpoints discovered = discover();
        if (discovered == null) {
            // The person is standing at the login page; this is the one place a discovery failure
            // must surface as a message rather than degrade silently into a broken redirect.
            throw BizException.invalidParam("身份提供方暂时不可用，请稍后重试");
        }
        return discovered.authorizationEndpoint()
                + "?response_type=code"
                + "&client_id=" + encode(oidc.getClientId())
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(oidc.getScopes())
                + "&state=" + encode(state);
    }

    @Override
    public ExternalAuthOutcome exchange(String code, String redirectUri) {
        Endpoints discovered = discover();
        if (discovered == null) {
            return ExternalAuthOutcome.unavailable();
        }
        KbProperties.Auth.Oidc oidc = properties.getAuth().getOidc();
        String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri)
                + "&client_id=" + encode(oidc.getClientId())
                + "&client_secret=" + encode(oidc.getClientSecret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(discovered.tokenEndpoint()))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.error("oidc token endpoint unreachable, endpoint={}", discovered.tokenEndpoint(), e);
            return ExternalAuthOutcome.unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExternalAuthOutcome.unavailable();
        }
        if (response.statusCode() >= STATUS_SERVER_ERROR) {
            log.error("oidc token endpoint failed, status={}", response.statusCode());
            return ExternalAuthOutcome.unavailable();
        }
        if (response.statusCode() != STATUS_OK) {
            // A 4xx is the IdP rejecting this exchange - an expired or replayed code - which is the
            // caller's own doing and must count like a wrong password, not like an outage.
            log.info("oidc code exchange rejected, status={}", response.statusCode());
            return ExternalAuthOutcome.invalid();
        }
        Map<String, Object> body = JsonUtil.parse(response.body(), MAP_TYPE);
        Object idToken = body.get(FIELD_ID_TOKEN);
        if (idToken == null) {
            log.info("oidc token response carries no id_token, treated as rejected");
            return ExternalAuthOutcome.invalid();
        }
        return verify(String.valueOf(idToken), discovered);
    }

    /**
     * Verifies signature, issuer, audience and expiry of the id_token and extracts the identity.
     */
    private ExternalAuthOutcome verify(String idToken, Endpoints discovered) {
        KbProperties.Auth.Oidc oidc = properties.getAuth().getOidc();
        try {
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            // Any published signature algorithm of the RSA and EC families is acceptable; the MAC
            // family is not, because a MAC key is the client secret and a token endpoint that signs
            // with it would let this very service forge assertions.
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                    JWSAlgorithm.Family.SIGNATURE,
                    new RemoteJWKSet<>(new URL(discovered.jwksUri()))));
            processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                    oidc.getClientId(),
                    new JWTClaimsSet.Builder().issuer(oidc.getIssuer()).build(),
                    Set.of("exp", "sub")));
            JWTClaimsSet claims = processor.process(idToken, null);
            String username = firstNonBlank(
                    claims.getStringClaim(CLAIM_PREFERRED_USERNAME), claims.getSubject());
            return ExternalAuthOutcome.success(new ExternalIdentity(username,
                    claims.getStringClaim(CLAIM_NAME), claims.getStringClaim(CLAIM_EMAIL)));
        } catch (Exception e) {
            // A token that fails verification is an invalid credential regardless of which check
            // tripped; naming the failing check to the browser would only help an attacker iterate.
            log.info("oidc id_token rejected: {}", e.getMessage());
            return ExternalAuthOutcome.invalid();
        }
    }

    /**
     * Fetches and caches the discovery document, {@code null} when the issuer cannot be read.
     */
    private Endpoints discover() {
        Endpoints cached = endpoints;
        if (cached != null) {
            return cached;
        }
        String issuer = properties.getAuth().getOidc().getIssuer();
        String url = issuer.endsWith("/")
                ? issuer.substring(0, issuer.length() - 1) + DISCOVERY_PATH
                : issuer + DISCOVERY_PATH;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != STATUS_OK) {
                log.error("oidc discovery failed, url={}, status={}", url, response.statusCode());
                return null;
            }
            Map<String, Object> document = JsonUtil.parse(response.body(), MAP_TYPE);
            Endpoints discovered = new Endpoints(
                    String.valueOf(document.get(FIELD_AUTHORIZATION_ENDPOINT)),
                    String.valueOf(document.get(FIELD_TOKEN_ENDPOINT)),
                    String.valueOf(document.get(FIELD_JWKS_URI)));
            endpoints = discovered;
            return discovered;
        } catch (IOException e) {
            log.error("oidc discovery unreachable, url={}", url, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /**
     * The three discovery fields this relying party consumes.
     *
     * @param authorizationEndpoint where the browser is sent to log in
     * @param tokenEndpoint         where the code is exchanged
     * @param jwksUri               where the signing keys are published
     */
    private record Endpoints(String authorizationEndpoint, String tokenEndpoint, String jwksUri) {
    }
}
