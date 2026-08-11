package io.kbrag.infrastructure.parser;

import com.sun.net.httpserver.HttpServer;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ParsedDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the field mapping between the parser service response and {@link ParsedDocument} against a
 * real local HTTP server.
 *
 * <p><b>Why this test exists.</b> The response is walked field by field rather than bound by Jackson,
 * so a field the parser starts sending is simply ignored until someone reads it here. That is exactly
 * how {@code pages[].markdown} was lost once: the parser produced it, the domain model declared it,
 * every unit test on both sides passed - because both sides built their own fixtures - and the page
 * splitting strategy silently kept falling back to the plain page text, which carries no image
 * placeholders. The seam between the two services had no test at all.
 *
 * @author owlzhangfq@gmail.com
 */
class HttpDocumentParserClientTest {

    private static final String PAGE_ONE_MARKDOWN = "## Page 1\n\n第一页正文\n\n[[IMAGE:img-a]]";
    private static final String PAGE_ONE_TEXT = "第一页正文";

    private HttpServer server;
    private String responseBody;
    private HttpDocumentParserClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/parse", exchange -> {
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(payload);
            }
        });
        server.start();
        KbProperties properties = new KbProperties();
        properties.getParser().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new HttpDocumentParserClient(properties);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldCarryEveryPageFieldTheParserReports() {
        responseBody = """
                {"code":"OK","message":"","request_id":"r-1","data":{
                  "markdown":"## Page 1\\n\\n第一页正文\\n\\n[[IMAGE:img-a]]",
                  "pages":[{"page_no":1,"text":"第一页正文",
                    "markdown":"## Page 1\\n\\n第一页正文\\n\\n[[IMAGE:img-a]]",
                    "scanned":false,"ocr_source":null}],
                  "images":[],"warnings":[]}}""";

        ParsedDocument parsed = client.parse("sample.pdf", "pdf", new byte[]{1});

        ParsedDocument.ParsedPage page = parsed.pagesOrEmpty().get(0);
        assertThat(page.getPageNo()).isEqualTo(1);
        assertThat(page.getText()).isEqualTo(PAGE_ONE_TEXT);
        // The field the page splitting strategy is cut from. Losing it costs the strategy its image
        // placeholders and its markdown structure, without any error surfacing anywhere.
        assertThat(page.getMarkdown()).isEqualTo(PAGE_ONE_MARKDOWN);
        assertThat(page.markdownOrText()).contains("[[IMAGE:img-a]]");
        assertThat(page.isScanned()).isFalse();
        assertThat(page.getOcrSource()).isNull();
    }

    @Test
    void shouldFallBackToThePlainTextWhenAnOlderParserSendsNoPageMarkdown() {
        // The two services deploy in either order, so a parser that predates the field must still yield
        // an indexable page - just one without the placeholders it never carried.
        responseBody = """
                {"code":"OK","message":"","request_id":"r-2","data":{
                  "markdown":"第一页正文",
                  "pages":[{"page_no":1,"text":"第一页正文","scanned":false}],
                  "images":[],"warnings":[]}}""";

        ParsedDocument parsed = client.parse("legacy.pdf", "pdf", new byte[]{1});

        ParsedDocument.ParsedPage page = parsed.pagesOrEmpty().get(0);
        assertThat(page.getMarkdown()).isNull();
        assertThat(page.markdownOrText()).isEqualTo(PAGE_ONE_TEXT);
    }

    @Test
    void shouldCarryTheOcrMarkerAndTheScannedFlag() {
        responseBody = """
                {"code":"OK","message":"","request_id":"r-3","data":{
                  "markdown":"## Page 1\\n\\nOCR 文本",
                  "pages":[{"page_no":1,"text":"OCR 文本","markdown":"## Page 1\\n\\nOCR 文本",
                    "scanned":true,"ocr_source":"paddle"}],
                  "images":[],"warnings":["page 1 rendered"]}}""";

        ParsedDocument parsed = client.parse("scanned.pdf", "pdf", new byte[]{1});

        ParsedDocument.ParsedPage page = parsed.pagesOrEmpty().get(0);
        assertThat(page.isScanned()).isTrue();
        assertThat(page.ocrApplied()).isTrue();
        assertThat(parsed.warningsOrEmpty()).containsExactly("page 1 rendered");
    }
}
