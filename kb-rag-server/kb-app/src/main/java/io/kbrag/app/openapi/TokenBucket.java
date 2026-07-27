package io.kbrag.app.openapi;

/**
 * One key's token bucket.
 *
 * <p><b>Why a bucket and not a counter per second.</b> A fixed window counter lets a caller send its whole
 * quota at the very end of one window and again at the start of the next, so a key configured for 10 requests
 * per second can legitimately produce 20 within a few milliseconds. A bucket refills continuously and has no
 * boundary to exploit.
 *
 * <p>Time arrives as a parameter rather than being read inside, which is what makes the refill arithmetic
 * testable without sleeping.
 *
 * @author owlzhangfq@gmail.com
 */
public final class TokenBucket {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** Bucket size and refill rate per second; a burst may never exceed one second of quota. */
    private final int ratePerSecond;

    private double tokens;

    private long lastRefillNanos;

    /**
     * Creates a full bucket.
     *
     * @param ratePerSecond permitted requests per second, at least one
     * @param nowNanos      creation timestamp in nanoseconds
     */
    public TokenBucket(int ratePerSecond, long nowNanos) {
        this.ratePerSecond = Math.max(1, ratePerSecond);
        this.tokens = this.ratePerSecond;
        this.lastRefillNanos = nowNanos;
    }

    /**
     * Takes one token if the bucket holds one.
     *
     * @param nowNanos current timestamp in nanoseconds
     * @return {@code true} when the request may proceed
     */
    public synchronized boolean tryAcquire(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0d) {
            tokens -= 1.0d;
            return true;
        }
        return false;
    }

    /**
     * Adds the tokens elapsed time earned, capped at the bucket size.
     *
     * @param nowNanos current timestamp in nanoseconds
     */
    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        double earned = (double) elapsed / NANOS_PER_SECOND * ratePerSecond;
        tokens = Math.min(ratePerSecond, tokens + earned);
        lastRefillNanos = nowNanos;
    }
}
