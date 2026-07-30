package io.kbrag.infrastructure.web;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.WebPageFetcher;
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
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Primary
@Component
public class WebPageFetcherDispatcher implements WebPageFetcher {

    private final HttpWebPageFetcher staticFetcher;
    private final PlaywrightWebPageFetcher renderFetcher;
    private final KbProperties properties;

    public WebPageFetcherDispatcher(HttpWebPageFetcher staticFetcher,
                                    PlaywrightWebPageFetcher renderFetcher,
                                    KbProperties properties) {
        this.staticFetcher = staticFetcher;
        this.renderFetcher = renderFetcher;
        this.properties = properties;
    }

    @Override
    public FetchedPage fetch(String url, boolean renderJs) {
        if (renderJs && properties.getWebImport().getRender().isEnabled()) {
            return renderFetcher.fetch(url, true);
        }
        if (renderJs) {
            // Asked to render but the master switch is off: fall back rather than fail, so a source
            // stays syncable on an image that cannot render.
            log.info("render requested but disabled by master switch, falling back to static fetch, url={}", url);
        }
        return staticFetcher.fetch(url, false);
    }
}
