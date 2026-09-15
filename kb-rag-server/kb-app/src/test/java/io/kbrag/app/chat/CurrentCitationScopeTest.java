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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 当前编号白名单来自本轮检索集合，历史引用和原文章节不能扩大编号范围。 */
class CurrentCitationScopeTest {
    private final ChatProvider provider = mock(ChatProvider.class);
    private final AppConfigSnapshot snapshot = new AppConfigSnapshot();

    @Test
    void shouldBindCompleteAndStreamToTheSameExplicitCurrentCitationSet() {
        AnswerGenerationService service = service();
        List<RetrievalNodeView> current = List.of(
                RetrievalNodeView.builder().content("## 八、操作安全\n重复请求不重复执行").build(),
                RetrievalNodeView.builder().content("写操作必须支持幂等键").build());
        List<ChatMessage> history = List.of(ChatMessage.assistant("历史回答使用了 [8]"));
        service.generate(snapshot, "再次提交应该执行几次？", history, current);
        service.stream(snapshot, "再次提交应该执行几次？", history, current, ignored -> { });
        ArgumentCaptor<String> complete = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stream = ArgumentCaptor.forClass(String.class);
        verify(provider).complete(complete.capture(), anyList());
        verify(provider).stream(stream.capture(), anyList(), any(), any());
        for (String system : List.of(complete.getValue(), stream.getValue())) {
            assertTrue(system.contains("合法引用编号完整列表：[1] [2]"));
            assertTrue(system.contains("列表以外的编号一律无效"));
            assertFalse(system.contains("[8]"));
            assertFalse(system.contains("## 八"));
        }
    }

    @Test
    void shouldExplicitlyForbidCitationNumbersWhenCurrentRetrievalIsEmpty() {
        service().generate(snapshot, "没有资料的问题", List.of(), List.of());
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(provider).complete(system.capture(), anyList());
        assertTrue(system.getValue().contains("本轮没有可引用资料"));
    }

    @Test
    void shouldKeepTheApplicationCitationSwitchEffective() {
        snapshot.getPrompt().setCitationEnabled(false);
        service().generate(snapshot, "不显示引用的问题", List.of(), List.of());
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(provider).complete(system.capture(), anyList());
        assertFalse(system.getValue().contains("合法引用编号完整列表"));
        assertFalse(system.getValue().contains("本轮没有可引用资料"));
    }

    private AnswerGenerationService service() {
        ChatProviderFactory factory = mock(ChatProviderFactory.class);
        when(factory.forModel(any())).thenReturn(provider);
        when(provider.isConfigured()).thenReturn(true);
        when(provider.complete(anyString(), anyList())).thenReturn("合成回答");
        return new AnswerGenerationService(factory, new ChatPromptAssembler());
    }
}
