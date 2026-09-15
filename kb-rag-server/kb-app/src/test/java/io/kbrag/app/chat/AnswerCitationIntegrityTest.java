package io.kbrag.app.chat;

import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.ChatCancellation;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.ChatProviderFactory;
import io.kbrag.domain.service.ChatPromptAssembler;
import io.kbrag.domain.mapper.DocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实试跑发现五条证据却生成 [10]，将完整与流式路径都纳入完成门槛。 */
class AnswerCitationIntegrityTest {
    private final ChatProvider provider = mock(ChatProvider.class);
    private final AppConfigSnapshot snapshot = new AppConfigSnapshot();
    private final List<RetrievalNodeView> nodes = List.of(RetrievalNodeView.builder().content("写操作使用幂等键").build());

    private AnswerGenerationService service() {
        ChatProviderFactory factory = mock(ChatProviderFactory.class);
        when(factory.forModel(any())).thenReturn(provider);
        when(provider.isConfigured()).thenReturn(true);
        return new AnswerGenerationService(factory, new ChatPromptAssembler(), mock(DocumentMapper.class));
    }

    @Test
    void shouldRejectNonexistentCitationInsteadOfReturningSuccessfulAnswer() {
        when(provider.complete(any(), any(List.class))).thenReturn("写操作需要幂等键 [10]。");
        BizException failure = assertThrows(BizException.class,
                () -> service().generate(snapshot, "为什么？", List.of(), nodes));
        assertEquals(ErrorCode.ANSWER_CITATION_INVALID, failure.getErrorCode());
    }

    @Test
    void shouldFailAfterStreamEvenWhenCitationIsSplitAcrossDeltas() {
        List<String> received = new ArrayList<>();
        doAnswer(call -> {
            Consumer<String> delta = call.getArgument(2);
            for (String piece : List.of("写操作", " [", "10", "]。")) delta.accept(piece);
            return null;
        }).when(provider).stream(any(), any(List.class), any(), any(ChatCancellation.class));
        assertThrows(BizException.class,
                () -> service().stream(snapshot, "为什么？", List.of(), nodes, received::add));
        assertEquals("写操作 [10]。", String.join("", received));
    }

    @Test
    void shouldPreserveValidCitationsAndLiteralCodeAndLinks() {
        String answer = "写操作 [1]。`array[10]`\n\n```java\nitems[10]\n```\n[10](https://example.com)";
        when(provider.complete(any(), any(List.class))).thenReturn(answer);
        assertEquals(answer, service().generate(snapshot, "为什么？", List.of(), nodes));
    }

    @ParameterizedTest
    @ValueSource(strings = {"无效 [0]", "越界 [2]", "部分有效 [1][2]", "过长 [999999999999999999]"})
    void shouldRejectInvalidIndexesEvenWhenCitationInstructionIsOptional(String answer) {
        snapshot.setPrompt(new io.kbrag.domain.model.AppPromptConfig());
        snapshot.getPrompt().setCitationEnabled(false);
        when(provider.complete(any(), any(List.class))).thenReturn(answer);
        assertThrows(BizException.class, () -> service().generate(snapshot, "问题", List.of(), nodes));
    }

    @Test
    void shouldNotTreatHistoricalCitationsAsCurrentEvidence() {
        when(provider.complete(any(), any(List.class))).thenReturn("沿用上一轮 [10]");
        assertThrows(BizException.class, () -> service().generate(snapshot, "继续",
                List.of(ChatMessage.assistant("旧回答 [10]")), nodes));
    }

    @Test
    void shouldKeepCancellationAndValidStreamingBehavior() {
        List<String> received = new ArrayList<>();
        ChatCancellation cancellation = new ChatCancellation();
        doAnswer(call -> {
            call.<Consumer<String>>getArgument(2).accept("依据 [1]。");
            cancellation.cancel();
            return null;
        }).when(provider).stream(any(), any(List.class), any(), any(ChatCancellation.class));
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> service().stream(snapshot, "问题", List.of(), nodes, received::add, cancellation));
        assertEquals(List.of("依据 [1]。"), received);
    }
}
