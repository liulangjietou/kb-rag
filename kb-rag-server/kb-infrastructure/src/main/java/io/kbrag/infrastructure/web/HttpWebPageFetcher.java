package io.kbrag.infrastructure.web;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.WebPageFetcher;
import io.kbrag.domain.service.UrlGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches web pages for URL import, the M12 contract section 3.3.
 *
 * <p><b>Redirects are followed by hand, never by the HTTP client.</b> An automatic follow would
 * request whatever Location the remote server names, which is exactly how a public URL that 302s to
 * {@code 169.254.169.254} defeats a guard that only checked the first address - so every hop goes
 * through {@link UrlGuard} again before a connection is opened to it.
 *
 * <p>The body is read in bounded chunks against the size cap rather than trusting Content-Length:
 * a chunked response carries no length at all, and a hostile one may understate it.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class HttpWebPageFetcher implements WebPageFetcher {

    private static final int BYTES_PER_MB = 1024 * 1024;
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final String HEADER_LOCATION = "Location";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String USER_AGENT = "kb-rag/1.0 (+url-import)";

    /** Content type (parameters stripped, lower case) to the extension the upload chain expects. */
    private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = Map.of(
            "text/html", "html",
            "application/xhtml+xml", "html",
            "text/plain", "txt",
            "text/markdown", "md");

    /** Extension assumed when the response carries no Content-Type header at all. */
    private static final String DEFAULT_EXTENSION = "html";

    private final UrlGuard urlGuard;
    private final KbProperties properties;
    private final HttpClient httpClient;

    public HttpWebPageFetcher(UrlGuard urlGuard, KbProperties properties) {
        this.urlGuard = urlGuard;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                // NEVER is the whole point: see the class comment on redirect handling.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(properties.getWebImport().getFetchTimeoutMs()))
                .build();
    }

    @Override
    public FetchedPage fetch(String url) {
        String current = url;
        int maxRedirects = Math.max(0, properties.getWebImport().getMaxRedirects());
        for (int hop = 0; hop <= maxRedirects; hop++) {
            URI uri = urlGuard.validate(current);
            HttpResponse<InputStream> response = send(uri);
            int status = response.statusCode();
            if (isRedirect(status)) {
                current = redirectTarget(uri, response);
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "页面返回状态码 " + status + "，抓取失败");
            }
            String extension = extensionOf(response);
            return new FetchedPage(readBounded(response.body()), extension);
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR,
                "重定向超过 " + maxRedirects + " 次，抓取中止");
    }

    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getWebImport().getFetchTimeoutMs()))
                .header("Accept", "text/html, text/plain, text/markdown, application/xhtml+xml")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.info("web page fetch failed, uri={}, error={}", uri, e.getMessage());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "页面抓取失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "页面抓取被中断", e);
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private String redirectTarget(URI from, HttpResponse<InputStream> response) {
        Optional<String> location = response.headers().firstValue(HEADER_LOCATION);
        if (location.isEmpty() || location.get().isBlank()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "页面返回重定向但缺少目标地址");
        }
        // A relative Location is legal per RFC 7231 and resolves against the hop that issued it.
        return from.resolve(location.get().trim()).toString();
    }

    /**
     * Maps the response content type onto the file extension the upload chain validates against.
     * Anything outside the text whitelist is rejected: a PDF or an image behind a URL belongs to the
     * file upload path where the magic number checks live.
     */
    private String extensionOf(HttpResponse<InputStream> response) {
        Optional<String> header = response.headers().firstValue(HEADER_CONTENT_TYPE);
        if (header.isEmpty() || header.get().isBlank()) {
            return DEFAULT_EXTENSION;
        }
        String mediaType = header.get().split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (mediaType.isEmpty()) {
            return DEFAULT_EXTENSION;
        }
        String extension = EXTENSION_BY_CONTENT_TYPE.get(mediaType);
        if (extension == null) {
            throw BizException.invalidParam("仅支持网页与文本类内容，该地址返回 " + mediaType);
        }
        return extension;
    }

    private byte[] readBounded(InputStream body) {
        long maxBytes = (long) properties.getWebImport().getMaxPageSizeMb() * BYTES_PER_MB;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[COPY_BUFFER_SIZE];
        try (InputStream in = body) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() + read > maxBytes) {
                    throw BizException.invalidParam("页面超过 "
                            + properties.getWebImport().getMaxPageSizeMb() + " MB 上限，抓取中止");
                }
                buffer.write(chunk, 0, read);
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取页面内容失败：" + e.getMessage(), e);
        }
        return buffer.toByteArray();
    }
}
