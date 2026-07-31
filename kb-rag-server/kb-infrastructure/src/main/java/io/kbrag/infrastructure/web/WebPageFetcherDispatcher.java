package io.kbrag.infrastructure.web;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.WebPageFetcher;
import io.kbrag.domain.service.LoginWallDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Routes a fetch to the static or the rendering implementation, the M17 contract section 2.4.
 *
 * <p>This is the {@link Primary} {@link WebPageFetcher} the sync service injects, so the choice
 * between fetching server HTML and rendering in a browser stays out of the service and in one place.
 * A source renders only when it asked to ({@code renderJs}) <b>and</b> the deployment allows it (the
 * master switch): with the switch off - the safe default for an image without Chromium - every
 * source falls back to the static fetch and records SUCCESS/UNCHANGED exactly as in M12.
 *
 * <p>Being the single exit also makes it the login wall checkpoint (M18): whichever implementation
 * produced the page, a body that turns out to be a login form is rejected here, before the caller
 * can hash it, judge it "changed" and file it as a document. The two paths cannot drift apart on
 * this rule because neither of them owns it.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Primary
@Component
public class WebPageFetcherDispatcher implements WebPageFetcher {

    private final HttpWebPageFetcher staticFetcher;
    private final PlaywrightWebPageFetcher renderFetcher;
    private final LoginWallDetector loginWallDetector;
    private final KbProperties properties;

    public WebPageFetcherDispatcher(HttpWebPageFetcher staticFetcher,
                                    PlaywrightWebPageFetcher renderFetcher,
                                    LoginWallDetector loginWallDetector,
                                    KbProperties properties) {
        this.staticFetcher = staticFetcher;
        this.renderFetcher = renderFetcher;
        this.loginWallDetector = loginWallDetector;
        this.properties = properties;
    }

    @Override
    public FetchedPage fetch(FetchRequest request) {
        FetchedPage page = route(request);
        loginWallDetector.check(page.finalUrl(), page.body(), page.extension());
        return page;
    }

    private FetchedPage route(FetchRequest request) {
        if (request.renderJs() && properties.getWebImport().getRender().isEnabled()) {
            return renderFetcher.fetch(request);
        }
        if (request.renderJs()) {
            // Asked to render but the master switch is off: fall back rather than fail, so a source
            // stays syncable on an image that cannot render.
            log.info("render requested but disabled by master switch, falling back to static fetch, url={}",
                    request.url());
        }
        return staticFetcher.fetch(request);
    }
}
