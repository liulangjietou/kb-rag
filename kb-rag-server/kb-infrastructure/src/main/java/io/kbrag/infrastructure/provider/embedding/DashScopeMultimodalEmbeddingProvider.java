package io.kbrag.infrastructure.provider.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.ImageInput;
import io.kbrag.domain.port.MultimodalEmbeddingProvider;
import io.kbrag.infrastructure.provider.DashScopeHttp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope multimodal embedding provider driven by plain HTTP, the M14 contract section 6.1.
 *
 * <p>Transport choice. Like rerank and unlike text embedding, the multimodal endpoint has no OpenAI
 * compatible equivalent, so the native path
 * {@code /api/v1/services/embeddings/multimodal-embedding/multimodal-embedding} is used with its own
 * envelope: the payload nests a {@code contents} array under {@code input}, each element being either
 * a {@code text} or an {@code image}, and the answer arrives under {@code output.embeddings} with an
 * index per element. No vendor SDK is involved.
 *
 * <p><b>Why a data URL and not a pre signed link.</b> The same reasoning as the vision provider: the
 * bucket is private and the model provider sits outside the deployment, so inlining the bytes keeps
 * the storage private and removes any dependency on the provider reaching this network. Should the
 * endpoint reject inlined bytes, the fallback documented in the contract is a pre signed URL, which
 * this class would switch to without touching its port.
 *
 * <p>Result alignment. The endpoint answers with an index per embedding and may reorder entries, so
 * vectors are scattered back into the submitted order and the dimension of each is verified against
 * the configured one, which turns a silent model mismatch into an actionable failure instead of a
 * corrupt index.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class DashScopeMultimodalEmbeddingProvider implements MultimodalEmbeddingProvider {

    /** Provider name recorded in the multimodal index registry. */
    public static final String PROVIDER_NAME = "dashscope";

    private static final String STAGE = "multimodal-embedding";
    private static final String FIELD_MODEL = "model";
    private static final String FIELD_INPUT = "input";
    private static final String FIELD_CONTENTS = "contents";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_IMAGE = "image";
    private static final String FIELD_OUTPUT = "output";
    private static final String FIELD_EMBEDDINGS = "embeddings";
    private static final String FIELD_INDEX = "index";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String DATA_URL_PREFIX = "data:";
    private static final String DATA_URL_BASE64 = ";base64,";
    private static final String DEFAULT_MEDIA_TYPE = "image/png";
    private static final String HEALTH_PROBE_TEXT = "ping";

    private final KbProperties.MultimodalEmbedding config;
    private final RestClient restClient;

    public DashScopeMultimodalEmbeddingProvider(KbProperties properties) {
        this.config = properties.getMultimodalEmbedding();
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
    public List<float[]> embedTexts(List<String> texts) {
        if (CollectionUtils.isEmpty(texts)) {
            return List.of();
        }
        List<Map<String, Object>> contents = new ArrayList<>(texts.size());
        for (String text : texts) {
            contents.add(Map.of(FIELD_TEXT, text));
        }
        return embed(contents);
    }

    @Override
    public List<float[]> embedImages(List<ImageInput> images) {
        if (CollectionUtils.isEmpty(images)) {
            return List.of();
        }
        List<Map<String, Object>> contents = new ArrayList<>(images.size());
        for (ImageInput image : images) {
            contents.add(Map.of(FIELD_IMAGE, dataUrl(image.content(), image.mediaType())));
        }
        return embed(contents);
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            List<float[]> vectors = embedTexts(List.of(HEALTH_PROBE_TEXT));
            return HealthStatus.up(config.getModel() + " dim=" + vectors.get(0).length);
        } catch (ProviderException e) {
            log.error("multimodal embedding health check failed, errorCode={}, type={}",
                    e.getErrorCode(), e.getErrorType(), e);
            return HealthStatus.down(e.getErrorType().name());
        }
    }

    /**
     * Issues one request for a batch of contents and returns the aligned vectors.
     *
     * @param contents request items, texts or images, size already bounded by the caller
     * @return one vector per content in submitted order
     */
    private List<float[]> embed(List<Map<String, Object>> contents) {
        if (contents.size() > maxBatchSize()) {
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "batch size exceeds the provider limit of " + maxBatchSize());
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(FIELD_CONTENTS, contents);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_MODEL, config.getModel());
        payload.put(FIELD_INPUT, input);

        String body = DashScopeHttp.post(restClient, config.getUrl(), payload, PROVIDER_NAME, STAGE);
        return parseVectors(body, contents.size());
    }

    /**
     * Scatters the returned embeddings back into the submitted order and verifies each dimension.
     *
     * @param body         raw response body
     * @param expectedSize number of submitted contents
     * @return one vector per submitted content
     */
    private List<float[]> parseVectors(String body, int expectedSize) {
        JsonNode root = JsonUtil.parse(body, JsonNode.class);
        JsonNode embeddings = root == null ? null : root.path(FIELD_OUTPUT).path(FIELD_EMBEDDINGS);
        if (embeddings == null || !embeddings.isArray() || embeddings.size() != expectedSize) {
            log.error("multimodal embedding response size mismatch, errorCode={}, expected={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, expectedSize);
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "multimodal embedding response does not match the request size");
        }
        float[][] ordered = new float[expectedSize][];
        for (JsonNode item : embeddings) {
            int index = item.path(FIELD_INDEX).asInt(-1);
            if (index < 0 || index >= expectedSize) {
                continue;
            }
            ordered[index] = vectorOf(item.path(FIELD_EMBEDDING));
        }
        List<float[]> vectors = new ArrayList<>(expectedSize);
        for (float[] vector : ordered) {
            if (vector == null) {
                log.error("multimodal embedding response missing an entry, errorCode={}, expected={}",
                        ErrorCode.UPSTREAM_MODEL_ERROR, expectedSize);
                throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                        "multimodal embedding response is missing an entry");
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private float[] vectorOf(JsonNode embedding) {
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        if (vector.length != config.getDimension()) {
            log.error("multimodal embedding dimension mismatch, errorCode={}, expected={}, actual={}",
                    ErrorCode.UPSTREAM_MODEL_ERROR, config.getDimension(), vector.length);
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.DIMENSION_MISMATCH,
                    "model returned dimension " + vector.length
                            + " while the index expects " + config.getDimension());
        }
        return vector;
    }

    private String dataUrl(byte[] content, String mediaType) {
        String type = mediaType == null || mediaType.isBlank() ? DEFAULT_MEDIA_TYPE : mediaType;
        return DATA_URL_PREFIX + type + DATA_URL_BASE64 + Base64.getEncoder().encodeToString(content);
    }
}
