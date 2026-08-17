package io.kbrag.app.chat;

import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.ChatProviderFactory;
import io.kbrag.domain.service.ChatPromptAssembler;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The single final-answer generation path shared by served chat, console preview and offline evaluation.
 *
 * <p>Owning provider resolution and prompt assembly here prevents the quality gate from measuring a
 * hand-written approximation of production. Retrieval remains outside this service because online calls
 * resolve routing, snapshots and request overrides while an evaluation deliberately supplies the ranked
 * nodes of the configuration under test.
 *
 * @author owlzhangfq@gmail.com
 */
@Service
@RequiredArgsConstructor
public class AnswerGenerationService {

    private final ChatProviderFactory chatProviderFactory;
    private final ChatPromptAssembler chatPromptAssembler;

    /**
     * Generates one final answer.
     *
     * @param snapshot frozen application configuration
     * @param query    current user question
     * @param history  conversation history
     * @param nodes    ranked retrieved passages
     * @return generated answer
     */
    public String generate(AppConfigSnapshot snapshot, String query, List<ChatMessage> history,
                           List<RetrievalNodeView> nodes) {
        ChatProvider provider = requireProvider(snapshot);
        return provider.complete(chatPromptAssembler.systemPrompt(snapshot.promptOrDefaults()),
                promptMessages(query, history, nodes));
    }

    /**
     * Streams one final answer through the same prompt path as {@link #generate}.
     *
     * @param snapshot frozen application configuration
     * @param query    current user question
     * @param history  conversation history
     * @param nodes    ranked retrieved passages
     * @param onDelta  receiver of generated pieces
     */
    public void stream(AppConfigSnapshot snapshot, String query, List<ChatMessage> history,
                       List<RetrievalNodeView> nodes, Consumer<String> onDelta) {
        ChatProvider provider = requireProvider(snapshot);
        provider.stream(chatPromptAssembler.systemPrompt(snapshot.promptOrDefaults()),
                promptMessages(query, history, nodes), onDelta);
    }

    /**
     * Checks whether the generation model frozen by a snapshot is callable.
     *
     * @param snapshot frozen application configuration
     * @return {@code true} when its provider has usable credentials
     */
    public boolean isAvailable(AppConfigSnapshot snapshot) {
        return providerOf(snapshot).isConfigured();
    }

    private ChatProvider requireProvider(AppConfigSnapshot snapshot) {
        ChatProvider provider = providerOf(snapshot);
        if (!provider.isConfigured()) {
            throw new BizException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    "问答生成不可用：当前部署未配置对话模型（零 Key 模式），"
                            + "请在系统设置中配置对话模型 Provider 后重试；检索接口不受影响");
        }
        return provider;
    }

    private ChatProvider providerOf(AppConfigSnapshot snapshot) {
        return chatProviderFactory.forModel(snapshot == null ? null : snapshot.getChatModel());
    }

    private List<ChatMessage> promptMessages(String query, List<ChatMessage> history,
                                             List<RetrievalNodeView> nodes) {
        List<String> passages = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(nodes)) {
            for (RetrievalNodeView node : nodes) {
                passages.add(node.getContent());
            }
        }
        List<ChatMessage> messages = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(chatPromptAssembler.userPrompt(query, passages)));
        return messages;
    }
}
