package io.kbrag.infrastructure.web;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.WaitUntilState;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.FetchCredential;
import io.kbrag.domain.port.WebPageFetcher;
import io.kbrag.domain.service.UrlGuard;
import io.kbrag.domain.service.WebAuthException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Fetches a page through a headless Chromium and returns its rendered DOM, the M17 contract section
 * 2.3. This is the render half of the fetcher pair; {@link WebPageFetcherDispatcher} routes only the
 * {@code render_js} sources here, static ones stay on {@link HttpWebPageFetcher}.
 *
 * <p><b>The browser is launched lazily.</b> A deployment that never renders - or one whose image has
 * no Chromium - never pays for a browser: the first render triggers the launch under a double-checked
 * lock, and a launch failure surfaces as a per-source FAILED rather than a startup crash.
 *
 * <p><b>Every sub-request the page issues is re-validated against {@link UrlGuard}.</b> A headless
 * browser fetches images, XHRs, iframes and stylesheets on its own, none of which passed through the
 * URL the operator typed; without this route interception "render this page" would become "let the
 * page read {@code 169.254.169.254}". A blocked sub-resource is aborted, not thrown, so one hostile
 * asset cannot abort an otherwise legitimate render.
 *
 * <p>Concurrency is bounded by a {@link Semaphore}: a browser context holds hundreds of MB, so an
 * unbounded render fan-out is an out-of-memory waiting to happen.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class PlaywrightWebPageFetcher implements WebPageFetcher {

    private static final int BYTES_PER_MB = 1024 * 1024;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final String RENDERED_EXTENSION = "html";
    private static final String USER_AGENT = "kb-rag/1.0 (+url-import; render)";

    private final UrlGuard urlGuard;
    private final KbProperties properties;
    private final Semaphore renderPermits;

    /** Launched on the first render under {@link #browserLock}, closed by {@link #shutdown()}. */
    private volatile Playwright playwright;
    private volatile Browser browser;
    private final Object browserLock = new Object();

    public PlaywrightWebPageFetcher(UrlGuard urlGuard, KbProperties properties) {
        this.urlGuard = urlGuard;
        this.properties = properties;
        int permits = Math.max(1, properties.getWebImport().getRender().getMaxConcurrency());
        this.renderPermits = new Semaphore(permits);
    }

    @Override
    public FetchedPage fetch(FetchRequest request) {
        int timeoutMs = properties.getWebImport().getRender().getTimeoutMs();
        boolean acquired = acquirePermit(timeoutMs);
        if (!acquired) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "渲染并发已满，等待超时，本次抓取中止");
        }
        try {
            return render(request, timeoutMs);
        } finally {
            renderPermits.release();
        }
    }

    private FetchedPage render(FetchRequest request, int timeoutMs) {
        Browser current = browser();
        BrowserContext context = current.newContext(new Browser.NewContextOptions()
                .setUserAgent(USER_AGENT)
                .setAcceptDownloads(false));
        try {
            context.setDefaultNavigationTimeout(timeoutMs);
            context.setDefaultTimeout(timeoutMs);
            // Every request the page makes - the navigation itself included - is re-validated, so a
            // redirect or a sub-resource pointing at an internal address is aborted before it opens.
            // The credential rides the same interception: injected ONLY onto requests whose host it
            // names. Never context.setExtraHTTPHeaders - that would broadcast the secret to every
            // third party asset the page happens to embed.
            FetchCredential credential = request.credential();
            context.route("**/*", route -> {
                String requestUrl = route.request().url();
                try {
                    URI target = urlGuard.validate(requestUrl);
                    if (credential != null && credential.appliesTo(target)) {
                        Map<String, String> headers = new HashMap<>(route.request().headers());
                        headers.put(credential.headerName(), credential.headerValue());
                        route.resume(new Route.ResumeOptions().setHeaders(headers));
                    } else {
                        route.resume();
                    }
                } catch (BizException e) {
                    log.info("render sub-request blocked by ssrf guard, errorCode={}, url={}, reason={}",
                            ErrorCode.INVALID_PARAM, requestUrl, e.getMessage());
                    route.abort();
                }
            });
            Page page = context.newPage();
            Response response = page.navigate(request.url(),
                    new Page.NavigateOptions().setWaitUntil(waitUntil()).setTimeout(timeoutMs));
            if (response != null && response.status() == HTTP_UNAUTHORIZED) {
                // Same dedicated type as the static path: one 401 per host per sync pass is the
                // ceiling, or a rotated password CAPTCHA-locks the account overnight.
                throw new WebAuthException("站点认证被拒绝（HTTP 401），请检查该 host 的凭据");
            }
            String dom = page.content();
            return new FetchedPage(bounded(dom), RENDERED_EXTENSION, page.url());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.info("render fetch failed, url={}, error={}", request.url(), e.getMessage());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "页面渲染抓取失败：" + e.getMessage(), e);
        } finally {
            context.close();
        }
    }

    private boolean acquirePermit(int timeoutMs) {
        try {
            return renderPermits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "等待渲染并发许可被中断", e);
        }
    }

    private byte[] bounded(String dom) {
        byte[] bytes = dom.getBytes(StandardCharsets.UTF_8);
        long maxBytes = (long) properties.getWebImport().getMaxPageSizeMb() * BYTES_PER_MB;
        if (bytes.length > maxBytes) {
            throw BizException.invalidParam("渲染后页面超过 "
                    + properties.getWebImport().getMaxPageSizeMb() + " MB 上限，抓取中止");
        }
        return bytes;
    }

    private WaitUntilState waitUntil() {
        String configured = properties.getWebImport().getRender().getWaitUntil();
        String value = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "load" -> WaitUntilState.LOAD;
            case "domcontentloaded" -> WaitUntilState.DOMCONTENTLOADED;
            default -> WaitUntilState.NETWORKIDLE;
        };
    }

    /**
     * Returns the shared browser, launching it on first use. The double-checked lock keeps the launch
     * to exactly one, and a launch failure - most often a missing Chromium binary - is turned into a
     * BizException so the calling sync records the source FAILED instead of taking down the app.
     */
    private Browser browser() {
        Browser existing = browser;
        if (existing != null) {
            return existing;
        }
        synchronized (browserLock) {
            if (browser == null) {
                try {
                    playwright = Playwright.create();
                    browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
                    log.info("headless browser launched for js rendering");
                } catch (RuntimeException e) {
                    log.error("headless browser launch failed, errorCode={}, error={}",
                            ErrorCode.INTERNAL_ERROR, e.getMessage());
                    throw new BizException(ErrorCode.INTERNAL_ERROR,
                            "无头浏览器启动失败，请确认镜像已内置 Chromium：" + e.getMessage(), e);
                }
            }
            return browser;
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (browserLock) {
            if (browser != null) {
                browser.close();
                browser = null;
            }
            if (playwright != null) {
                playwright.close();
                playwright = null;
            }
        }
    }
}
