package io.kbrag.infrastructure.provider.rerank;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.DecisionProvider;
import io.kbrag.domain.port.RerankProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用决策模型实现重排：每个候选段落是一个 Noul 问题「该段落是否包含能回答查询的信息」，概率即相关分。
 *
 * <p>组合而非另起一套 HTTP：凭据、计量与失败分类都属于 {@link DecisionProvider}，这里只负责把重排语义翻译成
 * 决策问题。Noul 概率天然落在 {@code [0,1]} 且跨查询可比，满足重排阈值依赖的「绝对分」契约。
 *
 * <p>查询放进状态、段落放进各自的问题，Jev 对状态只读一次并行评估全部问题。请求按
 * {@link KbProperties.Decision#getMaxRequestChars()} 拆批并串行发出：重排本身已在检索线程池里执行，
 * 再向同一有界池并发提交子任务会在高负载下自我饿死。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class DecisionRerankProvider implements RerankProvider {

    /** {@code kb.rerank.provider} 取该值时启用本实现。 */
    public static final String PROVIDER_NAME = "typesafe";

    private static final String FIELD_QUERY = "query";
    private static final String FIELD_QUESTION = "question";
    private static final String FIELD_PASSAGE = "passage";
    private static final String QUESTION_ID_PREFIX = "d";
    private static final String RELEVANCE_QUESTION =
            "Does the passage contain information that answers the query in the state?";
    private static final String HEALTH_PROBE_QUERY = "ping";

    private final DecisionProvider decisionProvider;
    private final int candidateLimit;
    private final int maxRequestChars;

    public DecisionRerankProvider(DecisionProvider decisionProvider, KbProperties properties) {
        this.decisionProvider = decisionProvider;
        this.candidateLimit = properties.getRerank().getCandidateLimit();
        this.maxRequestChars = properties.getDecision().getMaxRequestChars();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String model() {
        return decisionProvider.model();
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public List<Double> rerank(String query, List<String> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return List.of();
        }
        if (documents.size() > candidateLimit) {
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "candidate count exceeds the configured limit of " + candidateLimit);
        }
        Map<String, Object> state = Map.of(FIELD_QUERY, query);
        List<Double> scores = new ArrayList<>(documents.size());
        int start = 0;
        while (start < documents.size()) {
            int end = batchEnd(query, documents, start);
            scores.addAll(scoreBatch(state, documents, start, end));
            start = end;
        }
        return scores;
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            rerank(HEALTH_PROBE_QUERY, List.of(HEALTH_PROBE_QUERY));
            return HealthStatus.up(model());
        } catch (ProviderException e) {
            log.error("decision rerank health check failed, errorCode={}, type={}",
                    e.getErrorCode(), e.getErrorType(), e);
            return HealthStatus.down(e.getErrorType().name());
        }
    }

    /**
     * 从 {@code start} 起贪心装批，直到累计字符数超过上限；至少装一个，超长单段交给服务端拒绝并整体降级。
     */
    private int batchEnd(String query, List<String> documents, int start) {
        long used = query == null ? 0 : query.length();
        int end = start;
        while (end < documents.size()) {
            int length = documents.get(end) == null ? 0 : documents.get(end).length();
            if (end > start && used + length > maxRequestChars) {
                break;
            }
            used += length;
            end++;
        }
        return end;
    }

    private List<Double> scoreBatch(Map<String, Object> state, List<String> documents, int start, int end) {
        Map<String, Object> questions = new LinkedHashMap<>((end - start) * 2);
        for (int i = start; i < end; i++) {
            Map<String, Object> instructions = new LinkedHashMap<>();
            instructions.put(FIELD_QUESTION, RELEVANCE_QUESTION);
            instructions.put(FIELD_PASSAGE, documents.get(i));
            questions.put(QUESTION_ID_PREFIX + i, instructions);
        }
        Map<String, Double> nouls = decisionProvider.evaluateNouls(state, questions);
        List<Double> scores = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            scores.add(nouls.get(QUESTION_ID_PREFIX + i));
        }
        return scores;
    }
}
