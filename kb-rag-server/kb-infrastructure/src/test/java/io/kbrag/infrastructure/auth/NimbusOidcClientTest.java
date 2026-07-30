package io.kbrag.infrastructure.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.DirectoryBindResult;
import io.kbrag.domain.model.ExternalAuthOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the OIDC relying party of the M16 contract section 3.3 against a real local IdP: the
 * discovery document, the code exchange and the JWKS all come from a local HTTP server, and the
 * id_token is a genuinely signed JWT. What is proven is the verification order the contract
 * demands - signature, issuer, audience and expiry all checked before a single claim is read - and
 * the outage/rejection split that keeps an IdP incident out of the lockout counters.
 *
 * @author owlzhangfq@gmail.com
 */
class NimbusOidcClientTest {

    private static final String CLIENT_ID = "kb-client";
    private static final String KEY_ID = "k1";
    private static final String REDIRECT_URI = "https://kb.example.com/sso/oidc/callback";

    private HttpServer server;
    private RSAKey idpKey;
    private KbProperties properties;
    private NimbusOidcClient client;
    private volatile int tokenStatus;
    private volatile String tokenBody;

    @BeforeEach
    void startIdp() throws Exception {
        idpKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/openid-configuration", exchange -> reply(exchange,
                200, "{\"authorization_endpoint\":\"" + issuer() + "/authorize\","
                        + "\"token_endpoint\":\"" + issuer() + "/token\","
                        + "\"jwks_uri\":\"" + issuer() + "/jwks\"}"));
        server.createContext("/jwks", exchange -> reply(exchange, 200,
                new JWKSet(idpKey.toPublicJWK()).toString()));
        server.createContext("/token", exchange -> reply(exchange, tokenStatus, tokenBody));
        server.start();
        properties = new KbProperties();
        properties.getAuth().getOidc().setEnabled(true);
        properties.getAuth().getOidc().setIssuer(issuer());
        properties.getAuth().getOidc().setClientId(CLIENT_ID);
        properties.getAuth().getOidc().setClientSecret("secret");
        properties.getAuth().getSso().setWebBaseUrl("https://kb.example.com");
        client = new NimbusOidcClient(properties);
    }

    @AfterEach
    void stopIdp() {
        server.stop(0);
    }

    @Test
    void shouldVerifyTheIdTokenAndReadTheIdentity() throws Exception {
        tokenAnswers(idToken(claims().claim("preferred_username", "alice")
                .claim("name", "Alice").claim("email", "alice@corp.example").build(), idpKey));

        ExternalAuthOutcome outcome = client.exchange("code-1", REDIRECT_URI);

        assertThat(outcome.result()).isEqualTo(DirectoryBindResult.SUCCESS);
        assertThat(outcome.identity().username()).isEqualTo("alice");
        assertThat(outcome.identity().displayName()).isEqualTo("Alice");
        assertThat(outcome.identity().email()).isEqualTo("alice@corp.example");
    }

    @Test
    void shouldFallBackToTheSubjectWithoutAPreferredUsername() throws Exception {
        tokenAnswers(idToken(claims().build(), idpKey));

        assertThat(client.exchange("code-1", REDIRECT_URI).identity().username())
                .isEqualTo("sub-1");
    }

    @Test
    void shouldRejectAnIdTokenFromAnotherIssuer() throws Exception {
        tokenAnswers(idToken(claims().issuer("https://evil.example.com").build(), idpKey));

        assertThat(client.exchange("code-1", REDIRECT_URI).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldRejectAnIdTokenMintedForAnotherClient() throws Exception {
        // A token for some other relying party of the same IdP must not open a session here.
        tokenAnswers(idToken(claims().audience("someone-else").build(), idpKey));

        assertThat(client.exchange("code-1", REDIRECT_URI).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldRejectAnExpiredIdToken() throws Exception {
        tokenAnswers(idToken(claims()
                .expirationTime(new Date(System.currentTimeMillis() - 600_000L)).build(), idpKey));

        assertThat(client.exchange("code-1", REDIRECT_URI).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldRejectAnIdTokenSignedByAForeignKey() throws Exception {
        // Same key id, different key: the JWKS the IdP publishes is the only trust anchor, and a
        // token endpoint gone rogue must not be able to mint console sessions.
        RSAKey foreign = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        tokenAnswers(idToken(claims().build(), foreign));

        assertThat(client.exchange("code-1", REDIRECT_URI).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldTreatATokenResponseWithoutAnIdTokenAsRejected() {
        tokenAnswers("{\"access_token\":\"opaque\"}");

        assertThat(client.exchange("code-1", REDIRECT_URI).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldCountARejectedCodeExchangeLikeAWrongPassword() {
        // A 4xx is the IdP rejecting this exchange - an expired or replayed code - which is the
        // caller's own doing and must count like a wrong password, not like an outage.
        tokenStatus = 400;
        tokenBody = "{\"error\":\"invalid_grant\"}";

        assertThat(client.exchange("code-replayed", REDIRECT_URI).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldCountATokenEndpointFailureAsAnOutage() {
        tokenStatus = 500;
        tokenBody = "{\"error\":\"server_error\"}";

        assertThat(client.exchange("code-1", REDIRECT_URI).result())
                .isEqualTo(DirectoryBindResult.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldCountAnUnreachableIssuerAsAnOutage() {
        // Discovery is fetched on first use; an issuer that cannot be read means no endpoint can
        // be trusted, and nobody retried a wrong password.
        properties.getAuth().getOidc().setIssuer("http://127.0.0.1:1/never-there");
        NimbusOidcClient fresh = new NimbusOidcClient(properties);

        assertThat(fresh.exchange("code-1", REDIRECT_URI).result())
                .isEqualTo(DirectoryBindResult.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldBuildTheAuthorizationUrlFromTheDiscoveryDocument() {
        String url = client.authorizationUrl("state-1", REDIRECT_URI);

        assertThat(url).startsWith(issuer() + "/authorize?response_type=code");
        assertThat(url).contains("&client_id=" + CLIENT_ID);
        assertThat(url).contains("&state=state-1");
    }

    private String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Baseline claims that pass every check; each test breaks exactly one of them. */
    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer(issuer())
                .audience(CLIENT_ID)
                .subject("sub-1")
                .expirationTime(new Date(System.currentTimeMillis() + 600_000L));
    }

    private String idToken(JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private void tokenAnswers(String idToken) {
        tokenStatus = 200;
        tokenBody = idToken.startsWith("{") ? idToken : "{\"id_token\":\"" + idToken + "\"}";
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
