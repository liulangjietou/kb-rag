package io.kbrag.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two-signal rule: a password input alone condemns a page, a login looking URL alone
 * does not - it needs the title to agree. The false-positive cases matter as much as the hits,
 * because an over-eager detector would silently un-index every article that mentions logging in.
 *
 * @author owlzhangfq@gmail.com
 */
class LoginWallDetectorTest {

    private static final String CONTENT_URL = "https://wiki.example.com/pages/viewpage.action?pageId=1";

    private LoginWallDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LoginWallDetector();
    }

    @Test
    void shouldRejectAPageWithAPasswordInput() {
        // The real incident shape: Confluence serves the login form AT the content URL with a 200.
        String loginForm = "<html><head><title>Log In - Confluence</title></head>"
                + "<body><form action=\"/dologin.action\">"
                + "<input type=\"text\" name=\"os_username\"/>"
                + "<input type=\"password\" name=\"os_password\"/>"
                + "</form></body></html>";

        WebAuthException e = assertThrows(WebAuthException.class, () -> check(CONTENT_URL, loginForm));
        assertTrue(e.getMessage().contains("登录页"));
    }

    @Test
    void shouldRejectALoginUrlWhoseTitleAgrees() {
        // No password input in the static HTML (an SPA login renders the form via JS), but the
        // fetch ended on /login and the shell titles itself a login page: the two weak signals
        // together are enough.
        String spaShell = "<html><head><title>Sign in · GitLab</title></head>"
                + "<body><div id=\"app\"></div></body></html>";

        assertThrows(WebAuthException.class, () -> check("https://git.example.com/users/login", spaShell));
    }

    @Test
    void shouldKeepAnArticleAboutLoginPagesIndexable() {
        // Login-ish words in body and URL path alone must not condemn content: no password input,
        // and the title is not a login title.
        String article = "<html><head><title>Designing login walls well</title></head>"
                + "<body>A login wall is a page that asks users to sign in before content.</body></html>";

        assertDoesNotThrow(() -> check("https://blog.example.com/why-login-walls-hurt", article));
    }

    @Test
    void shouldIgnoreNonHtmlContent() {
        // A markdown or plain text fetch cannot be a login form; the detector must not even look.
        assertDoesNotThrow(() -> detector.check("https://example.com/notes.md",
                "type=password".getBytes(StandardCharsets.UTF_8), "md"));
    }

    private void check(String finalUrl, String html) {
        detector.check(finalUrl, html.getBytes(StandardCharsets.UTF_8), "html");
    }
}
