package io.kbrag.app.auth;

import com.github.benmanes.caffeine.cache.Ticker;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 固化登录滑块的一次性、来源绑定和轨迹校验边界。
 *
 * @author owlzhangfq@gmail.com
 */
class LoginCaptchaServiceTest {

    private static final String REMOTE_ADDRESS = "127.0.0.1";
    private static final String OTHER_ADDRESS = "127.0.0.2";
    private static final String USER_AGENT = "captcha-test-browser";
    private static final int TOKEN_LENGTH = 43;
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{43}";
    private static final String MALFORMED_TOKEN = "!" + "a".repeat(TOKEN_LENGTH - 1);
    private static final String OVERSIZED_TOKEN = "a".repeat(100_000);
    private static final String BACKGROUND_IMAGE = "data:image/png;base64,YmFja2dyb3VuZA==";
    private static final String PIECE_IMAGE = "data:image/png;base64,cGllY2U=";
    private static final int TARGET_X = 625;
    private static final int PIECE_Y = 48;

    private final AtomicLong tickerNanos = new AtomicLong();

    private LoginCaptchaService service;

    @BeforeEach
    void setUp() {
        Ticker ticker = tickerNanos::get;
        LoginCaptchaPuzzleGenerator puzzleGenerator = mock(LoginCaptchaPuzzleGenerator.class);
        when(puzzleGenerator.generate(anyString())).thenReturn(new LoginCaptchaPuzzleGenerator.Puzzle(
                BACKGROUND_IMAGE, PIECE_IMAGE, TARGET_X, PIECE_Y));
        service = new LoginCaptchaService(new LoginCaptchaRateLimiter(ticker), new SecureRandom(), ticker,
                puzzleGenerator);
    }

    @Test
    void shouldIssueVerifyAndConsumeAProofOnce() {
        LoginCaptchaChallenge challenge = service.issue(REMOTE_ADDRESS, USER_AGENT);
        advanceForVerification();

        LoginCaptchaProof proof = service.verify(challenge.challengeId(), validTrack(),
                REMOTE_ADDRESS, USER_AGENT);
        service.consume(proof.proof(), REMOTE_ADDRESS, USER_AGENT);

        assertEquals(LoginCaptchaService.TRACK_SCALE, challenge.trackScale());
        assertEquals(120L, challenge.expiresInSeconds());
        assertEquals(60L, proof.expiresInSeconds());
        assertNotNull(proof.proof());
        assertEquals(TOKEN_LENGTH, challenge.challengeId().length());
        assertEquals(TOKEN_LENGTH, proof.proof().length());
        assertTrue(challenge.challengeId().matches(TOKEN_PATTERN));
        assertTrue(proof.proof().matches(TOKEN_PATTERN));
        assertEquals(BACKGROUND_IMAGE, challenge.backgroundImage());
        assertEquals(PIECE_IMAGE, challenge.pieceImage());
        assertEquals(320, challenge.imageWidth());
        assertEquals(160, challenge.imageHeight());
        assertEquals(48, challenge.pieceWidth());
        assertEquals(48, challenge.pieceHeight());
        assertEquals(PIECE_Y, challenge.pieceY());
        assertFalse(Arrays.stream(LoginCaptchaChallenge.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .anyMatch(name -> name.contains("target") || name.contains("gapx") || name.contains("gap_x")));
        BizException replay = assertThrows(BizException.class,
                () -> service.consume(proof.proof(), REMOTE_ADDRESS, USER_AGENT));
        assertEquals(ErrorCode.INVALID_PARAM, replay.getErrorCode());
    }

    @Test
    void shouldRejectMalformedAndOversizedChallengeBeforeDigesting() {
        BizException malformed = assertThrows(BizException.class,
                () -> service.verify(MALFORMED_TOKEN, validTrack(), REMOTE_ADDRESS, USER_AGENT));
        BizException oversized = assertThrows(BizException.class,
                () -> service.verify(OVERSIZED_TOKEN, validTrack(), REMOTE_ADDRESS, USER_AGENT));

        assertEquals(ErrorCode.INVALID_PARAM, malformed.getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAM, oversized.getErrorCode());
    }

    @Test
    void shouldRejectMalformedAndOversizedProofBeforeDigesting() {
        BizException malformed = assertThrows(BizException.class,
                () -> service.consume(MALFORMED_TOKEN, REMOTE_ADDRESS, USER_AGENT));
        BizException oversized = assertThrows(BizException.class,
                () -> service.consume(OVERSIZED_TOKEN, REMOTE_ADDRESS, USER_AGENT));

        assertEquals(ErrorCode.INVALID_PARAM, malformed.getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAM, oversized.getErrorCode());
    }

    @Test
    void shouldConsumeTheChallengeWhenTheTrackIsInvalid() {
        LoginCaptchaChallenge challenge = service.issue(REMOTE_ADDRESS, USER_AGENT);
        advanceForVerification();
        List<LoginCaptchaTrackPoint> backwardsTime = List.of(
                new LoginCaptchaTrackPoint(0, 0, 0),
                new LoginCaptchaTrackPoint(500, 2, 500),
                new LoginCaptchaTrackPoint(TARGET_X, 0, 450));

        BizException invalid = assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), backwardsTime, REMOTE_ADDRESS, USER_AGENT));
        BizException replay = assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), validTrack(), REMOTE_ADDRESS, USER_AGENT));

        assertEquals(ErrorCode.INVALID_PARAM, invalid.getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAM, replay.getErrorCode());
    }

    @Test
    void shouldRejectPointCountRangeEndpointsDurationAndNonMonotonicTime() {
        List<List<LoginCaptchaTrackPoint>> invalidTracks = List.of(
                List.of(
                        new LoginCaptchaTrackPoint(0, 0, 0),
                        new LoginCaptchaTrackPoint(TARGET_X, 0, 400)),
                List.of(
                        new LoginCaptchaTrackPoint(0, 0, 0),
                        new LoginCaptchaTrackPoint(1_001, 0, 200),
                        new LoginCaptchaTrackPoint(TARGET_X, 0, 400)),
                List.of(
                        new LoginCaptchaTrackPoint(0, 0, 0),
                        new LoginCaptchaTrackPoint(500, 101, 200),
                        new LoginCaptchaTrackPoint(TARGET_X, 0, 400)),
                List.of(
                        new LoginCaptchaTrackPoint(31, 0, 0),
                        new LoginCaptchaTrackPoint(500, 0, 200),
                        new LoginCaptchaTrackPoint(TARGET_X, 0, 400)),
                List.of(
                        new LoginCaptchaTrackPoint(0, 0, 0),
                        new LoginCaptchaTrackPoint(500, 0, 200),
                        new LoginCaptchaTrackPoint(TARGET_X + LoginCaptchaService.TARGET_X_TOLERANCE + 1,
                                0, 400)),
                List.of(
                        new LoginCaptchaTrackPoint(0, 0, 0),
                        new LoginCaptchaTrackPoint(500, 0, 150),
                        new LoginCaptchaTrackPoint(TARGET_X, 0, 299)),
                List.of(
                        new LoginCaptchaTrackPoint(0, 0, 0),
                        new LoginCaptchaTrackPoint(500, 0, 8_000),
                        new LoginCaptchaTrackPoint(TARGET_X, 0, 15_001)),
                List.of(
                        new LoginCaptchaTrackPoint(0, 0, 1),
                        new LoginCaptchaTrackPoint(500, 0, 201),
                        new LoginCaptchaTrackPoint(TARGET_X, 0, 401)),
                List.of(
                        new LoginCaptchaTrackPoint(0, 0, 0),
                        new LoginCaptchaTrackPoint(500, 0, 15_001),
                        new LoginCaptchaTrackPoint(TARGET_X, 0, 15_001)),
                List.of(
                        new LoginCaptchaTrackPoint(0, 0, 0),
                        new LoginCaptchaTrackPoint(500, 0, 500),
                        new LoginCaptchaTrackPoint(TARGET_X, 0, 450)));

        for (List<LoginCaptchaTrackPoint> track : invalidTracks) {
            LoginCaptchaChallenge challenge = service.issue(REMOTE_ADDRESS, USER_AGENT);
            advanceForVerification();
            BizException failure = assertThrows(BizException.class,
                    () -> service.verify(challenge.challengeId(), track, REMOTE_ADDRESS, USER_AGENT));
            assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        }
    }

    @Test
    void shouldConsumeChallengeWhenPieceStopsOutsideTheRandomTarget() {
        LoginCaptchaChallenge challenge = service.issue(REMOTE_ADDRESS, USER_AGENT);
        advanceForVerification();
        List<LoginCaptchaTrackPoint> wrongTarget = List.of(
                new LoginCaptchaTrackPoint(0, 0, 0),
                new LoginCaptchaTrackPoint(500, 1, 180),
                new LoginCaptchaTrackPoint(1_000, 0, 420));

        BizException failure = assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), wrongTarget, REMOTE_ADDRESS, USER_AGENT));
        BizException replay = assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), validTrack(), REMOTE_ADDRESS, USER_AGENT));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAM, replay.getErrorCode());
    }

    @Test
    void shouldRejectAnImmediateFixedThreePointTrackUsingServerTime() {
        LoginCaptchaChallenge challenge = service.issue(REMOTE_ADDRESS, USER_AGENT);
        List<LoginCaptchaTrackPoint> fixedTrack = List.of(
                new LoginCaptchaTrackPoint(0, 0, 0),
                new LoginCaptchaTrackPoint(500, 0, 200),
                new LoginCaptchaTrackPoint(TARGET_X, 0, 400));

        BizException immediate = assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), fixedTrack, REMOTE_ADDRESS, USER_AGENT));
        advanceForVerification();
        BizException replay = assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), validTrack(), REMOTE_ADDRESS, USER_AGENT));

        assertEquals(ErrorCode.INVALID_PARAM, immediate.getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAM, replay.getErrorCode());
    }

    @Test
    void shouldConsumeTheChallengeOnFingerprintMismatch() {
        LoginCaptchaChallenge challenge = service.issue(REMOTE_ADDRESS, USER_AGENT);

        assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), validTrack(), OTHER_ADDRESS, USER_AGENT));
        assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), validTrack(), REMOTE_ADDRESS, USER_AGENT));
    }

    @Test
    void shouldBindTheChallengeToTheUserAgentHash() {
        LoginCaptchaChallenge challenge = service.issue(REMOTE_ADDRESS, USER_AGENT);

        assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), validTrack(), REMOTE_ADDRESS, "other-browser"));
        assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), validTrack(), REMOTE_ADDRESS, USER_AGENT));
    }

    @Test
    void shouldConsumeTheProofOnFingerprintMismatch() {
        LoginCaptchaProof proof = verifiedProof();

        BizException mismatch = assertThrows(BizException.class,
                () -> service.consume(proof.proof(), OTHER_ADDRESS, USER_AGENT));
        BizException replay = assertThrows(BizException.class,
                () -> service.consume(proof.proof(), REMOTE_ADDRESS, USER_AGENT));

        assertEquals(ErrorCode.INVALID_PARAM, mismatch.getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAM, replay.getErrorCode());
    }

    @Test
    void shouldRejectExpiredChallengeAndProof() {
        LoginCaptchaChallenge challenge = service.issue(REMOTE_ADDRESS, USER_AGENT);
        advance(Duration.ofMinutes(2).plusNanos(1));

        assertThrows(BizException.class,
                () -> service.verify(challenge.challengeId(), validTrack(), REMOTE_ADDRESS, USER_AGENT));

        LoginCaptchaProof proof = verifiedProof();
        advance(Duration.ofMinutes(1).plusNanos(1));

        BizException expired = assertThrows(BizException.class,
                () -> service.consume(proof.proof(), REMOTE_ADDRESS, USER_AGENT));
        assertEquals(ErrorCode.INVALID_PARAM, expired.getErrorCode());
    }

    @Test
    void shouldAllowOnlyOneConcurrentProofConsumer() throws Exception {
        LoginCaptchaProof proof = verifiedProof();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> consumeAfterBarrier(proof.proof(), ready, start));
            Future<Boolean> second = executor.submit(() -> consumeAfterBarrier(proof.proof(), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int successCount = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successCount);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldAllowOnlyOneConcurrentChallengeVerifier() throws Exception {
        LoginCaptchaChallenge challenge = service.issue(REMOTE_ADDRESS, USER_AGENT);
        advanceForVerification();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(
                    () -> verifyAfterBarrier(challenge.challengeId(), ready, start));
            Future<Boolean> second = executor.submit(
                    () -> verifyAfterBarrier(challenge.challengeId(), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int successCount = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successCount);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRateLimitChallengeIssuanceByFingerprint() {
        for (int i = 0; i < 20; i++) {
            service.issue(REMOTE_ADDRESS, USER_AGENT);
        }

        BizException limited = assertThrows(BizException.class,
                () -> service.issue(REMOTE_ADDRESS, USER_AGENT));
        assertEquals(ErrorCode.RATE_LIMITED, limited.getErrorCode());
    }

    @Test
    void shouldRateLimitChallengeIssuanceGloballyAcrossSources() {
        Ticker ticker = tickerNanos::get;
        LoginCaptchaPuzzleGenerator puzzleGenerator = stubPuzzleGenerator();
        service = new LoginCaptchaService(new LoginCaptchaRateLimiter(ticker, 2, 1),
                new SecureRandom(), ticker, puzzleGenerator);

        service.issue("10.0.0.1", USER_AGENT);
        service.issue("10.0.0.2", USER_AGENT);
        BizException limited = assertThrows(BizException.class,
                () -> service.issue("10.0.0.3", USER_AGENT));

        assertEquals(ErrorCode.RATE_LIMITED, limited.getErrorCode());
    }

    @Test
    void shouldRejectConcurrentImageGenerationBeyondTheBulkhead() throws Exception {
        Ticker ticker = tickerNanos::get;
        CountDownLatch generationEntered = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        LoginCaptchaPuzzleGenerator puzzleGenerator = mock(LoginCaptchaPuzzleGenerator.class);
        when(puzzleGenerator.generate(anyString())).thenAnswer(invocation -> {
            generationEntered.countDown();
            if (!releaseGeneration.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("captcha generation test timed out");
            }
            return puzzle();
        });
        service = new LoginCaptchaService(new LoginCaptchaRateLimiter(ticker, 120, 1),
                new SecureRandom(), ticker, puzzleGenerator);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<LoginCaptchaChallenge> first = executor.submit(
                    () -> service.issue(REMOTE_ADDRESS, USER_AGENT));
            assertTrue(generationEntered.await(5, TimeUnit.SECONDS));

            BizException limited = assertThrows(BizException.class,
                    () -> service.issue(OTHER_ADDRESS, USER_AGENT));
            assertEquals(ErrorCode.RATE_LIMITED, limited.getErrorCode());

            releaseGeneration.countDown();
            assertNotNull(first.get(5, TimeUnit.SECONDS));
        } finally {
            releaseGeneration.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectNewChallengeWithoutEvictingTheExistingOneWhenCapacityIsFull() {
        Ticker ticker = tickerNanos::get;
        LoginCaptchaPuzzleGenerator puzzleGenerator = stubPuzzleGenerator();
        service = new LoginCaptchaService(new LoginCaptchaRateLimiter(ticker),
                new SecureRandom(), ticker, puzzleGenerator, 1, 1);
        LoginCaptchaChallenge first = service.issue(REMOTE_ADDRESS, USER_AGENT);

        BizException limited = assertThrows(BizException.class,
                () -> service.issue(OTHER_ADDRESS, USER_AGENT));
        advanceForVerification();
        LoginCaptchaProof proof = service.verify(first.challengeId(), validTrack(),
                REMOTE_ADDRESS, USER_AGENT);

        assertEquals(ErrorCode.RATE_LIMITED, limited.getErrorCode());
        org.mockito.Mockito.verify(puzzleGenerator, org.mockito.Mockito.times(1)).generate(anyString());
        service.consume(proof.proof(), REMOTE_ADDRESS, USER_AGENT);
    }

    @Test
    void shouldRejectNewProofWithoutEvictingTheExistingOneWhenCapacityIsFull() {
        Ticker ticker = tickerNanos::get;
        service = new LoginCaptchaService(new LoginCaptchaRateLimiter(ticker),
                new SecureRandom(), ticker, stubPuzzleGenerator(), 2, 1);
        LoginCaptchaChallenge first = service.issue(REMOTE_ADDRESS, USER_AGENT);
        LoginCaptchaChallenge second = service.issue(OTHER_ADDRESS, USER_AGENT);
        advanceForVerification();
        LoginCaptchaProof firstProof = service.verify(first.challengeId(), validTrack(),
                REMOTE_ADDRESS, USER_AGENT);

        BizException limited = assertThrows(BizException.class,
                () -> service.verify(second.challengeId(), validTrack(), OTHER_ADDRESS, USER_AGENT));

        assertEquals(ErrorCode.RATE_LIMITED, limited.getErrorCode());
        service.consume(firstProof.proof(), REMOTE_ADDRESS, USER_AGENT);
    }

    private LoginCaptchaProof verifiedProof() {
        LoginCaptchaChallenge challenge = service.issue(REMOTE_ADDRESS, USER_AGENT);
        advanceForVerification();
        return service.verify(challenge.challengeId(), validTrack(), REMOTE_ADDRESS, USER_AGENT);
    }

    private LoginCaptchaPuzzleGenerator stubPuzzleGenerator() {
        LoginCaptchaPuzzleGenerator generator = mock(LoginCaptchaPuzzleGenerator.class);
        when(generator.generate(anyString())).thenReturn(puzzle());
        return generator;
    }

    private LoginCaptchaPuzzleGenerator.Puzzle puzzle() {
        return new LoginCaptchaPuzzleGenerator.Puzzle(
                BACKGROUND_IMAGE, PIECE_IMAGE, TARGET_X, PIECE_Y);
    }

    private List<LoginCaptchaTrackPoint> validTrack() {
        return List.of(
                new LoginCaptchaTrackPoint(0, 0, 0),
                new LoginCaptchaTrackPoint(210, 3, 120),
                new LoginCaptchaTrackPoint(460, -2, 260),
                new LoginCaptchaTrackPoint(TARGET_X, 0, 420));
    }

    private boolean consumeAfterBarrier(String proof, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            service.consume(proof, REMOTE_ADDRESS, USER_AGENT);
            return true;
        } catch (BizException e) {
            return false;
        }
    }

    private boolean verifyAfterBarrier(String challengeId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            service.verify(challengeId, validTrack(), REMOTE_ADDRESS, USER_AGENT);
            return true;
        } catch (BizException e) {
            return false;
        }
    }

    private void advance(Duration duration) {
        tickerNanos.addAndGet(duration.toNanos());
    }

    private void advanceForVerification() {
        advance(Duration.ofNanos(LoginCaptchaService.MIN_SERVER_DURATION_NANOS));
    }
}
