package io.kbrag.app.retrieval;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.port.RerankProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the rerank stage without a model: that the coarse order survives every failure mode, and that
 * a timeout and an error stay distinguishable because they call for different remediations.
 *
 * @author owlzhangfq@gmail.com
 */
class RerankServiceTest {

    private static final String QUERY = "product price";
    private static final List<String> DOCUMENTS = List.of("first", "second", "third");
    private static final int TIMEOUT_MS = 200;

    private RerankProvider rerankProvider;
    private KbProperties properties;
    private Executor executor;

    @BeforeEach
    void setUp() {
        rerankProvider = mock(RerankProvider.class);
        properties = new KbProperties();
        properties.getRerank().setTimeoutMs(TIMEOUT_MS);
        executor = Executors.newCachedThreadPool();
    }

    @Test
    void shouldDegradeOnTimeout() {
        when(rerankProvider.isConfigured()).thenReturn(true);
        when(rerankProvider.rerank(anyString(), any())).thenAnswer(invocation -> {
            Thread.sleep(TIMEOUT_MS * 10L);
            return List.of(0.9d, 0.5d, 0.1d);
        });

        long start = System.currentTimeMillis();
        RerankOutcome outcome = newService().rerank(QUERY, DOCUMENTS, true);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(outcome.isApplied());
        assertEquals(DegradedReason.RERANK_TIMEOUT.code(), outcome.getDegradedReason());
        assertTrue(outcome.getScores().isEmpty());
        assertTrue(elapsed < TIMEOUT_MS * 5L, "the stage must give up close to its budget, took " + elapsed);
    }

    @Test
    void shouldDegradeOnProviderFailure() {
        when(rerankProvider.isConfigured()).thenReturn(true);
        when(rerankProvider.rerank(anyString(), any())).thenThrow(
                new ProviderException("dashscope", ProviderErrorType.AUTH_FAILED, "bad credential"));

        RerankOutcome outcome = newService().rerank(QUERY, DOCUMENTS, true);

        assertFalse(outcome.isApplied());
        // An error is a configuration problem and a timeout is a capacity problem; conflating them
        // would send an operator to the wrong place.
        assertEquals(DegradedReason.RERANK_ERROR.code(), outcome.getDegradedReason());
    }

    @Test
    void shouldDegradeWhenTheProviderReturnsAMisalignedResult() {
        when(rerankProvider.isConfigured()).thenReturn(true);
        when(rerankProvider.rerank(anyString(), any())).thenReturn(List.of(0.9d));

        RerankOutcome outcome = newService().rerank(QUERY, DOCUMENTS, true);

        // Scores that cannot be matched to candidates would silently reorder the wrong documents.
        assertFalse(outcome.isApplied());
        assertEquals(DegradedReason.RERANK_ERROR.code(), outcome.getDegradedReason());
    }

    @Test
    void shouldReportAnUnavailableModelOnlyWhenTheStageWasRequested() {
        when(rerankProvider.isConfigured()).thenReturn(false);
        RerankService service = newService();

        assertEquals(DegradedReason.RERANK_UNAVAILABLE.code(),
                service.rerank(QUERY, DOCUMENTS, true).getDegradedReason());

        RerankOutcome disabled = service.rerank(QUERY, DOCUMENTS, false);
        assertNull(disabled.getDegradedReason());
        assertFalse(disabled.isApplied());
        verify(rerankProvider, never()).rerank(anyString(), any());
    }

    @Test
    void shouldHonourTheOfflineTimeoutInsteadOfTheOnlineOneDuringAnEvaluationRun() {
        properties.getEval().setOfflineTimeoutMs(TIMEOUT_MS * 20L);
        when(rerankProvider.isConfigured()).thenReturn(true);
        when(rerankProvider.rerank(anyString(), any())).thenAnswer(invocation -> {
            Thread.sleep(TIMEOUT_MS * 2L);
            return List.of(0.9d, 0.5d, 0.1d);
        });
        RerankService service = newService();

        RerankOutcome outcome = OfflineExecutionContext.runOffline(
                () -> service.rerank(QUERY, DOCUMENTS, true));

        assertTrue(outcome.isApplied());
        assertNull(outcome.getDegradedReason());
    }

    @Test
    void shouldApplyTheScoresInSubmissionOrder() {
        when(rerankProvider.isConfigured()).thenReturn(true);
        when(rerankProvider.rerank(anyString(), any())).thenReturn(List.of(0.2d, 0.9d, 0.4d));

        RerankOutcome outcome = newService().rerank(QUERY, DOCUMENTS, true);

        assertTrue(outcome.isApplied());
        assertEquals(List.of(0.2d, 0.9d, 0.4d), outcome.getScores());
        assertNull(outcome.getDegradedReason());
    }

    @Test
    void shouldDegradeWhenThePoolRejectsTheCall() {
        when(rerankProvider.isConfigured()).thenReturn(true);
        Executor saturated = task -> {
            throw new RejectedExecutionException("pool saturated");
        };

        RerankOutcome outcome =
                new RerankService(rerankProvider, properties, saturated).rerank(QUERY, DOCUMENTS, true);

        // The retrieval pool is queueless on purpose, so it rejects synchronously under load. A search
        // must survive that by keeping the fusion order, not fail with a 500.
        assertFalse(outcome.isApplied());
        assertEquals(DegradedReason.RERANK_ERROR.code(), outcome.getDegradedReason());
    }

    @Test
    void shouldSkipAnEmptyCandidateList() {
        when(rerankProvider.isConfigured()).thenReturn(true);

        RerankOutcome outcome = newService().rerank(QUERY, List.of(), true);

        assertFalse(outcome.isApplied());
        assertNull(outcome.getDegradedReason());
        verify(rerankProvider, never()).rerank(anyString(), any());
    }

    private RerankService newService() {
        return new RerankService(rerankProvider, properties, executor);
    }
}
