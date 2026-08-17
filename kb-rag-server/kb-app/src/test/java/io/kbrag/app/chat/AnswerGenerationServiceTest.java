package io.kbrag.app.chat;

import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.ChatProviderFactory;
import io.kbrag.domain.service.ChatPromptAssembler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the shared generation path used by online chat and final-answer evaluation.
 *
 * @author owlzhangfq@gmail.com
 */
class AnswerGenerationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldPreserveHistoryAndAttachNumberedPassagesOnlyToTheCurrentQuestion() {
        ChatProviderFactory factory = mock(ChatProviderFactory.class);
        ChatProvider provider = mock(ChatProvider.class);
        when(factory.forModel("qwen-plus")).thenReturn(provider);
        when(provider.isConfigured()).thenReturn(true);
        when(provider.complete(any(), any(List.class))).thenReturn("answer");
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setChatModel("qwen-plus");
        RetrievalNodeView node = RetrievalNodeView.builder().content("evidence").metadata(Map.of()).build();
        AnswerGenerationService service = new AnswerGenerationService(factory, new ChatPromptAssembler());

        String answer = service.generate(snapshot, "current", List.of(ChatMessage.user("history")),
                List.of(node));

        assertEquals("answer", answer);
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass((Class<List<ChatMessage>>) (Class<?>) List.class);
        verify(provider).complete(any(), captor.capture());
        assertEquals("history", captor.getValue().get(0).getContent());
        assertTrue(captor.getValue().get(1).getContent().contains("[1] evidence"));
        assertTrue(captor.getValue().get(1).getContent().contains("current"));
    }
}
