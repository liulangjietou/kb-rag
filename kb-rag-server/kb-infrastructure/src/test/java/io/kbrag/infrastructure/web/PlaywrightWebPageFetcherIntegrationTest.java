package io.kbrag.infrastructure.web;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpServer;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.WebPageFetcher;
import io.kbrag.domain.service.UrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Renders real pages through a real Chromium against a local HTTP server, the M17 contract section
 * 7: the JS-injected body a static fetch cannot see, the SSRF interception of a private-network
 * sub-resource, and the navigation timeout. Tagged and assumption-guarded because it needs a
 * browser: on a machine without one every test is skipped, never failed - the same posture the
 * production code takes towards an image without Chromium.
 *
 * <p>The guard is replaced by a recording one that admits only the loopback host this test server
 * lives on, because the production guard would rightly refuse loopback altogether - and refusing
 * everything else is exactly how the sub-request interception is proven.
 *
 * @author owlzhangfq@gmail.com
 */
@Tag("browser")
class PlaywrightWebPageFetcherIntegrationTest {

    /** Built by string concatenation in the page script so the raw HTML never contains it. */
    private static final String RENDER_MARKER = "RENDERED-BY-JS";

    private final List<String> validatedUrls = new ArrayList<>();
    private final List<String> blockedUrls = new ArrayList<>();

    private HttpServer server;
    private KbProperties properties;
    private PlaywrightWebPageFetcher fetcher;

    @BeforeAll
    static void requireBrowser() {
        // Probe the browser once up front; without it (offline CI, no cached binary) every test in
        // the class is reported skipped instead of failed.
        try (Playwright playwright = Playwright.create()) {
            playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)).close();
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "chromium unavailable, skipping render integration tests: " + e.getMessage());
        }
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new KbProperties();
        properties.getWebImport().getRender().setTimeoutMs(15000);
        UrlGuard loopbackOnlyGuard = new UrlGuard(properties) {
            @Override
            public URI validate(String url) {
                validatedUrls.add(url);
                if (!url.contains("127.0.0.1")) {
                    blockedUrls.add(url);
                    throw BizException.invalidParam("blocked by test guard: " + url);
                }
                return URI.create(url);
            }
        };
        fetcher = new PlaywrightWebPageFetcher(loopbackOnlyGuard, properties);
    }

    @AfterEach
    void stopServer() {
        fetcher.shutdown();
        server.stop(0);
    }

    @Test
    void shouldReturnTheScriptInjectedBodyAStaticFetchCannotSee() {
        String raw = "<html><body><div id=\"root\"></div>"
                + "<script>document.getElementById('root').textContent='RENDERED-'+'BY-JS';</script>"
                + "</body></html>";
        respondHtml("/js", raw);

        WebPageFetcher.FetchedPage page = fetcher.fetch(url("/js"), true);

        String rendered = new String(page.body(), StandardCharsets.UTF_8);
        // The contrast of the M17 acceptance case 1: the marker exists only in the rendered DOM,
        // never in the server HTML - which is precisely why the static fetch stores an empty shell.
        assertThat(rendered).contains(RENDER_MARKER);
        assertThat(raw).doesNotContain(RENDER_MARKER);
        assertThat(page.extension()).isEqualTo("html");
    }

    @Test
    void shouldAbortThePrivateNetworkSubRequestAndStillRenderThePage() {
        respondHtml("/ssrf", "<html><body><h1>public body</h1>"
                + "<img src=\"http://192.168.255.250/pixel.png\">"
                + "</body></html>");

        WebPageFetcher.FetchedPage page = fetcher.fetch(url("/ssrf"), true);

        // The page renders and its own body survives; the hostile asset was validated and aborted.
        assertThat(new String(page.body(), StandardCharsets.UTF_8)).contains("public body");
        assertThat(blockedUrls).anyMatch(u -> u.contains("192.168.255.250"));
        // Every request the browser issued went through the guard, the navigation itself included.
        assertThat(validatedUrls).anyMatch(u -> u.equals(url("/ssrf")));
    }

    @Test
    void shouldFailWithinTheRenderTimeoutOnAPageThatNeverSettles() {
        properties.getWebImport().getRender().setTimeoutMs(2000);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> fetcher.fetch(url("/slow"), true))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("渲染");
        // Generous ceiling: the point is that the budget cuts the hang, not that it is exact.
        assertThat(System.currentTimeMillis() - start).isLessThan(8000);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void respondHtml(String path, String html) {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }
}
