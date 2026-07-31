package io.kbrag.app.websource;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.WebCredential;
import io.kbrag.domain.enums.WebAuthType;
import io.kbrag.domain.mapper.WebCredentialMapper;
import io.kbrag.domain.model.FetchCredential;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the credential life cycle rules that guard the secret: the per-type shape gate, the
 * keep-on-blank update semantics that let the console flip a switch without holding the password,
 * and the resolution that turns a stored row into the one header a fetcher may inject.
 *
 * @author owlzhangfq@gmail.com
 */
class WebCredentialServiceTest {

    private static final String CREDENTIAL_ID = "wcred_1";
    private static final String HOST = "wiki.example.com";

    private WebCredentialMapper webCredentialMapper;
    private BizIdGenerator bizIdGenerator;
    private WebCredentialService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(WebCredential.class);
        webCredentialMapper = mock(WebCredentialMapper.class);
        bizIdGenerator = mock(BizIdGenerator.class);
        service = new WebCredentialService(webCredentialMapper, bizIdGenerator);
        when(bizIdGenerator.webCredentialId()).thenReturn(CREDENTIAL_ID);
    }

    @Test
    void shouldCreateABasicCredentialWithTheHostLowerCased() {
        when(webCredentialMapper.selectCount(any())).thenReturn(0L);

        WebCredential created = service.create("Wiki.Example.COM", WebAuthType.BASIC,
                "reader", "secret", null, true);

        verify(webCredentialMapper).insert(created);
        assertEquals(HOST, created.getHost());
        assertEquals("reader", created.getUsername());
        assertNull(created.getHeaderName());
    }

    @Test
    void shouldRejectASecondCredentialOfTheSameHost() {
        when(webCredentialMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () ->
                service.create(HOST, WebAuthType.BASIC, "reader", "secret", null, true));
        verify(webCredentialMapper, never()).insert(any(WebCredential.class));
    }

    @Test
    void shouldRejectAHostCarryingAPathOrScheme() {
        assertThrows(BizException.class, () ->
                service.create("https://wiki.example.com", WebAuthType.BASIC, "u", "s", null, true));
        assertThrows(BizException.class, () ->
                service.create("wiki.example.com/path", WebAuthType.BASIC, "u", "s", null, true));
    }

    @Test
    void shouldEnforceThePerTypeShape() {
        when(webCredentialMapper.selectCount(any())).thenReturn(0L);

        // BASIC without a username is unusable; HEADER without a header name equally so.
        assertThrows(BizException.class, () ->
                service.create(HOST, WebAuthType.BASIC, null, "secret", null, true));
        assertThrows(BizException.class, () ->
                service.create(HOST, WebAuthType.HEADER, null, "Bearer x", null, true));
    }

    @Test
    void shouldKeepTheStoredSecretWhenTheUpdateSendsABlankOne() {
        // The console edits the enabled flag without re-typing the password; a blank secret must
        // mean "keep", never "replace with empty".
        WebCredential stored = basicRow();
        when(webCredentialMapper.selectOne(any())).thenReturn(stored);

        WebCredential updated = service.update(CREDENTIAL_ID, null, " ", null, false);

        assertEquals("secret", updated.getSecret());
        assertEquals(0, updated.getEnabled());
        verify(webCredentialMapper).updateById(stored);
    }

    @Test
    void shouldResolveABasicRowIntoThePreEncodedAuthorizationHeader() {
        WebCredential stored = basicRow();
        when(webCredentialMapper.selectOne(any())).thenReturn(stored);

        FetchCredential resolved = service.resolveFor(HOST);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("reader:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals("Authorization", resolved.headerName());
        assertEquals(expected, resolved.headerValue());
        assertEquals(HOST, resolved.host());
    }

    @Test
    void shouldResolveNothingForAnUnknownOrBlankHost() {
        when(webCredentialMapper.selectOne(any())).thenReturn(null);

        assertNull(service.resolveFor("unknown.example.com"));
        assertNull(service.resolveFor(null));
        assertNull(service.resolveFor(" "));
    }

    private WebCredential basicRow() {
        WebCredential credential = new WebCredential();
        credential.setId(1L);
        credential.setCredentialId(CREDENTIAL_ID);
        credential.setHost(HOST);
        credential.setAuthType(WebAuthType.BASIC);
        credential.setUsername("reader");
        credential.setSecret("secret");
        credential.setEnabled(1);
        return credential;
    }
}
