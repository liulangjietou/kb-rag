package io.kbrag.infrastructure.web;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.WebPageFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the routing rule of the M17 contract section 2.4: a source renders only when it asked to
 * <b>and</b> the master switch is on; every other combination stays on the static fetch. The point
 * of the test is that the browser is never even reached when it should not be - an image without
 * Chromium must keep syncing.
 *
 * @author owlzhangfq@gmail.com
 */
class WebPageFetcherDispatcherTest {

    private static final String URL = "https://example.com/docs/guide";

    private HttpWebPageFetcher staticFetcher;
    private PlaywrightWebPageFetcher renderFetcher;
    private KbProperties properties;
    private WebPageFetcherDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        staticFetcher = mock(HttpWebPageFetcher.class);
        renderFetcher = mock(PlaywrightWebPageFetcher.class);
        properties = new KbProperties();
        dispatcher = new WebPageFetcherDispatcher(staticFetcher, renderFetcher, properties);
        when(staticFetcher.fetch(anyString(), eq(false))).thenReturn(page("static"));
        when(renderFetcher.fetch(anyString(), eq(true))).thenReturn(page("rendered"));
    }

    @Test
    void shouldStayOnStaticFetchWhenTheSourceDidNotAskToRender() {
        WebPageFetcher.FetchedPage page = dispatcher.fetch(URL, false);

        assertThat(page.body()).isEqualTo("static".getBytes(StandardCharsets.UTF_8));
        verify(staticFetcher).fetch(URL, false);
        verify(renderFetcher, never()).fetch(anyString(), eq(true));
    }

    @Test
    void shouldRenderWhenTheSourceAskedAndTheMasterSwitchIsOn() {
        properties.getWebImport().getRender().setEnabled(true);

        WebPageFetcher.FetchedPage page = dispatcher.fetch(URL, true);

        assertThat(page.body()).isEqualTo("rendered".getBytes(StandardCharsets.UTF_8));
        verify(renderFetcher).fetch(URL, true);
        verify(staticFetcher, never()).fetch(anyString(), eq(false));
    }

    @Test
    void shouldFallBackToStaticWhenTheMasterSwitchIsOff() {
        // The safe default for an image without Chromium: a render_js source keeps syncing statically
        // rather than failing every pass.
        properties.getWebImport().getRender().setEnabled(false);

        WebPageFetcher.FetchedPage page = dispatcher.fetch(URL, true);

        assertThat(page.body()).isEqualTo("static".getBytes(StandardCharsets.UTF_8));
        verify(staticFetcher).fetch(URL, false);
        verify(renderFetcher, never()).fetch(anyString(), eq(true));
    }

    private WebPageFetcher.FetchedPage page(String body) {
        return new WebPageFetcher.FetchedPage(body.getBytes(StandardCharsets.UTF_8), "html");
    }
}
