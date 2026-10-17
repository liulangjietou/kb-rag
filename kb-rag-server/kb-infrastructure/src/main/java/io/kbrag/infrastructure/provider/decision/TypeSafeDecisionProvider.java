package io.kbrag.infrastructure.provider.decision;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.ModelCallSpec;
import io.kbrag.domain.port.DecisionProvider;
import io.kbrag.domain.port.ModelCallMeter;
import io.kbrag.infrastructure.provider.DashScopeHttp;
import io.kbrag.infrastructure.provider.ModelUsageSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TypeSafe Jev 决策模型适配器，直接走 {@code POST /v1/systemone}。
 *
 * <p>TypeSafe 只提供 Python / JS SDK，这里与其余适配器一样按线协议发 HTTP，复用 {@link DashScopeHttp} 的
 * 传输与失败分类：401/403 归为凭据错误，429 归为配额，其余（含 422 校验失败、529 过载）归为未知。
 *
 * <p>答案必须覆盖全部问题 id：缺任何一个都整体失败而不是补默认值。调用方拿到的每个概率都要能直接驱动
 * 分支，一个编出来的 0 会让路由静默漏库、让重排静默沉底，比整体降级更难发现。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class TypeSafeDecisionProvider implements DecisionProvider {

    /** 提供方名称。 */
    public static final String PROVIDER_NAME = "typesafe";

    private static final String STAGE = "decision";
    private static final String ENDPOINT = "/v1/systemone";
    private static final String FIELD_MODEL = "model";
    private static final String FIELD_STATE = "state";
    private static final String FIELD_QUESTIONS = "questions";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_INSTRUCTIONS = "instructions";
    private static final String FIELD_ANSWERS = "answers";
    private static final String TYPE_NOUL = "noul";
    private static final String HEALTH_PROBE_KEY = "probe";
    private static final String HEALTH_PROBE_STATE = "ping";
    private static final String HEALTH_PROBE_QUESTION = "Is the state a single word?";

    private final KbProperties.Decision config;
    private final RestClient restClient;
    private final ModelCallMeter modelCallMeter;

    public TypeSafeDecisionProvider(KbProperties.Decision config, ModelCallMeter modelCallMeter) {
        this.config = config;
        this.modelCallMeter = modelCallMeter;
        this.restClient = DashScopeHttp.client(config.getBaseUrl(), config.getApiKey(), config.getTimeoutMs());
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String model() {
        return config.getModel();
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public Map<String, Double> evaluateNouls(Object state, Map<String, Object> questions) {
        if (questions == null || questions.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> typedQuestions = new LinkedHashMap<>(questions.size() * 2);
        questions.forEach((id, instructions) -> {
            Map<String, Object> question = new LinkedHashMap<>();
            question.put(FIELD_TYPE, TYPE_NOUL);
            question.put(FIELD_INSTRUCTIONS, instructions);
            typedQuestions.put(id, question);
        });
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_MODEL, config.getModel());
        payload.put(FIELD_STATE, state);
        payload.put(FIELD_QUESTIONS, typedQuestions);

        // 按整个请求体估算预留量：状态与每个问题都计入输入 token，输出免费但仍以实际 usage 结算
        String body = JsonUtil.toJson(payload);
        return ModelUsageSupport.execute(modelCallMeter,
                new ModelCallSpec(ModelUsageSupport.billingProvider(config.getProvider(), PROVIDER_NAME),
                        ModelCallSpec.DECISION, config.getModel(), ModelUsageSupport.textUpperBound(List.of(body))),
                () -> DashScopeHttp.post(restClient, ENDPOINT, payload, PROVIDER_NAME, STAGE),
                raw -> parseNouls(raw, questions.keySet()));
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            evaluateNouls(HEALTH_PROBE_STATE, Map.of(HEALTH_PROBE_KEY, HEALTH_PROBE_QUESTION));
            return HealthStatus.up(config.getModel());
        } catch (ProviderException e) {
            log.error("decision health check failed, errorCode={}, type={}",
                    e.getErrorCode(), e.getErrorType(), e);
            return HealthStatus.down(e.getErrorType().name());
        }
    }

    /**
     * 按问题 id 取回每个 Noul 概率。
     *
     * @param body 原始响应
     * @param ids  请求中的问题 id
     * @return 问题 id 到概率，顺序与请求一致
     */
    private Map<String, Double> parseNouls(String body, Iterable<String> ids) {
        JsonNode root = JsonUtil.parse(body, JsonNode.class);
        JsonNode answers = root == null ? null : root.path(FIELD_ANSWERS);
        if (answers == null || !answers.isObject()) {
            throw malformed("decision response carries no answers object");
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (String id : ids) {
            JsonNode value = answers.path(id).path(TYPE_NOUL);
            if (!value.isNumber()) {
                throw malformed("decision response misses the noul answer of question " + id);
            }
            result.put(id, value.asDouble());
        }
        return result;
    }

    private ProviderException malformed(String message) {
        log.error("decision response malformed, errorCode={}, detail={}", ErrorCode.UPSTREAM_MODEL_ERROR, message);
        return new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN, message);
    }
}
