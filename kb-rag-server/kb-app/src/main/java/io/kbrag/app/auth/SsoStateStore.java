package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.entity.SsoState;
import io.kbrag.domain.mapper.SsoStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
 * <p>Only the digest is ever written, mirroring the API key design: the plaintext state lives only in
 * the redirect chain, so a database dump cannot be used to forge a callback.
 *
 * <p>这些值曾经借住在会话令牌表里，理由是形状相同、连过期清理都能顺带做掉。控制台会话改由 Sa-Token 托管
 * 之后那张表不复存在，state 也就搬进了自己的表——它本来也不是会话，而是一次登录流程中途的一张票。
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

    private final SsoStateMapper ssoStateMapper;

    /**
     * Issues a state value carrying an opaque payload for the callback.
     *
     * @param payload flow context returned verbatim by {@link #consume(String)}, at most the
     *                width of the payload column
     * @return opaque state value for the redirect
     */
    public String issue(String payload) {
        byte[] raw = new byte[STATE_BYTES];
        random.nextBytes(raw);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        SsoState record = new SsoState();
        record.setStateHash(HashUtil.sha256Hex(state));
        record.setPayload(payload);
        record.setExpiresAt(LocalDateTime.now().plusMinutes(STATE_TTL_MINUTES));
        ssoStateMapper.insert(record);
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
        SsoState record = ssoStateMapper.selectOne(new LambdaQueryWrapper<SsoState>()
                .eq(SsoState::getStateHash, stateHash)
                .last("limit 1"));
        if (record == null) {
            return Optional.empty();
        }
        ssoStateMapper.delete(new LambdaQueryWrapper<SsoState>()
                .eq(SsoState::getStateHash, stateHash));
        if (record.getExpiresAt() == null || record.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.info("sso state expired before the callback arrived");
            return Optional.empty();
        }
        return Optional.of(record.getPayload());
    }

    /**
     * 回收没走完的流程留下的 state。
     *
     * <p>过去这件事由会话令牌表的登录时清理顺带完成；表拆开之后需要自己做。走完的流程在回调时就把行删了，
     * 因此这里清掉的都是用户中途放弃的登录，本就没有读者。
     */
    @Scheduled(cron = "${kb.auth.sso.state-purge-cron:0 20 * * * *}")
    public void purgeExpired() {
        int purged = ssoStateMapper.purgeExpired();
        if (purged > 0) {
            log.info("expired sso states purged, rows={}", purged);
        }
    }
}
