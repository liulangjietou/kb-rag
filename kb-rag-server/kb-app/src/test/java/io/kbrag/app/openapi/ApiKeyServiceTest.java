package io.kbrag.app.openapi;

import io.kbrag.app.appcenter.AppService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.ApiKey;
import io.kbrag.domain.enums.ApiKeyStatus;
import io.kbrag.domain.mapper.ApiKeyMapper;
import io.kbrag.domain.service.ApiKeyFactory;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers API key issuance, authentication and the authorisation scope of requirement section 4.8: only the
 * digest is stored, a rotation invalidates the previous secret, and the two rejection codes stay distinct.
 *
 * @author owlzhangfq@gmail.com
 */
class ApiKeyServiceTest {

    private static final String KEY_ID = "ak_1";
    private static final String APP_ID = "app_1";

    private ApiKeyMapper apiKeyMapper;
    private AppService appService;
    private BizIdGenerator bizIdGenerator;
    private ApiKeyFactory apiKeyFactory;
    private KbProperties properties;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        apiKeyMapper = mock(ApiKeyMapper.class);
        appService = mock(AppService.class);
        bizIdGenerator = mock(BizIdGenerator.class);
        apiKeyFactory = new ApiKeyFactory();
        properties = new KbProperties();
        when(bizIdGenerator.apiKeyId()).thenReturn(KEY_ID);
        // The audit executor runs inline so the last-used write this suite observes has already happened
        // when authenticate returns; that it leaves the request thread in production is asserted separately.
        service = new ApiKeyService(apiKeyMapper, apiKeyFactory, appService, bizIdGenerator, properties,
                Runnable::run);
    }

    @Test
    void shouldStoreOnlyTheDigestAndReturnThePlaintextOnce() {
        ApiKeyService.IssuedKey issued = service.create("agent", 5, List.of());

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyMapper).insert(captor.capture());
        ApiKey stored = captor.getValue();
        assertEquals(apiKeyFactory.hash(issued.plaintext()), stored.getKeyHash());
        assertNotEquals(issued.plaintext(), stored.getKeyHash());
        assertTrue(issued.plaintext().startsWith("kb-sk-"));
        assertEquals(ApiKeyStatus.ENABLED, stored.getStatus());
        assertEquals(5, stored.getQpsLimit());
        // An empty scope means "every application" and is stored as null rather than as an empty array.
        assertEquals(null, stored.getAppScope());
    }

    @Test
    void shouldFallBackToTheDeploymentQuotaWhenNoneWasGiven() {
        properties.getOpenApi().setRateLimitDefault(7);

        service.create("agent", null, null);

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyMapper).insert(captor.capture());
        assertEquals(7, captor.getValue().getQpsLimit());
    }

    @Test
    void shouldRejectAScopeNamingAnApplicationThatDoesNotExist() {
        when(appService.require("app_missing"))
                .thenThrow(new BizException(ErrorCode.APP_NOT_FOUND, "application not found"));

        BizException e = assertThrows(BizException.class,
                () -> service.create("agent", 5, List.of("app_missing")));

        assertEquals(ErrorCode.APP_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void shouldAuthenticateAKeyByItsDigest() {
        ApiKeyService.IssuedKey issued = service.create("agent", 5, List.of());
        when(apiKeyMapper.selectOne(any())).thenReturn(issued.key());

        ApiKeyPrincipal principal = service.authenticate(issued.plaintext());

        assertEquals(KEY_ID, principal.getKeyId());
        assertEquals(5, principal.getQpsLimit());
        assertTrue(principal.unrestricted());
    }

    @Test
    void shouldRejectAMalformedKeyWithoutTouchingTheDatabase() {
        BizException e = assertThrows(BizException.class, () -> service.authenticate("not-a-key"));

        assertEquals(ErrorCode.INVALID_API_KEY, e.getErrorCode());
        verify(apiKeyMapper, org.mockito.Mockito.never()).selectOne(any());
    }

    @Test
    void shouldRejectAnUnknownKey() {
        ApiKeyService.IssuedKey issued = service.create("agent", 5, List.of());
        when(apiKeyMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.authenticate(issued.plaintext()));

        assertEquals(ErrorCode.INVALID_API_KEY, e.getErrorCode());
    }

    @Test
    void shouldTellADisabledKeyApartFromAnUnknownOne() {
        ApiKeyService.IssuedKey issued = service.create("agent", 5, List.of());
        issued.key().setStatus(ApiKeyStatus.DISABLED);
        when(apiKeyMapper.selectOne(any())).thenReturn(issued.key());

        BizException e = assertThrows(BizException.class, () -> service.authenticate(issued.plaintext()));

        assertEquals(ErrorCode.API_KEY_DISABLED, e.getErrorCode());
        assertEquals(401, e.getErrorCode().getHttpStatus());
    }

    @Test
    void shouldReplaceTheDigestOnRotationSoTheOldSecretStopsMatching() {
        ApiKeyService.IssuedKey original = service.create("agent", 5, List.of());
        String originalHash = original.key().getKeyHash();
        when(apiKeyMapper.selectOne(any())).thenReturn(original.key());

        ApiKeyService.IssuedKey rotated = service.rotate(KEY_ID);

        assertNotEquals(originalHash, rotated.key().getKeyHash());
        assertEquals(apiKeyFactory.hash(rotated.plaintext()), rotated.key().getKeyHash());
        assertNotEquals(original.plaintext(), rotated.plaintext());
        // The row keeps its identity, quota and name: a rotation replaces the secret and nothing else.
        assertEquals(KEY_ID, rotated.key().getKeyId());
        assertEquals(5, rotated.key().getQpsLimit());
        assertNotEquals(originalHash, apiKeyFactory.hash(rotated.plaintext()));
        assertFalse(apiKeyFactory.hash(original.plaintext()).equals(rotated.key().getKeyHash()));
    }

    @Test
    void shouldParseTheAuthorisationScopeOfAStoredKey() {
        ApiKey key = new ApiKey();
        key.setAppScope(JsonUtil.toJson(List.of(APP_ID, "app_2")));

        assertEquals(List.of(APP_ID, "app_2"), service.scopeOf(key));
        assertEquals(List.of(), service.scopeOf(new ApiKey()));
    }

    @Test
    void shouldDenyAnApplicationOutsideTheScopeAndAllowOneInsideIt() {
        ApiKeyPrincipal scoped = new ApiKeyPrincipal(KEY_ID, "agent", 5, List.of(APP_ID));

        assertDoesNotThrow(() -> scoped.requireAccessTo(APP_ID));
        BizException e = assertThrows(BizException.class, () -> scoped.requireAccessTo("app_other"));
        assertEquals(ErrorCode.APP_ACCESS_DENIED, e.getErrorCode());
        assertEquals(403, e.getErrorCode().getHttpStatus());
    }

    @Test
    void shouldTreatAnEmptyScopeAsAccessToEveryApplication() {
        ApiKeyPrincipal unrestricted = new ApiKeyPrincipal(KEY_ID, "agent", 5, List.of());

        assertDoesNotThrow(() -> unrestricted.requireAccessTo("app_anything"));
        assertTrue(unrestricted.unrestricted());
    }
}
