package io.kbrag.app.auth;

import io.kbrag.domain.config.KbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.security.SecureRandom;

/**
 * In memory store of console session tokens.
 *
 * <p>The console is a single instance deployment in M1, so an in process map is enough and avoids a
 * Redis dependency. Tokens are opaque random values carried in the {@code Authorization} header,
 * which makes the session immune to cross site request forgery by construction.
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

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private final KbProperties properties;

    /**
     * Issues a token for a user name.
     *
     * @param username authenticated user
     * @return opaque token
     */
    public String issue(String username) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = Instant.now().plusSeconds(properties.getAuth().getTokenTtlHours() * 3600L);
        sessions.put(token, new Session(username, expiresAt));
        return token;
    }

    /**
     * Resolves the user behind a token, evicting it when expired.
     *
     * @param token token presented by the caller
     * @return user name, or empty when the token is unknown or expired
     */
    public Optional<String> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
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
        sessions.remove(token);
    }

    /**
     * Invalidates every token of a user, used after a password change.
     *
     * @param username user whose sessions must end
     */
    public void revokeAll(String username) {
        sessions.entrySet().removeIf(entry -> entry.getValue().username().equals(username));
        log.info("sessions revoked, username={}", username);
    }

    /**
     * One active session.
     *
     * @param username authenticated user
     * @param expiresAt expiry instant
     */
    private record Session(String username, Instant expiresAt) {
    }
}
