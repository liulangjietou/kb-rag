package io.kbrag.app.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 登录滑块的 challenge -> 轨迹校验 -> 一次性 proof 准入链路。
 *
 * <p>challenge 和 proof 都只在进程内短期保存摘要。验证和消费先原子删除，再检查指纹或
 * 轨迹，因此不论成功、指纹不匹配还是轨迹错误，已经提交的值都不能重放。该机制用于提高
 * 批量撞库的成本，不代替网关限流或强身份认证。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class LoginCaptchaService {

    static final int TRACK_SCALE = 1_000;
    static final int MIN_TRACK_POINTS = 3;
    static final int MAX_TRACK_POINTS = 200;
    static final int START_X_TOLERANCE = 30;
    static final int TARGET_X_TOLERANCE = 25;
    static final int MIN_Y = -100;
    static final int MAX_Y = 100;
    static final long MIN_DURATION_MS = 300L;
    static final long MAX_DURATION_MS = 15_000L;
    static final long MIN_SERVER_DURATION_NANOS = Duration.ofMillis(MIN_DURATION_MS).toNanos();

    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(2);
    private static final Duration PROOF_TTL = Duration.ofMinutes(1);
    private static final int MAX_CHALLENGES = 10_000;
    private static final int MAX_PROOFS = 10_000;
    private static final int RANDOM_BYTES = 32;
    private static final int TOKEN_LENGTH = 43;
    private static final String FINGERPRINT_SEPARATOR = "\n";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final LoginCaptchaRateLimiter rateLimiter;
    private final SecureRandom secureRandom;
    private final Ticker ticker;
    private final LoginCaptchaPuzzleGenerator puzzleGenerator;
    private final Cache<String, ChallengeState> challenges;
    private final Cache<String, ProofState> proofs;
    private final Object challengeCapacityLock = new Object();
    private final Object proofCapacityLock = new Object();
    private final int maxChallenges;
    private final int maxProofs;

    @Autowired
    public LoginCaptchaService(LoginCaptchaRateLimiter rateLimiter) {
        this(rateLimiter, new SecureRandom(), Ticker.systemTicker());
    }

    LoginCaptchaService(LoginCaptchaRateLimiter rateLimiter, SecureRandom secureRandom, Ticker ticker) {
        this(rateLimiter, secureRandom, ticker, new LoginCaptchaPuzzleGenerator(secureRandom));
    }

    LoginCaptchaService(LoginCaptchaRateLimiter rateLimiter, SecureRandom secureRandom, Ticker ticker,
                        LoginCaptchaPuzzleGenerator puzzleGenerator) {
        this(rateLimiter, secureRandom, ticker, puzzleGenerator, MAX_CHALLENGES, MAX_PROOFS);
    }

    LoginCaptchaService(LoginCaptchaRateLimiter rateLimiter, SecureRandom secureRandom, Ticker ticker,
                        LoginCaptchaPuzzleGenerator puzzleGenerator, int maxChallenges, int maxProofs) {
        if (maxChallenges <= 0 || maxProofs <= 0) {
            throw new IllegalArgumentException("captcha cache capacity must be positive");
        }
        this.rateLimiter = rateLimiter;
        this.secureRandom = secureRandom;
        this.ticker = ticker;
        this.puzzleGenerator = puzzleGenerator;
        this.maxChallenges = maxChallenges;
        this.maxProofs = maxProofs;
        this.challenges = Caffeine.newBuilder()
                .expireAfterWrite(CHALLENGE_TTL)
                .maximumSize(maxChallenges)
                .ticker(ticker)
                .build();
        this.proofs = Caffeine.newBuilder()
                .expireAfterWrite(PROOF_TTL)
                .maximumSize(maxProofs)
                .ticker(ticker)
                .build();
    }

    /**
     * 签发一个绑定当前直接连接来源的挑战。
     *
     * @param remoteAddress API 层 ClientIpResolver 解析后的可信客户端地址
     * @param userAgent     浏览器 User-Agent
     * @return 短期挑战
     */
    public LoginCaptchaChallenge issue(String remoteAddress, String userAgent) {
        String fingerprint = fingerprint(remoteAddress, userAgent);
        String challengeId = randomToken();
        String challengeDigest = digest(challengeId);
        try (LoginCaptchaRateLimiter.IssuePermit ignored =
                     rateLimiter.acquireIssue(rateLimitSubject(remoteAddress))) {
            ChallengeState reservation = reserveChallenge(challengeDigest, fingerprint);
            try {
                LoginCaptchaPuzzleGenerator.Puzzle puzzle = puzzleGenerator.generate(challengeId);
                completeChallenge(challengeDigest, reservation, puzzle);
                return new LoginCaptchaChallenge(challengeId, TRACK_SCALE, CHALLENGE_TTL.toSeconds(),
                        puzzle.backgroundImage(), puzzle.pieceImage(),
                        LoginCaptchaPuzzleGenerator.IMAGE_WIDTH, LoginCaptchaPuzzleGenerator.IMAGE_HEIGHT,
                        LoginCaptchaPuzzleGenerator.PIECE_WIDTH, LoginCaptchaPuzzleGenerator.PIECE_HEIGHT,
                        puzzle.pieceY());
            } catch (RuntimeException failure) {
                challenges.asMap().remove(challengeDigest, reservation);
                throw failure;
            }
        }
    }

    /**
     * 原子消费挑战并校验轨迹，成功后签发一次性 proof。
     *
     * @param challengeId  挑战明文
     * @param track        浏览器采集的归一化轨迹
     * @param remoteAddress API 层 ClientIpResolver 解析后的可信客户端地址
     * @param userAgent    当前浏览器 User-Agent
     * @return 一次性 proof
     */
    public LoginCaptchaProof verify(String challengeId, List<LoginCaptchaTrackPoint> track,
                                    String remoteAddress, String userAgent) {
        String fingerprint = fingerprint(remoteAddress, userAgent);
        String challengeDigest = digestIfValidToken(challengeId);
        ChallengeState challenge = challengeDigest == null ? null : challenges.asMap().remove(challengeDigest);
        rateLimiter.acquire(LoginCaptchaRateLimiter.Action.VERIFY, rateLimitSubject(remoteAddress));
        if (challengeDigest == null) {
            throw BizException.invalidParam("验证码挑战无效，请重新获取");
        }
        if (challenge == null || !sameFingerprint(challenge.fingerprint(), fingerprint)) {
            throw BizException.invalidParam("验证码挑战已失效，请重新滑动");
        }
        validateTrack(track, challenge);

        String proof = randomToken();
        putProof(digest(proof), new ProofState(fingerprint));
        return new LoginCaptchaProof(proof, PROOF_TTL.toSeconds());
    }

    /**
     * 在密码校验前原子消费 proof。
     *
     * @param proof         proof 明文
     * @param remoteAddress API 层 ClientIpResolver 解析后的可信客户端地址
     * @param userAgent     当前浏览器 User-Agent
     */
    public void consume(String proof, String remoteAddress, String userAgent) {
        String fingerprint = fingerprint(remoteAddress, userAgent);
        String proofDigest = digestIfValidToken(proof);
        ProofState state = proofDigest == null ? null : proofs.asMap().remove(proofDigest);
        rateLimiter.acquire(LoginCaptchaRateLimiter.Action.CONSUME, rateLimitSubject(remoteAddress));
        if (proofDigest == null) {
            throw BizException.invalidParam("请先完成滑块验证");
        }
        if (state == null || !sameFingerprint(state.fingerprint(), fingerprint)) {
            throw BizException.invalidParam("滑块验证已失效，请重新验证");
        }
    }

    private void validateTrack(List<LoginCaptchaTrackPoint> track, ChallengeState challenge) {
        if (track == null || track.size() < MIN_TRACK_POINTS || track.size() > MAX_TRACK_POINTS) {
            throw BizException.invalidParam("滑块轨迹无效，请重新滑动");
        }
        LoginCaptchaTrackPoint first = track.get(0);
        LoginCaptchaTrackPoint last = track.get(track.size() - 1);
        if (first == null || last == null
                || first.x() < 0 || first.x() > START_X_TOLERANCE
                || first.elapsedMs() != 0L
                || last.x() < 0 || last.x() > TRACK_SCALE) {
            throw BizException.invalidParam("滑块轨迹起止位置无效，请重新滑动");
        }

        long previousElapsed = -1L;
        for (LoginCaptchaTrackPoint point : track) {
            if (point == null
                    || point.x() < 0 || point.x() > TRACK_SCALE
                    || point.y() < MIN_Y || point.y() > MAX_Y
                    || point.elapsedMs() < 0 || point.elapsedMs() > MAX_DURATION_MS
                    || point.elapsedMs() < previousElapsed) {
                throw BizException.invalidParam("滑块轨迹无效，请重新滑动");
            }
            previousElapsed = point.elapsedMs();
        }

        long duration = last.elapsedMs() - first.elapsedMs();
        if (duration < MIN_DURATION_MS || duration > MAX_DURATION_MS) {
            throw BizException.invalidParam("滑块操作时长无效，请重新滑动");
        }
        if (ticker.read() - challenge.issuedAtNanos() < MIN_SERVER_DURATION_NANOS) {
            throw BizException.invalidParam("滑块操作过快，请重新滑动");
        }
        if (Math.abs(last.x() - challenge.targetX()) > TARGET_X_TOLERANCE) {
            throw BizException.invalidParam("拼图位置不正确，请重新滑动");
        }
    }

    private ChallengeState reserveChallenge(String digest, String fingerprint) {
        synchronized (challengeCapacityLock) {
            challenges.cleanUp();
            if (challenges.estimatedSize() >= maxChallenges) {
                rejectFullCache("challenge");
            }
            ChallengeState reservation = new ChallengeState(fingerprint, -1, ticker.read());
            challenges.put(digest, reservation);
            return reservation;
        }
    }

    private void completeChallenge(String digest, ChallengeState reservation,
                                   LoginCaptchaPuzzleGenerator.Puzzle puzzle) {
        synchronized (challengeCapacityLock) {
            ChallengeState completed = new ChallengeState(
                    reservation.fingerprint(), puzzle.targetX(), reservation.issuedAtNanos());
            if (!challenges.asMap().replace(digest, reservation, completed)) {
                throw new BizException(ErrorCode.RATE_LIMITED,
                        "验证码生成超时，请重新获取");
            }
        }
    }

    private void putProof(String digest, ProofState state) {
        synchronized (proofCapacityLock) {
            proofs.cleanUp();
            if (!proofs.asMap().containsKey(digest) && proofs.estimatedSize() >= maxProofs) {
                rejectFullCache("proof");
            }
            proofs.put(digest, state);
        }
    }

    private void rejectFullCache(String cacheName) {
        log.info("login captcha call rejected because state cache is full, errorCode={}, cache={}",
                ErrorCode.RATE_LIMITED, cacheName);
        throw new BizException(ErrorCode.RATE_LIMITED,
                "验证码请求繁忙，请稍后重试");
    }

    private String fingerprint(String remoteAddress, String userAgent) {
        String address = remoteAddress == null ? "" : remoteAddress;
        String agentHash = HashUtil.sha256Hex(userAgent == null ? "" : userAgent);
        return HashUtil.sha256Hex(address + FINGERPRINT_SEPARATOR + agentHash);
    }

    private String rateLimitSubject(String remoteAddress) {
        return HashUtil.sha256Hex(remoteAddress == null ? "" : remoteAddress);
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    private String digestIfValidToken(String value) {
        if (value == null || value.length() != TOKEN_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            boolean base64UrlCharacter = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '_';
            if (!base64UrlCharacter) {
                return null;
            }
        }
        return digest(value);
    }

    private String digest(String value) {
        return HashUtil.sha256Hex(value);
    }

    private boolean sameFingerprint(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private record ChallengeState(String fingerprint, int targetX, long issuedAtNanos) {
    }

    private record ProofState(String fingerprint) {
    }
}
