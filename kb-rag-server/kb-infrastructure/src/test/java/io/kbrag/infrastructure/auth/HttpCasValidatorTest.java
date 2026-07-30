package io.kbrag.infrastructure.auth;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the CAS client of the M16 contract section 3.3 against a real local HTTP server: the
 * server side ticket validation is the entire security of the scheme, so what matters is that a
 * rejected ticket counts like a wrong password while a server that answers outside its protocol -
 * wrong status, unreadable XML, unreachable - counts as an outage and never towards a lockout.
 *
 * @author owlzhangfq@gmail.com
 */
class HttpCasValidatorTest {

    private static final String SERVICE_URL = "https://kb.example.com/sso/cas/callback";
    private static final String XML_SUCCESS = """
            <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
              <cas:authenticationSuccess><cas:user> alice </cas:user></cas:authenticationSuccess>
            </cas:serviceResponse>""";
    private static final String XML_FAILURE = """
            <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
              <cas:authenticationFailure code="INVALID_TICKET">ticket not recognised</cas:authenticationFailure>
            </cas:serviceResponse>""";
    private static final String XML_NAMELESS = """
            <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
              <cas:authenticationSuccess></cas:authenticationSuccess>
            </cas:serviceResponse>""";

    private HttpServer server;
    private KbProperties properties;
    private HttpCasValidator validator;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new KbProperties();
        properties.getAuth().getCas().setEnabled(true);
        // A trailing slash on purpose: path concatenation must stay predictable either way.
        properties.getAuth().getCas().setServerUrl(baseUrl() + "/");
        properties.getAuth().getSso().setWebBaseUrl("https://kb.example.com");
        validator = new HttpCasValidator(properties);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldReadTheAssertedUserOutOfASuccessReply() {
        respond(200, XML_SUCCESS);

        ExternalAuthOutcome outcome = validator.validate("ST-1", SERVICE_URL);

        assertThat(outcome.result()).isEqualTo(DirectoryBindResult.SUCCESS);
        assertThat(outcome.identity().username()).isEqualTo("alice");
    }

    @Test
    void shouldCountARejectedTicketLikeAWrongPassword() {
        respond(200, XML_FAILURE);

        assertThat(validator.validate("ST-replayed", SERVICE_URL).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldTreatASuccessReplyNamingNobodyAsRejected() {
        respond(200, XML_NAMELESS);

        assertThat(validator.validate("ST-1", SERVICE_URL).result())
                .isEqualTo(DirectoryBindResult.INVALID_CREDENTIALS);
    }

    @Test
    void shouldTreatANonOkStatusAsAnOutage() {
        // The validation endpoint answers 200 for both verdicts; any other status means the server
        // itself is unwell, which must never count towards a lockout.
        respond(500, "boom");

        assertThat(validator.validate("ST-1", SERVICE_URL).result())
                .isEqualTo(DirectoryBindResult.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldTreatAReplyOutsideTheProtocolAsAnOutage() {
        // The ticket was never judged: a non-XML body is a server fault, not the caller's.
        respond(200, "<html>maintenance page</html><oops");

        assertThat(validator.validate("ST-1", SERVICE_URL).result())
                .isEqualTo(DirectoryBindResult.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldTreatAnUnreachableServerAsAnOutage() {
        server.stop(0);

        assertThat(validator.validate("ST-1", SERVICE_URL).result())
                .isEqualTo(DirectoryBindResult.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldBuildTheLoginRedirectWithTheServiceUrlEncoded() {
        String redirect = validator.loginRedirectUrl(SERVICE_URL);

        // The trailing slash of the configured server URL must not double up in the path.
        assertThat(redirect).isEqualTo(baseUrl() + "/login?service="
                + "https%3A%2F%2Fkb.example.com%2Fsso%2Fcas%2Fcallback");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/p3/serviceValidate", exchange -> {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}
