package io.kbrag.app.websource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.WebCredential;
import io.kbrag.domain.enums.WebAuthType;
import io.kbrag.domain.mapper.WebCredentialMapper;
import io.kbrag.domain.model.FetchCredential;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * <p>Since V22 it also covers the tenant boundary, which is enforced two different ways and only
 * one of them is visible from here. The console methods rely on the MyBatis-Plus row level fence,
 * which no unit test can exercise without a database - what is pinned below is that they own no
 * second path around it. The fetch side is pure service code and is pinned directly.
 *
 * @author owlzhangfq@gmail.com
 */
class WebCredentialServiceTest {

    private static final String CREDENTIAL_ID = "wcred_1";
    private static final String HOST = "wiki.example.com";
    private static final String TENANT_ID = "tnt_acme0000000001";

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

        FetchCredential resolved = service.resolveFor(TENANT_ID, HOST);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("reader:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals("Authorization", resolved.headerName());
        assertEquals(expected, resolved.headerValue());
        assertEquals(HOST, resolved.host());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNarrowTheFetchLookupToTheAskingTenant() {
        // The heart of the V22 fix. This lookup runs on the scheduled sync thread, where the row
        // level fence is off (no console principal), so the tenant has to be in the predicate the
        // service writes itself. Without it the query matches on host alone and happily returns
        // another tenant's row - which is the fetcher receiving somebody else's password.
        when(webCredentialMapper.selectOne(any())).thenReturn(basicRow());

        service.resolveFor(TENANT_ID, HOST);

        ArgumentCaptor<LambdaQueryWrapper<WebCredential>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(webCredentialMapper).selectOne(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("tenant_id"),
                "the fetch side lookup must carry a tenant predicate of its own");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(TENANT_ID),
                "the tenant predicate must bind the tenant that was asked for");
    }

    @Test
    void shouldRefuseToLookAnythingUpWithoutATenant() {
        // "Tenant unknown" must resolve to no credential, never to a host-only query. A registration
        // whose knowledge base was deleted arrives here with a null tenant, and the one thing that
        // must not happen is it picking up whichever tenant happens to hold that host.
        assertNull(service.resolveFor(null, HOST));
        assertNull(service.resolveFor(" ", HOST));
        verify(webCredentialMapper, never()).selectOne(any());
    }

    @Test
    void shouldResolveNothingForAnUnknownOrBlankHost() {
        when(webCredentialMapper.selectOne(any())).thenReturn(null);

        assertNull(service.resolveFor(TENANT_ID, "unknown.example.com"));
        assertNull(service.resolveFor(TENANT_ID, null));
        assertNull(service.resolveFor(TENANT_ID, " "));
    }

    @Test
    void shouldNotFindACredentialTheFenceFilteredAway() {
        // The console side carries no tenant argument at all: t_kb_web_credential is in
        // FENCED_TABLES, so another tenant's row is not in the result set to begin with and this
        // lookup comes back empty. What is pinned here is that the service then stops - it has no
        // second, unfenced way of locating the row by credential id.
        when(webCredentialMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.update(CREDENTIAL_ID, "u", "s", null, true));
        assertThrows(BizException.class, () -> service.remove(CREDENTIAL_ID));
        verify(webCredentialMapper, never()).updateById(any(WebCredential.class));
        verify(webCredentialMapper, never()).hardDeleteById(any());
    }

    private WebCredential basicRow() {
        WebCredential credential = new WebCredential();
        credential.setId(1L);
        credential.setCredentialId(CREDENTIAL_ID);
        credential.setTenantId(TENANT_ID);
        credential.setHost(HOST);
        credential.setAuthType(WebAuthType.BASIC);
        credential.setUsername("reader");
        credential.setSecret("secret");
        credential.setEnabled(1);
        return credential;
    }
}
