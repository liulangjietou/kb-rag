package io.kbrag.api.controller;

import io.kbrag.api.dto.SsoProvidersResponse;
import io.kbrag.app.auth.AuthService;
import io.kbrag.app.auth.LoginTicket;
import io.kbrag.app.auth.SsoStateStore;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.DirectoryBindResult;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.ExternalAuthOutcome;
import io.kbrag.domain.port.CasValidator;
import io.kbrag.domain.port.OidcClient;
import io.kbrag.domain.port.SamlProcessor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Browser redirect single sign on: OIDC, SAML 2.0 and CAS entry points and callbacks.
 *
 * <p>Every endpoint here is necessarily unauthenticated - they exist so a person without a session
 * can get one. What protects them instead is the one-time state each flow carries (CAS relies on
 * its server validated one-time ticket), verified assertions at the protocol adapters, and the
 * source discipline of the account landing in {@code AuthService}.
 *
 * <p>Callbacks answer with a redirect to the console login page, the verdict in the URL fragment:
 * a fragment never leaves the browser, so the session token shows up in no access log on the way.
 * Failures travel the same way as a human readable message - the person is standing in a browser
 * mid-redirect, and a JSON envelope would strand them on a blank API page.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class SsoController {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String FORWARDED_SEPARATOR = ",";
    private static final String OIDC_CALLBACK_PATH = "/api/v1/auth/oidc/callback";
    private static final String SAML_ACS_PATH = "/api/v1/auth/saml/acs";
    private static final String CAS_CALLBACK_PATH = "/api/v1/auth/cas/callback";
    /** Payload of a pending OIDC flow; the protocol needs no more context than its own name. */
    private static final String STATE_OIDC = "sso:OIDC";
    /** Payload prefix of a pending SAML flow, followed by the AuthnRequest id to match. */
    private static final String STATE_SAML_PREFIX = "sso:SAML:";
    private static final String TOKEN_FRAGMENT = "/login#sso_token=";
    private static final String ERROR_FRAGMENT = "/login#sso_error=";

    private static final String MSG_NOT_ENABLED = "该单点登录方式未启用";
    private static final String MSG_STATE_LOST = "登录状态已失效，请重新发起单点登录";
    private static final String MSG_REJECTED = "单点登录校验未通过，请重新登录";
    private static final String MSG_PROVIDER_DOWN = "身份提供方暂时不可用，请稍后重试";

    private final AuthService authService;
    private final SsoStateStore stateStore;
    private final OidcClient oidcClient;
    private final SamlProcessor samlProcessor;
    private final CasValidator casValidator;
    private final KbProperties properties;

    /**
     * Tells the login page which single sign on buttons to render.
     *
     * @return availability of each redirect protocol
     */
    @GetMapping("/sso/providers")
    public Result<SsoProvidersResponse> providers() {
        return Result.success(new SsoProvidersResponse(
                oidcClient.available(), samlProcessor.available(), casValidator.available()));
    }

    /**
     * Starts an OIDC flow: issues a state and sends the browser to the authorization endpoint.
     *
     * @return redirect to the identity provider
     */
    @GetMapping("/oidc/login")
    public ResponseEntity<Void> oidcLogin() {
        requireAvailable(oidcClient.available());
        String state = stateStore.issue(STATE_OIDC);
        return redirect(oidcClient.authorizationUrl(state, callbackUrl(OIDC_CALLBACK_PATH)));
    }

    /**
     * Lands an OIDC callback: checks the state, exchanges the code and issues a session.
     *
     * @param code    authorization code, absent when the IdP reports an error instead
     * @param state   anti forgery value issued by {@link #oidcLogin()}
     * @param servlet servlet request, source address for the audit
     * @return redirect to the console with the verdict in the fragment
     */
    @GetMapping("/oidc/callback")
    public ResponseEntity<Void> oidcCallback(@RequestParam(required = false) String code,
                                             @RequestParam(required = false) String state,
                                             HttpServletRequest servlet) {
        Optional<String> payload = stateStore.consume(state);
        if (payload.isEmpty() || !STATE_OIDC.equals(payload.get())) {
            // Expired, replayed or forged: all indistinguishable on purpose, and none of them is
            // attributable to an account, so the failure is logged but not written to the audit.
            log.info("oidc callback rejected, state unknown or already used");
            return errorRedirect(MSG_STATE_LOST);
        }
        if (code == null || code.isBlank()) {
            log.info("oidc callback carries no code, idp reported an error upstream");
            return errorRedirect(MSG_REJECTED);
        }
        ExternalAuthOutcome outcome = oidcClient.exchange(code, callbackUrl(OIDC_CALLBACK_PATH));
        return land(UserSource.OIDC, outcome, servlet);
    }

    /**
     * Starts a SAML flow: issues a state bound to the AuthnRequest id and redirects to the IdP.
     *
     * @return redirect to the identity provider
     */
    @GetMapping("/saml/login")
    public ResponseEntity<Void> samlLogin() {
        requireAvailable(samlProcessor.available());
        // AuthnRequest ids must be XML NCNames, which may not start with a digit; the underscore
        // prefix is the convention every SAML implementation uses for exactly this reason.
        String requestId = "_" + UUID.randomUUID();
        String relayState = stateStore.issue(STATE_SAML_PREFIX + requestId);
        return redirect(samlProcessor.loginRedirectUrl(requestId, relayState, callbackUrl(SAML_ACS_PATH)));
    }

    /**
     * Lands a posted SAML response at the assertion consumer service.
     *
     * @param samlResponse base64 encoded response, form field name fixed by the SAML binding
     * @param relayState   anti forgery value issued by {@link #samlLogin()}
     * @param servlet      servlet request, source address for the audit
     * @return redirect to the console with the verdict in the fragment
     */
    @PostMapping("/saml/acs")
    public ResponseEntity<Void> samlAcs(@RequestParam(name = "SAMLResponse", required = false) String samlResponse,
                                        @RequestParam(name = "RelayState", required = false) String relayState,
                                        HttpServletRequest servlet) {
        Optional<String> payload = stateStore.consume(relayState);
        if (payload.isEmpty() || !payload.get().startsWith(STATE_SAML_PREFIX)) {
            log.info("saml callback rejected, relay state unknown or already used");
            return errorRedirect(MSG_STATE_LOST);
        }
        if (samlResponse == null || samlResponse.isBlank()) {
            log.info("saml callback carries no response document");
            return errorRedirect(MSG_REJECTED);
        }
        String expectedRequestId = payload.get().substring(STATE_SAML_PREFIX.length());
        ExternalAuthOutcome outcome =
                samlProcessor.consume(samlResponse, expectedRequestId, callbackUrl(SAML_ACS_PATH));
        return land(UserSource.SAML, outcome, servlet);
    }

    /**
     * Starts a CAS flow: sends the browser to the CAS login page.
     *
     * <p>No state of our own: the CAS ticket is itself one-time and bound to the service URL by
     * the server side validation, which is the anti forgery property the other flows build with a
     * state value.
     *
     * @return redirect to the CAS server
     */
    @GetMapping("/cas/login")
    public ResponseEntity<Void> casLogin() {
        requireAvailable(casValidator.available());
        return redirect(casValidator.loginRedirectUrl(callbackUrl(CAS_CALLBACK_PATH)));
    }

    /**
     * Lands a CAS callback: validates the ticket server to server and issues a session.
     *
     * @param ticket  service ticket, absent when the person cancelled at the CAS login page
     * @param servlet servlet request, source address for the audit
     * @return redirect to the console with the verdict in the fragment
     */
    @GetMapping("/cas/callback")
    public ResponseEntity<Void> casCallback(@RequestParam(required = false) String ticket,
                                            HttpServletRequest servlet) {
        if (!casValidator.available()) {
            return errorRedirect(MSG_NOT_ENABLED);
        }
        if (ticket == null || ticket.isBlank()) {
            log.info("cas callback carries no ticket");
            return errorRedirect(MSG_REJECTED);
        }
        ExternalAuthOutcome outcome = casValidator.validate(ticket, callbackUrl(CAS_CALLBACK_PATH));
        return land(UserSource.CAS, outcome, servlet);
    }

    /**
     * Turns a protocol verdict into the console redirect, opening the session when it verified.
     */
    private ResponseEntity<Void> land(UserSource source, ExternalAuthOutcome outcome,
                                      HttpServletRequest servlet) {
        if (outcome.result() == DirectoryBindResult.SERVICE_UNAVAILABLE) {
            return errorRedirect(MSG_PROVIDER_DOWN);
        }
        if (outcome.result() != DirectoryBindResult.SUCCESS) {
            return errorRedirect(MSG_REJECTED);
        }
        try {
            LoginTicket ticket = authService.completeExternalLogin(source, outcome.identity(), clientIp(servlet));
            return redirect(webBaseUrl() + TOKEN_FRAGMENT
                    + URLEncoder.encode(ticket.token(), StandardCharsets.UTF_8));
        } catch (BizException e) {
            // Wrong entry point, disabled account, locked account: already audited by the landing,
            // and the person deserves the reason rather than a generic failure.
            return errorRedirect(e.getMessage());
        }
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    private ResponseEntity<Void> errorRedirect(String message) {
        return redirect(webBaseUrl() + ERROR_FRAGMENT
                + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    /**
     * Absolute callback URL derived from the current request.
     *
     * <p>Derived rather than configured: the IdP already pins the acceptable callback on its own
     * side, and a second copy in our configuration would be one more value to drift. Behind a
     * reverse proxy the derivation follows the forwarded headers the server is told to trust.
     */
    private String callbackUrl(String path) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).toUriString();
    }

    private String webBaseUrl() {
        String webBaseUrl = properties.getAuth().getSso().getWebBaseUrl();
        return webBaseUrl.endsWith("/")
                ? webBaseUrl.substring(0, webBaseUrl.length() - 1)
                : webBaseUrl;
    }

    private void requireAvailable(boolean available) {
        if (!available) {
            throw BizException.invalidParam(MSG_NOT_ENABLED);
        }
    }

    /**
     * Resolves the caller address, honouring a single reverse proxy hop, same as the login endpoint.
     */
    private String clientIp(HttpServletRequest servlet) {
        String forwarded = servlet.getHeader(HEADER_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(FORWARDED_SEPARATOR)[0].trim();
        }
        return servlet.getRemoteAddr();
    }
}
