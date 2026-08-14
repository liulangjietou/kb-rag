package io.kbrag.infrastructure.connector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.ExtSourceConfig;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ExternalConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the M23 Confluence Cloud adapter at its real HTTP boundary: Basic authentication, cursor
 * pagination, stable page versions, HTML materialisation and cross-origin credential containment.
 *
 * <p>A local server is injected through the package-private seam because production correctly
 * requires HTTPS while an offline unit test must neither call Atlassian nor need a certificate.
 *
 * @author owlzhangfq@gmail.com
 */
class ConfluenceCloudConnectorTest {

    private static final String SPACE_KEY = "ENG";
    private static final String EMAIL = "reader@example.com";
    private static final String API_TOKEN = "token-value";
    private static final long MAX_CONTENT_BYTES = 1024 * 1024;

    private final Deque<StubResponse> responses = new ArrayDeque<>();
    private final List<SeenRequest> requests = new ArrayList<>();

    private HttpServer server;
    private ConfluenceCloudConnector connector;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        connector = new ConfluenceCloudConnector(URI::create, ignored -> client);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldListCapPlusOnePagesAcrossCursorPagination() {
        enqueue(200, spaces("42"), null);
        enqueue(200, pages(
                page("101", 3, "2026-08-10T08:30:00Z"),
                page("102", 7, "2026-08-11T09:00:00Z")),
                "</wiki/api/v2/spaces/42/pages?status=current&limit=2&cursor=next>; rel=\"next\"");
        enqueue(200, pages(page("103", 1, "2026-08-12T10:15:00Z")), null);

        List<ExternalConnector.RemoteObject> objects = connector.listObjects(config(2));

        assertThat(objects).extracting(ExternalConnector.RemoteObject::key)
                .containsExactly("confluence/101.html", "confluence/102.html", "confluence/103.html");
        assertThat(objects).extracting(ExternalConnector.RemoteObject::etag)
                .containsExactly("101:v3", "102:v7", "103:v1");
        assertThat(objects).extracting(ExternalConnector.RemoteObject::displayName)
                .containsExactly("Page 101", "Page 102", "Page 103");
        assertThat(objects.get(0).lastModified()).isEqualTo(LocalDateTime.parse("2026-08-10T08:30:00"));
        assertThat(requests).extracting(SeenRequest::pathAndQuery).containsExactly(
                "/wiki/api/v2/spaces?keys=ENG&status=current&limit=2",
                "/wiki/api/v2/spaces/42/pages?status=current&limit=3",
                "/wiki/api/v2/spaces/42/pages?status=current&limit=2&cursor=next");
        assertThat(requests).allSatisfy(request -> assertThat(request.authorization()).isEqualTo(basicHeader()));
    }

    @Test
    void shouldMaterialiseStorageBodyAsAnHtmlDocument() {
        enqueue(200, """
                {"id":"101","title":"R&D <Guide>","body":{"storage":{"value":"<h2>Intro</h2><p>A &amp; B</p>"}}}
                """, null);

        byte[] body = connector.fetchObject(config(10), "confluence/101.html");

        assertThat(new String(body, StandardCharsets.UTF_8))
                .contains("<title>R&amp;D &lt;Guide&gt;</title>")
                .contains("<article><h2>Intro</h2><p>A &amp; B</p></article>");
        assertThat(requests).extracting(SeenRequest::pathAndQuery)
                .containsExactly("/wiki/api/v2/pages/101?body-format=storage");
    }

    @Test
    void shouldRejectANextLinkBeforeSendingTheTokenToAnotherOrigin() {
        enqueue(200, spaces("42"), null);
        enqueue(200, """
                {"results":[],"_links":{"next":"https://evil.example/wiki/api/v2/pages?cursor=x"}}
                """, null);

        assertThatThrownBy(() -> connector.listObjects(config(10)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("站点边界");
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request -> assertThat(request.authorization()).isEqualTo(basicHeader()));
    }

    @Test
    void shouldFailTheListingInsteadOfTreatingAMalformedPageAsVanished() {
        enqueue(200, spaces("42"), null);
        enqueue(200, pages("{\"id\":\"101\"}"), null);

        assertThatThrownBy(() -> connector.listObjects(config(10)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("版本号");
    }

    @Test
    void shouldReportAnUnavailableSpaceWithoutLeakingTheToken() {
        enqueue(401, "{\"message\":\"denied\"}", null);

        HealthStatus health = connector.testConnection(config(10));

        assertThat(health.isUp()).isFalse();
        assertThat(health.getDetail()).contains("HTTP 401").doesNotContain(API_TOKEN);
    }

    @Test
    void shouldNormalizeTheDocumentedCloudSiteUrlShapes() {
        assertThat(ConfluenceCloudConnector.validateEndpoint("https://Example.atlassian.net"))
                .isEqualTo(URI.create("https://example.atlassian.net"));
        assertThat(ConfluenceCloudConnector.validateEndpoint("https://example.atlassian.net/wiki/"))
                .isEqualTo(URI.create("https://example.atlassian.net"));
        assertThatThrownBy(() -> ConfluenceCloudConnector.validateEndpoint("http://example.atlassian.net"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> ConfluenceCloudConnector.validateEndpoint(
                "https://example.atlassian.net/wiki/pages/viewpage.action"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("根地址");
    }

    private ExtSourceConfig config(int maxObjects) {
        return new ExtSourceConfig(baseUrl(), null, SPACE_KEY, null, EMAIL, API_TOKEN,
                maxObjects, 3000, MAX_CONTENT_BYTES);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String basicHeader() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (EMAIL + ":" + API_TOKEN).getBytes(StandardCharsets.UTF_8));
    }

    private void enqueue(int status, String body, String link) {
        responses.addLast(new StubResponse(status, body, link));
    }

    private void handle(HttpExchange exchange) throws IOException {
        StubResponse response = responses.removeFirst();
        requests.add(new SeenRequest(exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Authorization")));
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (response.link() != null) {
            exchange.getResponseHeaders().set("Link", response.link());
        }
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private String spaces(String id) {
        return "{\"results\":[{\"id\":\"" + id + "\",\"key\":\"" + SPACE_KEY + "\"}]}";
    }

    private String pages(String... values) {
        return "{\"results\":[" + String.join(",", values) + "]}";
    }

    private String page(String id, int version, String createdAt) {
        return "{\"id\":\"" + id + "\",\"title\":\"Page " + id
                + "\",\"version\":{\"number\":" + version
                + ",\"createdAt\":\"" + createdAt + "\"}}";
    }

    private record StubResponse(int status, String body, String link) {
    }

    private record SeenRequest(String pathAndQuery, String authorization) {
    }
}
