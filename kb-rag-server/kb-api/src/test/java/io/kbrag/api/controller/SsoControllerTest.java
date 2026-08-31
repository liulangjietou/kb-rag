package io.kbrag.api.controller;

import io.kbrag.api.security.ClientIpResolver;
import io.kbrag.app.auth.AuthService;
import io.kbrag.app.auth.LoginTicket;
import io.kbrag.app.auth.SsoStateStore;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.ExternalAuthOutcome;
import io.kbrag.domain.model.ExternalIdentity;
import io.kbrag.domain.port.CasValidator;
import io.kbrag.domain.port.OidcClient;
import io.kbrag.domain.port.SamlProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 固化浏览器 SSO 的公网回调地址与可信客户端地址解析边界。 */
class SsoControllerTest {

    private static final String PUBLIC_BASE_URL = "https://kb.example.com";
    private static final String OIDC_CALLBACK_URL = PUBLIC_BASE_URL + "/api/v1/auth/oidc/callback";
    private static final String SAML_ACS_URL = PUBLIC_BASE_URL + "/api/v1/auth/saml/acs";
    private static final String CAS_CALLBACK_URL = PUBLIC_BASE_URL + "/api/v1/auth/cas/callback";

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldUseTheTrustedResolverForExternalLoginAuditAddress() {
        AuthService authService = mock(AuthService.class);
        CasValidator casValidator = mock(CasValidator.class);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        KbProperties properties = new KbProperties();
        properties.getAuth().getSso().setWebBaseUrl("http://localhost:20002");
        SsoController controller = new SsoController(authService, mock(SsoStateStore.class),
                mock(OidcClient.class), mock(SamlProcessor.class), casValidator,
                properties, clientIpResolver);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/cas/callback");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(20003);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ExternalIdentity identity = new ExternalIdentity("alice", "Alice", null);
        when(casValidator.available()).thenReturn(true);
        when(casValidator.validate(
                eq("ticket"), eq("http://localhost:20002/api/v1/auth/cas/callback")))
                .thenReturn(ExternalAuthOutcome.success(identity));
        when(clientIpResolver.resolve(request)).thenReturn("198.51.100.17");
        when(authService.completeExternalLogin(UserSource.CAS, identity, "198.51.100.17"))
                .thenReturn(new LoginTicket("token", false));

        controller.casCallback("ticket", request);

        verify(clientIpResolver).resolve(request);
        verify(authService).completeExternalLogin(UserSource.CAS, identity, "198.51.100.17");
    }

    @Test
    void shouldUseConfiguredPublicOriginForEverySsoCallback() {
        SsoStateStore stateStore = mock(SsoStateStore.class);
        OidcClient oidcClient = mock(OidcClient.class);
        SamlProcessor samlProcessor = mock(SamlProcessor.class);
        CasValidator casValidator = mock(CasValidator.class);
        KbProperties properties = new KbProperties();
        properties.getAuth().getSso().setWebBaseUrl(PUBLIC_BASE_URL + "/");
        SsoController controller = new SsoController(mock(AuthService.class), stateStore,
                oidcClient, samlProcessor, casValidator, properties, mock(ClientIpResolver.class));

        // 模拟 TLS 在代理终止后的内部请求，并附带不可信转发头；协议地址只能来自显式公网配置。
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/oidc/login");
        request.setScheme("http");
        request.setServerName("internal-backend");
        request.setServerPort(20003);
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("X-Forwarded-Host", "attacker.example");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        when(oidcClient.available()).thenReturn(true);
        when(stateStore.issue("sso:OIDC")).thenReturn("oidc-state");
        when(oidcClient.authorizationUrl("oidc-state", OIDC_CALLBACK_URL))
                .thenReturn("https://idp.example/authorize");
        controller.oidcLogin();
        verify(oidcClient).authorizationUrl("oidc-state", OIDC_CALLBACK_URL);

        when(stateStore.consume("oidc-state")).thenReturn(Optional.of("sso:OIDC"));
        when(oidcClient.exchange("code", OIDC_CALLBACK_URL)).thenReturn(ExternalAuthOutcome.invalid());
        controller.oidcCallback("code", "oidc-state", request);
        verify(oidcClient).exchange("code", OIDC_CALLBACK_URL);

        when(samlProcessor.available()).thenReturn(true);
        when(stateStore.issue(anyString())).thenReturn("relay-state");
        when(samlProcessor.loginRedirectUrl(anyString(), eq("relay-state"), eq(SAML_ACS_URL)))
                .thenReturn("https://idp.example/saml");
        controller.samlLogin();
        verify(samlProcessor).loginRedirectUrl(anyString(), eq("relay-state"), eq(SAML_ACS_URL));

        when(stateStore.consume("relay-state")).thenReturn(Optional.of("sso:SAML:_request-id"));
        when(samlProcessor.consume("response", "_request-id", SAML_ACS_URL))
                .thenReturn(ExternalAuthOutcome.invalid());
        controller.samlAcs("response", "relay-state", request);
        verify(samlProcessor).consume("response", "_request-id", SAML_ACS_URL);

        when(casValidator.available()).thenReturn(true);
        when(casValidator.loginRedirectUrl(CAS_CALLBACK_URL)).thenReturn("https://idp.example/cas");
        controller.casLogin();
        verify(casValidator).loginRedirectUrl(CAS_CALLBACK_URL);

        when(casValidator.validate("ticket", CAS_CALLBACK_URL)).thenReturn(ExternalAuthOutcome.invalid());
        controller.casCallback("ticket", request);
        verify(casValidator).validate("ticket", CAS_CALLBACK_URL);
    }
}
