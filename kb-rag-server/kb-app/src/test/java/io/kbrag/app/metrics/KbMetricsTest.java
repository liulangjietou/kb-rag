package io.kbrag.app.metrics;

import io.kbrag.domain.enums.InsightSource;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.enums.WebSourceFetchStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the metrics facade of the M13 contract section 3.1: every record method must land in the
 * registry under the exact name and bounded tag values, the two search booleans must map from the
 * caller's raw facts, and a {@code null} enum must drop the sample instead of throwing - the one
 * defensive promise that keeps recording off the business failure path.
 *
 * @author owlzhangfq@gmail.com
 */
class KbMetricsTest {

    private SimpleMeterRegistry registry;
    private KbMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new KbMetrics(registry);
    }

    @Test
    void shouldTimeASearchUnderItsBoundaryAndOutcomeTags() {
        metrics.recordSearch(InsightSource.CONSOLE, 120, 3, null);

        assertEquals(1, registry.get(KbMetrics.SEARCH)
                .tag(KbMetrics.TAG_SOURCE, "console")
                .tag(KbMetrics.TAG_ZERO_HIT, "false")
                .tag(KbMetrics.TAG_DEGRADED, "false")
                .timer().count());
        assertEquals(120, registry.get(KbMetrics.SEARCH).timer().totalTime(TimeUnit.MILLISECONDS));
    }

    @Test
    void shouldMapAnEmptyResultAndADegradationMarkerOntoTheBooleanTags() {
        metrics.recordSearch(InsightSource.OPEN_API, 5, 0, List.of("VECTOR_UNAVAILABLE"));

        assertEquals(1, registry.get(KbMetrics.SEARCH)
                .tag(KbMetrics.TAG_SOURCE, "open_api")
                .tag(KbMetrics.TAG_ZERO_HIT, "true")
                .tag(KbMetrics.TAG_DEGRADED, "true")
                .timer().count());
    }

    @Test
    void shouldCountATaskCompletionUnderItsTypeAndStatus() {
        metrics.recordTaskCompleted(TaskType.INDEX, true);
        metrics.recordTaskCompleted(TaskType.PARSE, false);

        assertEquals(1, registry.get(KbMetrics.TASK_COMPLETED)
                .tag(KbMetrics.TAG_TYPE, "index")
                .tag(KbMetrics.TAG_STATUS, KbMetrics.STATUS_SUCCESS)
                .counter().count());
        assertEquals(1, registry.get(KbMetrics.TASK_COMPLETED)
                .tag(KbMetrics.TAG_TYPE, "parse")
                .tag(KbMetrics.TAG_STATUS, KbMetrics.STATUS_FAILED)
                .counter().count());
    }

    @Test
    void shouldCountAnOpenApiRejectionUnderItsErrorCode() {
        metrics.recordOpenApiRejected("INVALID_API_KEY");
        metrics.recordOpenApiRejected("INVALID_API_KEY");

        assertEquals(2, registry.get(KbMetrics.OPENAPI_REJECTED)
                .tag(KbMetrics.TAG_ERROR_CODE, "INVALID_API_KEY")
                .counter().count());
    }

    @Test
    void shouldCountAWebSourceSyncUnderItsLowerCasedState() {
        metrics.recordWebSourceSync(WebSourceFetchStatus.UNCHANGED);

        assertEquals(1, registry.get(KbMetrics.WEBSOURCE_SYNC)
                .tag(KbMetrics.TAG_STATUS, "unchanged")
                .counter().count());
    }

    @Test
    void shouldDropTheSampleInsteadOfThrowingOnANullInput() {
        assertDoesNotThrow(() -> metrics.recordSearch(null, 10, 1, null));
        assertDoesNotThrow(() -> metrics.recordTaskCompleted(null, true));
        assertDoesNotThrow(() -> metrics.recordOpenApiRejected(null));
        assertDoesNotThrow(() -> metrics.recordOpenApiRejected(" "));
        assertDoesNotThrow(() -> metrics.recordWebSourceSync(null));

        assertTrue(registry.getMeters().isEmpty());
    }
}
