package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.entity.AuthToken;
import io.kbrag.domain.mapper.AuthTokenMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * One-time state values of the browser redirect single sign on flows.
 *
 * <p>The state is what ties a callback to a login this server actually started: without it, an
 * attacker can complete a flow with their own assertion inside the victim's browser, or replay a
 * captured callback. Each value is issued for one flow, checked once and deleted in the same
 * breath - a second presentation of the same state finds nothing.
 *
 * <p>Stored in {@code t_kb_auth_token} next to the session tokens rather than a table of its own:
 * same shape (a hashed random value with an expiry), same hygiene (only the digest is written),
 * same purge. The payload rides in the username column under a {@code sso:} prefix; a state
 * presented as a bearer token therefore resolves to an account name that cannot exist, and dies
 * at the principal lookup.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsoStateStore {

    /** Entropy of a state value in bytes, same as a session token. */
    private static final int STATE_BYTES = 32;

    /**
     * Minutes a pending flow may take. Long enough for a password plus a second factor at the
     * IdP, short enough that an abandoned browser tab is not a standing invitation.
     */
    private static final int STATE_TTL_MINUTES = 10;

    private final SecureRandom random = new SecureRandom();

    private final AuthTokenMapper authTokenMapper;

    /**
     * Issues a state value carrying an opaque payload for the callback.
     *
     * @param payload flow context returned verbatim by {@link #consume(String)}, at most the
     *                width of the username column
     * @return opaque state value for the redirect
     */
    public String issue(String payload) {
        byte[] raw = new byte[STATE_BYTES];
        random.nextBytes(raw);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        AuthToken record = new AuthToken();
        record.setTokenHash(HashUtil.sha256Hex(state));
        record.setUsername(payload);
        record.setExpiresAt(LocalDateTime.now().plusMinutes(STATE_TTL_MINUTES));
        authTokenMapper.insert(record);
        return state;
    }

    /**
     * Consumes a state value: at most one caller ever gets the payload back.
     *
     * <p>The row is deleted before the expiry is judged, so even an expired presentation burns
     * the value.
     *
     * @param state state value from the callback
     * @return payload of the pending flow, or empty when unknown, already used or expired
     */
    public Optional<String> consume(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        String stateHash = HashUtil.sha256Hex(state);
        AuthToken record = authTokenMapper.selectOne(new LambdaQueryWrapper<AuthToken>()
                .eq(AuthToken::getTokenHash, stateHash)
                .last("limit 1"));
        if (record == null) {
            return Optional.empty();
        }
        authTokenMapper.delete(new LambdaQueryWrapper<AuthToken>()
                .eq(AuthToken::getTokenHash, stateHash));
        if (record.getExpiresAt() == null || record.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.info("sso state expired before the callback arrived");
            return Optional.empty();
        }
        return Optional.of(record.getUsername());
    }
}
