package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.AuthToken;
import io.kbrag.domain.mapper.AuthTokenMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store of console session tokens: database backed with a write through in process cache.
 *
 * <p>Tokens used to live only in a process map, which ended every session on restart. They now
 * persist in {@code t_kb_auth_token} so a redeploy keeps users logged in, while the cache keeps the
 * per request {@link #resolve(String)} off the database. The console is a single instance
 * deployment, so the cache never goes stale against the table.
 *
 * <p>Tokens are opaque random values carried in the {@code Authorization} header, which makes the
 * session immune to cross site request forgery by construction. Only the SHA-256 digest of a token
 * is ever written to the database, mirroring the API key design.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenStore {

    /** Entropy of an issued token in bytes. */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** token digest -> session, avoids a database round trip on every management call. */
    private final Map<String, Session> cache = new ConcurrentHashMap<>();

    private final AuthTokenMapper authTokenMapper;

    private final KbProperties properties;

    /**
     * Issues a token for a user name.
     *
     * <p>Login is also the moment expired rows are purged: sessions only ever come into existence
     * here, so the table stays bounded without a scheduled job.
     *
     * @param username authenticated user
     * @return opaque token
     */
    public String issue(String username) {
        purgeExpired();
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenHash = HashUtil.sha256Hex(token);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(properties.getAuth().getTokenTtlHours());
        AuthToken record = new AuthToken();
        record.setTokenHash(tokenHash);
        record.setUsername(username);
        record.setExpiresAt(expiresAt);
        authTokenMapper.insert(record);
        cache.put(tokenHash, new Session(username, expiresAt));
        return token;
    }

    /**
     * Resolves the user behind a token, evicting it when expired.
     *
     * <p>A cache miss falls back to the table, which is what carries sessions across a restart.
     *
     * @param token token presented by the caller
     * @return user name, or empty when the token is unknown or expired
     */
    public Optional<String> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = HashUtil.sha256Hex(token);
        Session session = cache.get(tokenHash);
        if (session == null) {
            session = loadFromDatabase(tokenHash);
        }
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(LocalDateTime.now())) {
            cache.remove(tokenHash);
            deleteByHash(tokenHash);
            return Optional.empty();
        }
        return Optional.of(session.username());
    }

    /**
     * Invalidates a single token, used by an explicit logout.
     *
     * @param token token to drop
     */
    public void revoke(String token) {
        String tokenHash = HashUtil.sha256Hex(token);
        cache.remove(tokenHash);
        deleteByHash(tokenHash);
    }

    /**
     * Invalidates every token of a user, used after a password change.
     *
     * @param username user whose sessions must end
     */
    public void revokeAll(String username) {
        cache.entrySet().removeIf(entry -> entry.getValue().username().equals(username));
        authTokenMapper.delete(new LambdaQueryWrapper<AuthToken>()
                .eq(AuthToken::getUsername, username));
        log.info("sessions revoked, username={}", username);
    }

    private Session loadFromDatabase(String tokenHash) {
        AuthToken record = authTokenMapper.selectOne(new LambdaQueryWrapper<AuthToken>()
                .eq(AuthToken::getTokenHash, tokenHash)
                .last("limit 1"));
        if (record == null) {
            return null;
        }
        Session session = new Session(record.getUsername(), record.getExpiresAt());
        cache.put(tokenHash, session);
        return session;
    }

    private void deleteByHash(String tokenHash) {
        authTokenMapper.delete(new LambdaQueryWrapper<AuthToken>()
                .eq(AuthToken::getTokenHash, tokenHash));
    }

    private void purgeExpired() {
        LocalDateTime now = LocalDateTime.now();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        authTokenMapper.delete(new LambdaQueryWrapper<AuthToken>()
                .lt(AuthToken::getExpiresAt, now));
    }

    /**
     * One active session.
     *
     * @param username  authenticated user
     * @param expiresAt expiry instant
     */
    private record Session(String username, LocalDateTime expiresAt) {
    }
}
