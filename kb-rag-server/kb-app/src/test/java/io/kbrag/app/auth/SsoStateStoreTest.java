package io.kbrag.app.auth;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.entity.SsoState;
import io.kbrag.domain.mapper.SsoStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the one-time state of the SSO redirect flows, the M16 contract section 4.6: a state value
 * pays out its payload to exactly one caller, an expired presentation still burns the row, and only
 * the digest of the value ever reaches the table.
 *
 * <p>The mapper mock is stateful - insert fills a holder, delete empties it, selectOne reads it -
 * because one-time-ness is precisely the property that a second lookup finds nothing after the
 * first consumption, which a stateless stub cannot express.
 *
 * @author owlzhangfq@gmail.com
 */
class SsoStateStoreTest {

    private static final String PAYLOAD = "sso:oidc";

    private SsoStateMapper ssoStateMapper;
    private SsoStateStore store;

    /** The single state row the mapper mock persists between issue and consume. */
    private final AtomicReference<SsoState> row = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(SsoState.class);
        ssoStateMapper = mock(SsoStateMapper.class);
        row.set(null);
        when(ssoStateMapper.insert(any(SsoState.class))).thenAnswer(invocation -> {
            row.set(invocation.getArgument(0));
            return 1;
        });
        when(ssoStateMapper.selectOne(any())).thenAnswer(invocation -> row.get());
        when(ssoStateMapper.delete(any())).thenAnswer(invocation -> {
            row.set(null);
            return 1;
        });
        store = new SsoStateStore(ssoStateMapper);
    }

    @Test
    void shouldStoreOnlyTheDigestOfTheIssuedState() {
        String state = store.issue(PAYLOAD);

        ArgumentCaptor<SsoState> inserted = ArgumentCaptor.forClass(SsoState.class);
        verify(ssoStateMapper).insert(inserted.capture());
        SsoState record = inserted.getValue();
        // The raw value only ever travels in the redirect; a table dump must not be enough to
        // complete somebody else's login flow.
        assertNotEquals(state, record.getStateHash());
        assertEquals(HashUtil.sha256Hex(state), record.getStateHash());
        assertEquals(PAYLOAD, record.getPayload());
        assertTrue(record.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void shouldPayThePayloadOutExactlyOnce() {
        String state = store.issue(PAYLOAD);

        assertEquals(Optional.of(PAYLOAD), store.consume(state));
        // The second presentation of the same state is the replayed callback the store exists to
        // stop: the first consumption deleted the row, so this one finds nothing.
        assertEquals(Optional.empty(), store.consume(state));
    }

    @Test
    void shouldBurnAnExpiredStateOnPresentation() {
        String state = store.issue(PAYLOAD);
        row.get().setExpiresAt(LocalDateTime.now().minusMinutes(1));

        assertEquals(Optional.empty(), store.consume(state));

        // The row is deleted before the expiry is judged: even a rejected presentation ends the
        // flow, so the attacker gains nothing by keeping an old state around.
        verify(ssoStateMapper).delete(any());
        assertNull(row.get());
    }

    @Test
    void shouldRejectAnUnknownStateWithoutDeletingAnything() {
        assertEquals(Optional.empty(), store.consume("never-issued"));

        verify(ssoStateMapper, never()).delete(any());
    }

    @Test
    void shouldRejectABlankStateWithoutTouchingTheTable() {
        assertEquals(Optional.empty(), store.consume(null));
        assertEquals(Optional.empty(), store.consume("  "));

        verifyNoInteractions(ssoStateMapper);
    }
}
