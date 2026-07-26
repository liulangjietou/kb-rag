package io.kbrag.app.alert;

import io.kbrag.domain.config.KbProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps the recent retrieval outcomes so the degradation ratio of the observation window can be read.
 *
 * <p>The window is a bounded deque of timestamps rather than a counter pair, because the ratio has to be
 * about the last few minutes: two counters would never forget a bad hour and the alert would stay latched
 * long after the cause was fixed.
 *
 * <p>Samples older than the window are dropped on every read, so an idle service naturally converges to
 * an empty window and reports no degradation instead of the last ratio it happened to observe.
 *
 * @author owlzhangfq@gmail.com
 */
@Service
@RequiredArgsConstructor
public class RetrievalDegradeMonitor {

    /** Upper bound of retained samples, so a burst cannot grow the window without limit. */
    private static final int MAX_SAMPLES = 10_000;

    private final KbProperties properties;

    private final Deque<Sample> samples = new ArrayDeque<>();

    /**
     * Records the outcome of one retrieval call.
     *
     * @param degraded {@code true} when the call carried at least one degradation marker
     */
    public synchronized void record(boolean degraded) {
        samples.addLast(new Sample(Instant.now(), degraded));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    /**
     * Reads the current window.
     *
     * @return sample count and degraded count of the observation window
     */
    public synchronized Snapshot snapshot() {
        Instant cutoff = Instant.now().minus(
                Duration.ofMinutes(properties.getAlert().getDegradeWindowMinutes()));
        while (!samples.isEmpty() && samples.peekFirst().at().isBefore(cutoff)) {
            samples.removeFirst();
        }
        int degraded = 0;
        for (Sample sample : samples) {
            if (sample.degraded()) {
                degraded++;
            }
        }
        return new Snapshot(samples.size(), degraded);
    }

    /**
     * One observed retrieval call.
     *
     * @param at       observation instant
     * @param degraded {@code true} when the call was degraded
     */
    private record Sample(Instant at, boolean degraded) {
    }

    /**
     * State of the observation window.
     *
     * @param total    calls observed
     * @param degraded calls that carried a degradation marker
     */
    public record Snapshot(int total, int degraded) {

        /**
         * Share of degraded calls.
         *
         * @return ratio between 0 and 1, zero for an empty window
         */
        public double ratio() {
            return total == 0 ? 0d : (double) degraded / total;
        }
    }
}
