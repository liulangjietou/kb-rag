package io.kbrag.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises a login wall pretending to be content, the M18 contract.
 *
 * <p>A page behind authentication answers an unauthenticated fetch with a login form <b>and HTTP
 * 200</b>, so without this check the pipeline files the form as the document: hash set, status
 * SUCCESS, indexed and retrievable - which is exactly the incident that motivated M18. Detection
 * runs at the single exit every fetch passes, so the static and the rendered path cannot drift.
 *
 * <p>Two signals, combined conservatively to keep an <i>article about</i> login pages indexable:
 * a password input in the body is conclusive on its own - content pages do not ask for passwords -
 * while the URL looking like a login endpoint only counts together with a login looking title.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class LoginWallDetector {

    /** Conclusive: a page whose body asks for a password is a form, not content. */
    private static final Pattern PASSWORD_INPUT = Pattern.compile(
            "<input[^>]{0,300}type\\s*=\\s*[\"']?password", Pattern.CASE_INSENSITIVE);

    /** Weak: the address the fetch ended on names a login endpoint. */
    private static final Pattern LOGIN_URL = Pattern.compile(
            "(?i)(^|[/.])(login|signin|sign-in|sso|cas|authorize)([/.?#]|$)");

    /** Weak: the document titles itself a login page. */
    private static final Pattern LOGIN_TITLE = Pattern.compile(
            "(?i)<title[^>]*>[^<]*(log\\s?in|sign\\s?in|登录|登入)");

    /** File extension of the only content this detector understands. */
    private static final String HTML_EXTENSION = "html";

    /**
     * Throws when a fetched page is a login wall rather than content.
     *
     * @param finalUrl  address the fetch ended on, redirects resolved
     * @param body      page bytes
     * @param extension mapped file extension; anything but html passes untouched
     */
    public void check(String finalUrl, byte[] body, String extension) {
        if (!HTML_EXTENSION.equalsIgnoreCase(extension)) {
            return;
        }
        String html = new String(body, StandardCharsets.UTF_8);
        boolean passwordInput = PASSWORD_INPUT.matcher(html).find();
        boolean loginUrl = finalUrl != null
                && LOGIN_URL.matcher(finalUrl.toLowerCase(Locale.ROOT)).find();
        Matcher title = LOGIN_TITLE.matcher(html);
        boolean loginTitle = title.find();
        if (passwordInput || (loginUrl && loginTitle)) {
            log.info("login wall detected, finalUrl={}, passwordInput={}, loginUrl={}, loginTitle={}",
                    finalUrl, passwordInput, loginUrl, loginTitle);
            throw new WebAuthException("抓取到登录页而非正文，内容未入库；请为该站点配置有效凭据（系统设置 → 站点凭据）");
        }
    }
}
