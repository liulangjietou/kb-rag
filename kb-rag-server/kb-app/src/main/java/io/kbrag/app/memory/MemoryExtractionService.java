package io.kbrag.app.memory;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.MemoryFragmentRule;
import io.kbrag.domain.entity.MemoryNode;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.MemoryCommand;
import io.kbrag.domain.model.MemoryProfileField;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.ChatProviderFactory;
import io.kbrag.domain.service.MemoryExtractionParser;
import io.kbrag.domain.service.MemoryPromptAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The LLM stages of the memory pipeline, the M19 contract: fragment extraction, profile
 * extraction, and the two optional search aids (intent recognition, query rewrite).
 *
 * <p><b>Two very different failure postures, on purpose.</b> Extraction is the point of an add
 * call, so a missing or failing model is surfaced as {@code UPSTREAM_MODEL_ERROR} - silently
 * writing nothing would make the caller believe the conversation was remembered. The search aids
 * are accelerants on top of a retrieval that works without them, so their failures degrade: no
 * model means no rewrite and "recall everything", never a failed search.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {

    /** Intent recognition instruction; the model answers with one literal. */
    private static final String INTENT_SYSTEM_PROMPT =
            "你是记忆召回判别助手。判断用户当前的提问是否需要查询该用户的历史记忆才能更好地回答。"
                    + "需要时只输出 YES，不需要时只输出 NO，不要输出其他任何文字。";

    /** Literal that marks a positive intent verdict. */
    private static final String INTENT_POSITIVE = "YES";

    /** Query rewrite instruction; the model answers with the rewritten query alone. */
    private static final String REWRITE_SYSTEM_PROMPT =
            "你是查询改写助手。将用户口语化、含指代或含冗余信息的提问改写为一句适合检索用户记忆的简洁查询，"
                    + "保留原意，不要添加原文没有的信息。只输出改写后的查询本身，不要输出其他任何文字。";

    /** Longest rewritten query accepted; anything longer is the model rambling, not rewriting. */
    private static final int MAX_REWRITE_LENGTH = 512;

    private final ChatProviderFactory chatProviderFactory;
    private final MemoryPromptAssembler memoryPromptAssembler;
    private final MemoryExtractionParser memoryExtractionParser;

    /**
     * Tells whether a chat model is available for extraction.
     *
     * @return {@code true} when extraction calls can be issued
     */
    public boolean isConfigured() {
        return chatProviderFactory.forModel(null).isConfigured();
    }

    /**
     * Extracts memory commands out of one conversation under one fragment rule.
     *
     * @param rule        fragment rule being applied
     * @param messages    conversation turns as the caller sent them
     * @param oldMemories entity's existing memories under the rule, only consulted for PRO
     * @return validated commands, empty when the model found nothing worth remembering
     */
    public List<MemoryCommand> extractFragments(MemoryFragmentRule rule, List<ChatMessage> messages,
                                                List<MemoryNode> oldMemories) {
        ChatProvider provider = requireProvider();
        String answer = completeOrFail(provider,
                memoryPromptAssembler.fragmentSystemPrompt(rule, oldMemories),
                memoryPromptAssembler.conversationPrompt(messages));
        Set<String> knownNodeIds = oldMemories == null ? Set.of()
                : oldMemories.stream().map(MemoryNode::getNodeId).collect(Collectors.toSet());
        return memoryExtractionParser.parseFragments(answer, knownNodeIds,
                memoryPromptAssembler.updateAllowed(rule));
    }

    /**
     * Extracts profile attributes out of one conversation under one profile rule.
     *
     * @param fields   attribute definitions of the profile rule
     * @param messages conversation turns as the caller sent them
     * @return extracted attributes, empty when the conversation supported none
     */
    public Map<String, String> extractProfile(List<MemoryProfileField> fields,
                                              List<ChatMessage> messages) {
        ChatProvider provider = requireProvider();
        String answer = completeOrFail(provider,
                memoryPromptAssembler.profileSystemPrompt(fields),
                memoryPromptAssembler.conversationPrompt(messages));
        Set<String> allowedFields = fields.stream()
                .map(MemoryProfileField::name).collect(Collectors.toSet());
        return memoryExtractionParser.parseProfile(answer, allowedFields);
    }

    /**
     * Decides whether the current query needs memory recall at all.
     *
     * <p>Degrades to {@code true} without a model or on a model failure: recalling memories that
     * were not needed costs a little relevance, skipping ones that were needed loses the answer.
     *
     * @param query current user query
     * @return {@code false} only when the model explicitly judged recall unnecessary
     */
    public boolean shouldRecall(String query) {
        ChatProvider provider = chatProviderFactory.forModel(null);
        if (!provider.isConfigured()) {
            return true;
        }
        try {
            String answer = provider.complete(INTENT_SYSTEM_PROMPT, query);
            return answer == null || answer.trim().toUpperCase().contains(INTENT_POSITIVE);
        } catch (Exception e) {
            log.error("memory intent recognition degraded to recall, errorCode={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, e);
            return true;
        }
    }

    /**
     * Rewrites a colloquial query into a retrieval friendly one.
     *
     * <p>Degrades to the original query without a model, on a model failure, or when the answer
     * does not look like a rewrite (blank or implausibly long).
     *
     * @param query current user query
     * @return rewritten query, or the original when rewriting was not possible
     */
    public String rewrite(String query) {
        ChatProvider provider = chatProviderFactory.forModel(null);
        if (!provider.isConfigured()) {
            return query;
        }
        try {
            String answer = provider.complete(REWRITE_SYSTEM_PROMPT, query);
            if (answer == null || answer.isBlank() || answer.trim().length() > MAX_REWRITE_LENGTH) {
                return query;
            }
            return answer.trim();
        } catch (Exception e) {
            log.error("memory query rewrite degraded to original, errorCode={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, e);
            return query;
        }
    }

    /**
     * Resolves the deployment's default chat provider or fails the extraction.
     *
     * @return configured provider
     */
    private ChatProvider requireProvider() {
        ChatProvider provider = chatProviderFactory.forModel(null);
        if (!provider.isConfigured()) {
            throw new BizException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    "未配置对话模型，无法从 messages 抽取记忆；可改用 custom_content 直接写入");
        }
        return provider;
    }

    /**
     * Issues one completion, translating any provider failure into the upstream error the add
     * contract promises.
     *
     * @param provider     configured provider
     * @param systemPrompt system instruction
     * @param userPrompt   user message
     * @return raw model answer
     */
    private String completeOrFail(ChatProvider provider, String systemPrompt, String userPrompt) {
        try {
            return provider.complete(systemPrompt, userPrompt);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("memory extraction model call failed, errorCode={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, e);
            throw new BizException(ErrorCode.UPSTREAM_MODEL_ERROR, "记忆抽取模型调用失败", e);
        }
    }
}
