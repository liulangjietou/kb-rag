package io.kbrag.infrastructure.provider.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.ModelCallSpec;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.ModelCallMeter;
import io.kbrag.infrastructure.provider.ModelUsageSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope embedding provider driven by plain HTTP.
 *
 * <p>Transport choice: the OpenAI compatible endpoint {@code {baseUrl}/embeddings} is used rather
 * than the native DashScope path. The request and response schema is then the OpenAI one, so the
 * very same implementation serves DashScope, Azure OpenAI, Ollama and vLLM by changing
 * {@code kb.embedding.base-url}, which is exactly what the provider abstraction promises. No vendor
 * SDK is involved.
 *
 * <p>Failures are classified before they leave this class, so a failed indexing task can display an
 * actionable reason instead of a stack trace.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class DashScopeEmbeddingProvider implements EmbeddingProvider {

    /** Provider name recorded in the index registry. */
    public static final String PROVIDER_NAME = "dashscope";

    private static final String EMBEDDINGS_PATH = "/embeddings";
    private static final String FIELD_MODEL = "model";
    private static final String FIELD_INPUT = "input";
    private static final String FIELD_DIMENSIONS = "dimensions";
    private static final String FIELD_ENCODING_FORMAT = "encoding_format";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String ENCODING_FLOAT = "float";
    private static final String HEALTH_PROBE_TEXT = "ping";

    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_PAYMENT_REQUIRED = 402;

    private final KbProperties.Embedding config;
    private final RestClient restClient;
    private final ModelCallMeter modelCallMeter;

    public DashScopeEmbeddingProvider(KbProperties properties) {
        this(properties, ModelCallMeter.NOOP);
    }

    /** Builds the production adapter with durable quota and usage metering. */
    public DashScopeEmbeddingProvider(KbProperties properties, ModelCallMeter modelCallMeter) {
        this.config = properties.getEmbedding();
        this.modelCallMeter = modelCallMeter;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(config.getTimeoutMs());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .build();
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
    public int dimension() {
        return config.getDimension();
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public int maxBatchSize() {
        return config.getBatchSize();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (CollectionUtils.isEmpty(texts)) {
            return List.of();
        }
        if (texts.size() > maxBatchSize()) {
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "batch size exceeds the provider limit of " + maxBatchSize());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_MODEL, config.getModel());
        payload.put(FIELD_INPUT, texts);
        payload.put(FIELD_DIMENSIONS, config.getDimension());
        payload.put(FIELD_ENCODING_FORMAT, ENCODING_FLOAT);

        return ModelUsageSupport.execute(modelCallMeter,
                new ModelCallSpec(ModelUsageSupport.billingProvider(config.getProvider(), PROVIDER_NAME),
                        ModelCallSpec.EMBEDDING, config.getModel(),
                        ModelUsageSupport.textUpperBound(texts)),
                () -> call(payload), body -> parseVectors(body, texts.size()));
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            List<float[]> vectors = embed(List.of(HEALTH_PROBE_TEXT));
            return HealthStatus.up(config.getModel() + " dim=" + vectors.get(0).length);
        } catch (ProviderException e) {
            log.error("embedding health check failed, errorCode={}, type={}",
                    e.getErrorCode(), e.getErrorType(), e);
            return HealthStatus.down(e.getErrorType().name());
        }
    }

    private String call(Map<String, Object> payload) {
        try {
            return restClient.post()
                    .uri(EMBEDDINGS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JsonUtil.toJson(payload))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        String responseBody = new String(response.getBody().readAllBytes());
                        if (status.isError()) {
                            throw classify(status.value(), responseBody);
                        }
                        return responseBody;
                    });
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("embedding call failed, errorCode={}, type={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, ProviderErrorType.NETWORK_UNREACHABLE, e);
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.NETWORK_UNREACHABLE,
                    "embedding provider unreachable", e);
        }
    }

    private List<float[]> parseVectors(String body, int expectedSize) {
        JsonNode root = JsonUtil.parse(body, JsonNode.class);
        JsonNode data = root == null ? null : root.path(FIELD_DATA);
        if (data == null || !data.isArray() || data.size() != expectedSize) {
            log.error("embedding response size mismatch, errorCode={}, expected={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, expectedSize);
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "embedding response does not match the request size");
        }
        List<float[]> vectors = new ArrayList<>(expectedSize);
        for (JsonNode item : data) {
            JsonNode embedding = item.path(FIELD_EMBEDDING);
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            if (vector.length != config.getDimension()) {
                log.error("embedding dimension mismatch, errorCode={}, expected={}, actual={}",
                        ErrorCode.UPSTREAM_MODEL_ERROR, config.getDimension(), vector.length);
                throw new ProviderException(PROVIDER_NAME, ProviderErrorType.DIMENSION_MISMATCH,
                        "model returned dimension " + vector.length
                                + " while the index expects " + config.getDimension());
            }
            vectors.add(vector);
        }
        return vectors;
    }

    /**
     * Maps an HTTP failure onto the classified provider error the console displays.
     *
     * @param status HTTP status code
     * @param body   raw response body, only inspected for keywords
     * @return classified exception
     */
    private ProviderException classify(int status, String body) {
        String lower = body == null ? "" : body.toLowerCase();
        ProviderErrorType type;
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            type = ProviderErrorType.AUTH_FAILED;
        } else if (status == HTTP_TOO_MANY_REQUESTS || status == HTTP_PAYMENT_REQUIRED) {
            type = ProviderErrorType.QUOTA_EXCEEDED;
        } else if (lower.contains("model") && (lower.contains("not found") || lower.contains("not exist"))) {
            type = ProviderErrorType.MODEL_NOT_FOUND;
        } else if (lower.contains("too long") || lower.contains("maximum context") || lower.contains("length")) {
            type = ProviderErrorType.INPUT_TOO_LONG;
        } else {
            type = ProviderErrorType.UNKNOWN;
        }
        log.error("embedding provider rejected request, errorCode={}, type={}, httpStatus={}",
                ErrorCode.UPSTREAM_MODEL_ERROR, type, status);
        return new ProviderException(PROVIDER_NAME, type, "embedding provider returned status " + status);
    }
}
