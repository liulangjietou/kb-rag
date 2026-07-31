package io.kbrag.infrastructure.web;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.WebPageFetcher;
import io.kbrag.domain.service.LoginWallDetector;
import io.kbrag.domain.service.WebAuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the routing rule of the M17 contract section 2.4: a source renders only when it asked to
 * <b>and</b> the master switch is on; every other combination stays on the static fetch. The point
 * of the test is that the browser is never even reached when it should not be - an image without
 * Chromium must keep syncing. M18 adds the login wall checkpoint at this same single exit: a page
 * that turns out to be a login form must not leave the dispatcher as content.
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
        // The real detector, not a mock: the dispatcher owning this check is the behaviour under test.
        dispatcher = new WebPageFetcherDispatcher(staticFetcher, renderFetcher,
                new LoginWallDetector(), properties);
        when(staticFetcher.fetch(any())).thenReturn(page("static"));
        when(renderFetcher.fetch(any())).thenReturn(page("rendered"));
    }

    @Test
    void shouldStayOnStaticFetchWhenTheSourceDidNotAskToRender() {
        WebPageFetcher.FetchedPage page = dispatcher.fetch(request(false));

        assertThat(page.body()).isEqualTo("static".getBytes(StandardCharsets.UTF_8));
        verify(staticFetcher).fetch(request(false));
        verify(renderFetcher, never()).fetch(any());
    }

    @Test
    void shouldRenderWhenTheSourceAskedAndTheMasterSwitchIsOn() {
        properties.getWebImport().getRender().setEnabled(true);

        WebPageFetcher.FetchedPage page = dispatcher.fetch(request(true));

        assertThat(page.body()).isEqualTo("rendered".getBytes(StandardCharsets.UTF_8));
        verify(renderFetcher).fetch(request(true));
        verify(staticFetcher, never()).fetch(any());
    }

    @Test
    void shouldFallBackToStaticWhenTheMasterSwitchIsOff() {
        // The safe default for an image without Chromium: a render_js source keeps syncing statically
        // rather than failing every pass.
        properties.getWebImport().getRender().setEnabled(false);

        WebPageFetcher.FetchedPage page = dispatcher.fetch(request(true));

        assertThat(page.body()).isEqualTo("static".getBytes(StandardCharsets.UTF_8));
        verify(staticFetcher).fetch(request(true));
        verify(renderFetcher, never()).fetch(any());
    }

    @Test
    void shouldRejectALoginWallInsteadOfReturningItAsContent() {
        // The M18 incident this guards against: a page behind auth answers 200 with a login form,
        // which then gets hashed, judged "changed" and indexed. The wall must die here, at the one
        // exit both fetch paths share.
        String loginForm = "<html><head><title>Log In - Confluence</title></head>"
                + "<body><form><input type=\"password\" name=\"pw\"/></form></body></html>";
        when(staticFetcher.fetch(any())).thenReturn(
                new WebPageFetcher.FetchedPage(loginForm.getBytes(StandardCharsets.UTF_8), "html", URL));

        assertThatThrownBy(() -> dispatcher.fetch(request(false)))
                .isInstanceOf(WebAuthException.class)
                .hasMessageContaining("登录页");
    }

    @Test
    void shouldPassAnOrdinaryPageThroughTheLoginWallCheck() {
        // An article ABOUT login pages must stay indexable: no password input, so a login-ish word
        // in the body alone must not trip the detector.
        String article = "<html><head><title>How single sign-on works</title></head>"
                + "<body>login walls explained</body></html>";
        when(staticFetcher.fetch(any())).thenReturn(
                new WebPageFetcher.FetchedPage(article.getBytes(StandardCharsets.UTF_8), "html", URL));

        assertThat(dispatcher.fetch(request(false)).body()).isNotEmpty();
    }

    private static WebPageFetcher.FetchRequest request(boolean renderJs) {
        return new WebPageFetcher.FetchRequest(URL, renderJs, null);
    }

    private WebPageFetcher.FetchedPage page(String body) {
        return new WebPageFetcher.FetchedPage(body.getBytes(StandardCharsets.UTF_8), "html", URL);
    }
}
