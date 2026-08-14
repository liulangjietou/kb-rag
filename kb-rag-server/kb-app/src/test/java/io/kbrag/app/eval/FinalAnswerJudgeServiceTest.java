package io.kbrag.app.eval;

import io.kbrag.domain.port.ChatProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers structured final-answer judge parsing and failure semantics.
 *
 * @author owlzhangfq@gmail.com
 */
class FinalAnswerJudgeServiceTest {

    @Test
    void shouldParseAndClampACompleteStructuredJudgment() {
        ChatProvider provider = mock(ChatProvider.class);
        when(provider.complete(any(), any(List.class))).thenReturn("""
                ```json
                {"correctness":6,"faithfulness":4,"completeness":3,
                 "citation_correctness":5,"citation_completeness":2,
                 "refusal_correct":true,"reason":"supported"}
                ```""");
        FinalAnswerJudgeService service = new FinalAnswerJudgeService(provider);

        FinalAnswerJudgeService.JudgeOutcome outcome = service.judge("expected", false, true,
                "answer [1]", List.of("passage"));

        assertNotNull(outcome.judgment());
        assertEquals(5, outcome.judgment().correctness());
        assertEquals(4, outcome.judgment().score());
        assertNull(outcome.failureReason());
    }

    @Test
    void shouldReturnAFailureInsteadOfInventingZeroScoresForInvalidJson() {
        ChatProvider provider = mock(ChatProvider.class);
        when(provider.complete(any(), any(List.class))).thenReturn("not-json");

        FinalAnswerJudgeService.JudgeOutcome outcome = new FinalAnswerJudgeService(provider)
                .judge("expected", false, false, "answer", List.of("passage"));

        assertNull(outcome.judgment());
        assertEquals("final answer judge call failed", outcome.failureReason());
    }
}
