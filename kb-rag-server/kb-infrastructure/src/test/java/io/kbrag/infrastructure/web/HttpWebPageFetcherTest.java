package io.kbrag.infrastructure.web;

import com.sun.net.httpserver.HttpServer;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.WebPageFetcher;
import io.kbrag.domain.service.UrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the transport rules of the M12 contract against a real local HTTP server: the redirect
 * that must be re-validated hop by hop, the content type whitelist and the streamed size cap.
 *
 * <p>The guard is replaced by a recording permissive one, because the production guard would
 * rightly refuse the loopback address this test server lives on - and the recording is exactly how
 * the per-hop re-validation is proven.
 *
 * @author owlzhangfq@gmail.com
 */
class HttpWebPageFetcherTest {

    private final List<String> validatedUrls = new ArrayList<>();

    private HttpServer server;
    private KbProperties properties;
    private HttpWebPageFetcher fetcher;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new KbProperties();
        properties.getWebImport().setMaxPageSizeMb(1);
        UrlGuard recordingGuard = new UrlGuard(properties) {
            @Override
            public URI validate(String url) {
                validatedUrls.add(url);
                return URI.create(url);
            }
        };
        fetcher = new HttpWebPageFetcher(recordingGuard, properties);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldMapTheContentTypeOntoTheUploadExtension() {
        respond("/plain", 200, "text/plain; charset=utf-8", "hello".getBytes(StandardCharsets.UTF_8));

        WebPageFetcher.FetchedPage page = fetcher.fetch(url("/plain"), false);

        assertThat(page.extension()).isEqualTo("txt");
        assertThat(page.body()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldDefaultToHtmlWhenTheResponseCarriesNoContentType() {
        respond("/bare", 200, null, "<html></html>".getBytes(StandardCharsets.UTF_8));

        assertThat(fetcher.fetch(url("/bare"), false).extension()).isEqualTo("html");
    }

    @Test
    void shouldRejectAContentTypeOutsideTheTextWhitelist() {
        // A PDF behind a URL belongs to the file upload path, where the magic number checks live.
        respond("/pdf", 200, "application/pdf", new byte[]{0x25, 0x50});

        assertThatThrownBy(() -> fetcher.fetch(url("/pdf"), false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("application/pdf");
    }

    @Test
    void shouldRevalidateEveryRedirectHop() {
        redirect("/a", "/b");
        respond("/b", 200, "text/html", "final".getBytes(StandardCharsets.UTF_8));

        WebPageFetcher.FetchedPage page = fetcher.fetch(url("/a"), false);

        assertThat(page.body()).isEqualTo("final".getBytes(StandardCharsets.UTF_8));
        // Both the original URL and the redirect target went through the guard: this is the whole
        // defence against a public page that 302s into the internal network.
        assertThat(validatedUrls).containsExactly(url("/a"), url("/b"));
    }

    @Test
    void shouldStopAfterTooManyRedirects() {
        redirect("/loop", "/loop");

        assertThatThrownBy(() -> fetcher.fetch(url("/loop"), false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重定向");
    }

    @Test
    void shouldRejectABodyOverTheSizeCap() {
        byte[] oversized = new byte[1024 * 1024 + 1];
        Arrays.fill(oversized, (byte) 'x');
        respond("/big", 200, "text/html", oversized);

        assertThatThrownBy(() -> fetcher.fetch(url("/big"), false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("MB");
    }

    @Test
    void shouldFailOnANonSuccessStatus() {
        respond("/gone", 404, "text/html", "not here".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> fetcher.fetch(url("/gone"), false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("404");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void respond(String path, int status, String contentType, byte[] body) {
        server.createContext(path, exchange -> {
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private void redirect(String path, String target) {
        server.createContext(path, exchange -> {
            // A relative Location is legal per RFC 7231 and must resolve against the issuing hop.
            exchange.getResponseHeaders().add("Location", target);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
    }
}
