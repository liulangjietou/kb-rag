package io.kbrag.infrastructure.auth;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ExternalAuthOutcome;
import io.kbrag.domain.model.ExternalIdentity;
import io.kbrag.domain.port.CasValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * CAS 2.0/3.0 client on the JDK HTTP client.
 *
 * <p>The protocol needs no library at all: the login side is one redirect URL, and the validation
 * side is one server to server GET whose XML reply names the account the ticket was issued to.
 * That server side call is the entire security of the scheme - the ticket itself is a random
 * string the browser carried, and only the CAS server can say whom it belongs to.
 *
 * <p>{@code /p3/serviceValidate} rather than the older {@code /serviceValidate}: same reply shape,
 * but the p3 endpoint also releases attributes, and every CAS 3.x server exposes it.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpCasValidator implements CasValidator {

    private static final String LOGIN_PATH = "/login";
    private static final String VALIDATE_PATH = "/p3/serviceValidate";
    private static final String NS_CAS = "http://www.yale.edu/tp/cas";
    private static final String ELEMENT_SUCCESS = "authenticationSuccess";
    private static final String ELEMENT_USER = "user";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final int STATUS_OK = 200;

    private final KbProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public boolean available() {
        KbProperties.Auth.Cas cas = properties.getAuth().getCas();
        return cas.isEnabled()
                && cas.getServerUrl() != null && !cas.getServerUrl().isBlank()
                && properties.getAuth().getSso().getWebBaseUrl() != null
                && !properties.getAuth().getSso().getWebBaseUrl().isBlank();
    }

    @Override
    public String loginRedirectUrl(String serviceUrl) {
        return baseUrl() + LOGIN_PATH + "?service=" + encode(serviceUrl);
    }

    @Override
    public ExternalAuthOutcome validate(String ticket, String serviceUrl) {
        String url = baseUrl() + VALIDATE_PATH
                + "?ticket=" + encode(ticket)
                + "&service=" + encode(serviceUrl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.error("cas validation unreachable, serverUrl={}", baseUrl(), e);
            return ExternalAuthOutcome.unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExternalAuthOutcome.unavailable();
        }
        if (response.statusCode() != STATUS_OK) {
            // The validation endpoint answers 200 for both verdicts; any other status means the
            // server itself is unwell, which must never count towards a lockout.
            log.error("cas validation failed, status={}", response.statusCode());
            return ExternalAuthOutcome.unavailable();
        }
        return parseVerdict(response.body());
    }

    /**
     * Reads the validation reply: {@code cas:authenticationSuccess} carrying {@code cas:user}, or
     * {@code cas:authenticationFailure} carrying a code this side deliberately does not relay.
     */
    private ExternalAuthOutcome parseVerdict(String body) {
        try {
            Document document = SecureXml.parse(body);
            NodeList success = document.getElementsByTagNameNS(NS_CAS, ELEMENT_SUCCESS);
            if (success.getLength() == 0) {
                // INVALID_TICKET, INVALID_SERVICE and friends: all of them mean this ticket opens
                // no session, and all of them are the caller's own doing.
                log.info("cas ticket rejected by the server");
                return ExternalAuthOutcome.invalid();
            }
            NodeList user = ((Element) success.item(0)).getElementsByTagNameNS(NS_CAS, ELEMENT_USER);
            if (user.getLength() == 0 || user.item(0).getTextContent() == null
                    || user.item(0).getTextContent().isBlank()) {
                log.info("cas success reply names no user, treated as rejected");
                return ExternalAuthOutcome.invalid();
            }
            return ExternalAuthOutcome.success(
                    new ExternalIdentity(user.item(0).getTextContent().trim(), null, null));
        } catch (Exception e) {
            // A reply that is not the protocol's XML is a server fault, not the caller's: the
            // ticket was never judged.
            log.error("cas validation reply unreadable", e);
            return ExternalAuthOutcome.unavailable();
        }
    }

    /**
     * The configured server URL without a trailing slash, so path concatenation stays predictable.
     */
    private String baseUrl() {
        String serverUrl = properties.getAuth().getCas().getServerUrl();
        return serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
