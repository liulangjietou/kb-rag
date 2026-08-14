package io.kbrag.infrastructure.provider.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.ModelCallSpec;
import io.kbrag.domain.port.RerankProvider;
import io.kbrag.domain.port.ModelCallMeter;
import io.kbrag.infrastructure.provider.DashScopeHttp;
import io.kbrag.infrastructure.provider.ModelUsageSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope rerank provider driven by plain HTTP.
 *
 * <p>Transport choice. Unlike embedding and chat, rerank has no OpenAI compatible equivalent, so the
 * native endpoint {@code /api/v1/services/rerank/text-rerank/text-rerank} is used with its own
 * request envelope: the payload nests the query and the documents under {@code input} and the tuning
 * knobs under {@code parameters}, and the answer arrives under {@code output.results}.
 *
 * <p>Score contract. The endpoint returns {@code relevance_score} already normalised to
 * {@code [0,1]}, which is precisely why the retrieval pipeline treats rerank as the only absolute
 * score: it is comparable across queries, so a threshold on it means the same thing every time.
 *
 * <p>Result alignment. The endpoint answers with an index per result and may reorder or omit
 * entries, so scores are scattered back into a list aligned with the submitted documents. A document
 * the provider did not score keeps a zero, which ranks it last instead of dropping it silently.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class DashScopeRerankProvider implements RerankProvider {

    /** Provider name reported by the model status endpoint. */
    public static final String PROVIDER_NAME = "dashscope";

    private static final String STAGE = "rerank";
    private static final String FIELD_MODEL = "model";
    private static final String FIELD_INPUT = "input";
    private static final String FIELD_QUERY = "query";
    private static final String FIELD_DOCUMENTS = "documents";
    private static final String FIELD_PARAMETERS = "parameters";
    private static final String FIELD_RETURN_DOCUMENTS = "return_documents";
    private static final String FIELD_TOP_N = "top_n";
    private static final String FIELD_OUTPUT = "output";
    private static final String FIELD_RESULTS = "results";
    private static final String FIELD_INDEX = "index";
    private static final String FIELD_RELEVANCE_SCORE = "relevance_score";
    private static final String HEALTH_PROBE_QUERY = "ping";
    private static final double UNSCORED = 0.0d;

    private final KbProperties.Rerank config;
    private final RestClient restClient;
    private final ModelCallMeter modelCallMeter;

    public DashScopeRerankProvider(KbProperties properties) {
        this(properties, ModelCallMeter.NOOP);
    }

    /** Builds the production adapter with durable quota and usage metering. */
    public DashScopeRerankProvider(KbProperties properties, ModelCallMeter modelCallMeter) {
        this.config = properties.getRerank();
        this.modelCallMeter = modelCallMeter;
        this.restClient = DashScopeHttp.client(null, config.getApiKey(), config.getTimeoutMs());
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
    public List<Double> rerank(String query, List<String> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return List.of();
        }
        if (documents.size() > config.getCandidateLimit()) {
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "candidate count exceeds the configured limit of " + config.getCandidateLimit());
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put(FIELD_QUERY, query);
        input.put(FIELD_DOCUMENTS, documents);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(FIELD_RETURN_DOCUMENTS, false);
        parameters.put(FIELD_TOP_N, documents.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_MODEL, config.getModel());
        payload.put(FIELD_INPUT, input);
        payload.put(FIELD_PARAMETERS, parameters);

        List<String> meteredTexts = new ArrayList<>(documents.size() + 1);
        meteredTexts.add(query);
        meteredTexts.addAll(documents);
        return ModelUsageSupport.execute(modelCallMeter,
                new ModelCallSpec(ModelUsageSupport.billingProvider(config.getProvider(), PROVIDER_NAME),
                        ModelCallSpec.RERANK, config.getModel(),
                        ModelUsageSupport.textUpperBound(meteredTexts)),
                () -> DashScopeHttp.post(restClient, config.getUrl(), payload, PROVIDER_NAME, STAGE),
                body -> parseScores(body, documents.size()));
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            rerank(HEALTH_PROBE_QUERY, List.of(HEALTH_PROBE_QUERY));
            return HealthStatus.up(config.getModel());
        } catch (ProviderException e) {
            log.error("rerank health check failed, errorCode={}, type={}",
                    e.getErrorCode(), e.getErrorType(), e);
            return HealthStatus.down(e.getErrorType().name());
        }
    }

    /**
     * Scatters the returned scores back into the submitted document order.
     *
     * @param body         raw response body
     * @param expectedSize number of submitted documents
     * @return one score per submitted document
     */
    private List<Double> parseScores(String body, int expectedSize) {
        JsonNode root = JsonUtil.parse(body, JsonNode.class);
        JsonNode results = root == null ? null : root.path(FIELD_OUTPUT).path(FIELD_RESULTS);
        if (results == null || !results.isArray()) {
            log.error("rerank response missing results, errorCode={}, expected={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, expectedSize);
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "rerank response carries no result array");
        }
        List<Double> scores = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            scores.add(UNSCORED);
        }
        for (JsonNode result : results) {
            int index = result.path(FIELD_INDEX).asInt(-1);
            if (index < 0 || index >= expectedSize) {
                continue;
            }
            scores.set(index, result.path(FIELD_RELEVANCE_SCORE).asDouble(UNSCORED));
        }
        return scores;
    }
}
