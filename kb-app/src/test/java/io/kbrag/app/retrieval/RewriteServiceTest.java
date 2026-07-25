package io.kbrag.app.retrieval;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.port.ChatProvider;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the query rewrite stage without a model: that a slow provider degrades within the budget, that
 * a failure never propagates, and that switching the stage off is not reported as a degradation.
 */
class RewriteServiceTest {

    private static final String QUERY = "how much does it cost";
    private static final long TIMEOUT_MS = 200L;

    private ChatProvider chatProvider;
    private KbProperties properties;
    private Executor executor;

    @BeforeEach
    void setUp() {
        chatProvider = mock(ChatProvider.class);
        properties = new KbProperties();
        properties.getRetrieval().setRewriteTimeoutMs(TIMEOUT_MS);
        executor = Executors.newCachedThreadPool();
    }

    @Test
    void shouldDegradeToTheOriginalQueryOnTimeout() {
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenAnswer(invocation -> {
            Thread.sleep(TIMEOUT_MS * 10);
            return "rewritten";
        });

        long start = System.currentTimeMillis();
        RewriteOutcome outcome = newService().rewrite(QUERY, List.of(), true);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(QUERY, outcome.getQuery());
        assertEquals(DegradedReason.QUERY_REWRITE_TIMEOUT.code(), outcome.getDegradedReason());
        assertFalse(outcome.isRewritten());
        // The budget has to bound the stage, not merely be checked after the provider returns.
        assertTrue(elapsed < TIMEOUT_MS * 5, "the stage must give up close to its budget, took " + elapsed);
    }

    @Test
    void shouldDegradeToTheOriginalQueryOnProviderFailure() {
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenThrow(
                new ProviderException("dashscope", ProviderErrorType.QUOTA_EXCEEDED, "quota exhausted"));

        RewriteOutcome outcome = newService().rewrite(QUERY, List.of(), true);

        assertEquals(QUERY, outcome.getQuery());
        // An exhausted quota is a configuration problem and an elapsed budget is a capacity problem;
        // conflating them would send an operator to the wrong place.
        assertEquals(DegradedReason.QUERY_REWRITE_ERROR.code(), outcome.getDegradedReason());
    }

    @Test
    void shouldDegradeWhenThePoolRejectsTheCall() {
        when(chatProvider.isConfigured()).thenReturn(true);
        executor = task -> {
            throw new RejectedExecutionException("pool saturated");
        };

        RewriteOutcome outcome = newService().rewrite(QUERY, List.of(), true);

        // The retrieval pool is queueless on purpose, so it rejects synchronously under load. A search
        // must survive that by using the original query, not fail with a 500.
        assertEquals(QUERY, outcome.getQuery());
        assertEquals(DegradedReason.QUERY_REWRITE_ERROR.code(), outcome.getDegradedReason());
    }

    @Test
    void shouldReportAnUnavailableModelOnlyWhenTheStageWasRequested() {
        when(chatProvider.isConfigured()).thenReturn(false);
        RewriteService service = newService();

        RewriteOutcome requested = service.rewrite(QUERY, List.of(), true);
        assertEquals(DegradedReason.QUERY_REWRITE_UNAVAILABLE.code(), requested.getDegradedReason());

        // Leaving the stage off in a deployment without a chat model is a supported configuration, not
        // a degradation, so the zero key response stays free of noise.
        RewriteOutcome disabled = service.rewrite(QUERY, List.of(), false);
        assertNull(disabled.getDegradedReason());
        assertEquals(QUERY, disabled.getQuery());
        verify(chatProvider, never()).complete(anyString(), anyList());
    }

    @Test
    void shouldFlattenAndUseTheRewrittenQuery() {
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn("  product X\n price list  ");

        RewriteOutcome outcome = newService().rewrite(QUERY, List.of(), true);

        assertEquals("product X price list", outcome.getQuery());
        assertTrue(outcome.isRewritten());
        assertNull(outcome.getDegradedReason());
    }

    @Test
    void shouldKeepTheOriginalQueryWhenTheModelAnswersNothing() {
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn("   ");

        RewriteOutcome outcome = newService().rewrite(QUERY, List.of(), true);

        assertEquals(QUERY, outcome.getQuery());
        assertNull(outcome.getDegradedReason());
    }

    @Test
    void shouldCapAnOverlongRewrite() {
        properties.getRetrieval().setRewriteMaxLength(10);
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn("0123456789abcdefghij");

        assertEquals("0123456789", newService().rewrite(QUERY, List.of(), true).getQuery());
    }

    @Test
    void shouldServeTheSameConversationFromCache() {
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn("resolved query");
        RewriteService service = newService();
        List<ChatMessage> history = List.of(ChatMessage.user("tell me about product X"));

        assertEquals("resolved query", service.rewrite(QUERY, history, true).getQuery());
        assertEquals("resolved query", service.rewrite(QUERY, history, true).getQuery());

        verify(chatProvider, times(1)).complete(anyString(), anyList());
    }

    @Test
    void shouldNotShareCacheEntriesAcrossConversations() {
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn("resolved query");
        RewriteService service = newService();

        // The same follow up question resolves differently depending on what came before it, so the
        // conversation has to be part of the key.
        service.rewrite(QUERY, List.of(ChatMessage.user("about product X")), true);
        service.rewrite(QUERY, List.of(ChatMessage.user("about product Y")), true);

        verify(chatProvider, times(2)).complete(anyString(), anyList());
    }

    private RewriteService newService() {
        return new RewriteService(chatProvider, properties, executor);
    }
}
