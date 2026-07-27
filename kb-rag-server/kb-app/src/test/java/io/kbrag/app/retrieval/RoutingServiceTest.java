package io.kbrag.app.retrieval;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.DegradedReason;
import io.kbrag.domain.port.ChatProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the routing stage of requirement section 4.9 and injection defence ③ of section 4.4: the model may
 * only narrow the white list, every way of failing narrows nothing, and a decision with one legal outcome is
 * never paid for.
 *
 * @author owlzhangfq@gmail.com
 */
class RoutingServiceTest {

    private static final String QUERY = "how much does the pro plan cost";
    private static final long TIMEOUT_MS = 200L;
    private static final String KB_A = "kb_manual";
    private static final String KB_B = "kb_chat";
    private static final String KB_C = "kb_faq";

    private ChatProvider chatProvider;
    private KbProperties properties;
    private Executor executor;

    @BeforeEach
    void setUp() {
        chatProvider = mock(ChatProvider.class);
        properties = new KbProperties();
        properties.getChat().setTimeoutMs((int) TIMEOUT_MS);
        executor = Executors.newCachedThreadPool();
    }

    @Test
    void shouldKeepOnlyTheSelectedBasesInDeclarationOrder() {
        givenAnswer("[\"" + KB_C + "\", \"" + KB_A + "\"]");

        RoutingOutcome outcome = newService().route(candidates(KB_A, KB_B, KB_C), QUERY, true, null);

        // Declaration order, not the model's: the quota remainder and the base whose defaults complete an
        // unset parameter must not depend on how a model happened to order its reply.
        assertEquals(List.of(KB_A, KB_C), outcome.getKbIds());
        assertNull(outcome.getDegradedReason());
    }

    @Test
    void shouldDiscardIdsOutsideTheWhiteListAndKeepTheRest() {
        givenAnswer("[\"" + KB_B + "\", \"kb_someone_elses\"]");

        RoutingOutcome outcome = newService().route(candidates(KB_A, KB_B), QUERY, true, null);

        assertEquals(List.of(KB_B), outcome.getKbIds());
        assertNull(outcome.getDegradedReason());
    }

    @Test
    void shouldSearchEveryBaseWhenTheAnswerMatchesNothingOnTheWhiteList() {
        // The shape a successful injection would take: an id nobody linked. Narrowing on it is out of the
        // question, and narrowing to nothing would answer from an empty corpus.
        givenAnswer("[\"kb_injected\"]");

        RoutingOutcome outcome = newService().route(candidates(KB_A, KB_B), QUERY, true, null);

        assertEquals(List.of(KB_A, KB_B), outcome.getKbIds());
        assertEquals(DegradedReason.ROUTE_FALLBACK_ALL.code(), outcome.getDegradedReason());
    }

    @Test
    void shouldSearchEveryBaseWhenTheAnswerIsNotParsable() {
        givenAnswer("I think the manual is the right place to look.");

        RoutingOutcome outcome = newService().route(candidates(KB_A, KB_B), QUERY, true, null);

        assertEquals(List.of(KB_A, KB_B), outcome.getKbIds());
        assertEquals(DegradedReason.ROUTE_FALLBACK_ALL.code(), outcome.getDegradedReason());
    }

    @Test
    void shouldReadAnArrayThatCameWrappedInProseOrAFence() {
        givenAnswer("```json\n[\"" + KB_A + "\"]\n```");

        RoutingOutcome outcome = newService().route(candidates(KB_A, KB_B), QUERY, true, null);

        assertEquals(List.of(KB_A), outcome.getKbIds());
        assertNull(outcome.getDegradedReason());
    }

    @Test
    void shouldSearchEveryBaseOnTimeout() {
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenAnswer(invocation -> {
            Thread.sleep(TIMEOUT_MS * 10);
            return "[\"" + KB_A + "\"]";
        });

        long start = System.currentTimeMillis();
        RoutingOutcome outcome = newService().route(candidates(KB_A, KB_B), QUERY, true, null);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(List.of(KB_A, KB_B), outcome.getKbIds());
        assertEquals(DegradedReason.ROUTE_FALLBACK_ALL.code(), outcome.getDegradedReason());
        // Routing sits in front of recall, so its budget has to bound the stage rather than be checked after.
        assertTrue(elapsed < TIMEOUT_MS * 5, "the stage must give up close to its budget, took " + elapsed);
    }

    @Test
    void shouldSearchEveryBaseOnProviderFailure() {
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenThrow(
                new ProviderException("dashscope", ProviderErrorType.AUTH_FAILED, "bad credential"));

        RoutingOutcome outcome = newService().route(candidates(KB_A, KB_B), QUERY, true, null);

        assertEquals(List.of(KB_A, KB_B), outcome.getKbIds());
        assertEquals(DegradedReason.ROUTE_FALLBACK_ALL.code(), outcome.getDegradedReason());
    }

    @Test
    void shouldSearchEveryBaseWhenThePoolRejectsTheCall() {
        when(chatProvider.isConfigured()).thenReturn(true);
        executor = task -> {
            throw new RejectedExecutionException("pool saturated");
        };

        RoutingOutcome outcome = newService().route(candidates(KB_A, KB_B), QUERY, true, null);

        assertEquals(List.of(KB_A, KB_B), outcome.getKbIds());
        assertEquals(DegradedReason.ROUTE_FALLBACK_ALL.code(), outcome.getDegradedReason());
    }

    @Test
    void shouldSearchEveryBaseAndReportItWhenRoutingIsOnWithoutAChatModel() {
        when(chatProvider.isConfigured()).thenReturn(false);

        RoutingOutcome outcome = newService().route(candidates(KB_A, KB_B), QUERY, true, null);

        assertEquals(List.of(KB_A, KB_B), outcome.getKbIds());
        assertEquals(DegradedReason.ROUTE_FALLBACK_ALL.code(), outcome.getDegradedReason());
        verify(chatProvider, never()).complete(anyString(), anyList());
    }

    @Test
    void shouldNotReportADegradationWhenRoutingIsSimplyOff() {
        when(chatProvider.isConfigured()).thenReturn(true);

        RoutingOutcome outcome = newService().route(candidates(KB_A, KB_B), QUERY, false, null);

        assertEquals(List.of(KB_A, KB_B), outcome.getKbIds());
        assertNull(outcome.getDegradedReason());
        verify(chatProvider, never()).complete(anyString(), anyList());
    }

    @Test
    void shouldNeverCallTheModelForASingleBaseApplication() {
        when(chatProvider.isConfigured()).thenReturn(true);

        RoutingOutcome outcome = newService().route(candidates(KB_A), QUERY, true, null);

        // One base is one legal outcome; paying a model call for it and marking it degraded would put noise
        // in every response of every single base application.
        assertEquals(List.of(KB_A), outcome.getKbIds());
        assertNull(outcome.getDegradedReason());
        verify(chatProvider, never()).complete(anyString(), anyList());
    }

    @Test
    void shouldServeTheSameQuestionAndCandidateSetFromCache() {
        givenAnswer("[\"" + KB_A + "\"]");
        RoutingService service = newService();

        assertEquals(List.of(KB_A), service.route(candidates(KB_A, KB_B), QUERY, true, null).getKbIds());
        assertEquals(List.of(KB_A), service.route(candidates(KB_A, KB_B), QUERY, true, null).getKbIds());

        verify(chatProvider, times(1)).complete(anyString(), anyList());
    }

    @Test
    void shouldNotShareCacheEntriesAcrossCandidateSetsOrPrompts() {
        givenAnswer("[\"" + KB_A + "\"]");
        RoutingService service = newService();

        service.route(candidates(KB_A, KB_B), QUERY, true, null);
        // A third base changes what the answer could be, and a reworded instruction changes what it will be.
        service.route(candidates(KB_A, KB_B, KB_C), QUERY, true, null);
        service.route(candidates(KB_A, KB_B), QUERY, true, "only pick the manual");

        verify(chatProvider, times(3)).complete(anyString(), anyList());
    }

    @Test
    void shouldNotCacheAFallbackDecision() {
        givenAnswer("not an array");
        RoutingService service = newService();

        service.route(candidates(KB_A, KB_B), QUERY, true, null);
        service.route(candidates(KB_A, KB_B), QUERY, true, null);

        // Caching a failure would keep an application on the full corpus for the whole time to live even
        // after the model recovered.
        verify(chatProvider, times(2)).complete(anyString(), anyList());
    }

    @Test
    void shouldUseTheOperatorPromptAsTheInstructionAndStillBuildTheCandidateList() {
        givenAnswer("[\"" + KB_A + "\"]");

        newService().route(candidates(KB_A, KB_B), QUERY, true, "pick exactly one base");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatProvider).complete(systemPrompt.capture(), anyList());
        assertEquals("pick exactly one base", systemPrompt.getValue());
    }

    @Test
    void shouldHonourTheOfflineTimeoutDuringAnEvaluationRun() {
        properties.getEval().setOfflineTimeoutMs(TIMEOUT_MS * 20);
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenAnswer(invocation -> {
            Thread.sleep(TIMEOUT_MS * 2);
            return "[\"" + KB_A + "\"]";
        });
        RoutingService service = newService();

        RoutingOutcome outcome = OfflineExecutionContext.runOffline(
                () -> service.route(candidates(KB_A, KB_B), QUERY, true, null));

        assertEquals(List.of(KB_A), outcome.getKbIds());
        assertNull(outcome.getDegradedReason());
    }

    private void givenAnswer(String answer) {
        when(chatProvider.isConfigured()).thenReturn(true);
        when(chatProvider.complete(anyString(), anyList())).thenReturn(answer);
    }

    private List<KnowledgeBase> candidates(String... kbIds) {
        return java.util.Arrays.stream(kbIds).map(kbId -> {
            KnowledgeBase knowledgeBase = new KnowledgeBase();
            knowledgeBase.setKbId(kbId);
            knowledgeBase.setName(kbId + " name");
            knowledgeBase.setDescription(kbId + " description");
            return knowledgeBase;
        }).toList();
    }

    private RoutingService newService() {
        return new RoutingService(chatProvider, properties, executor);
    }
}
