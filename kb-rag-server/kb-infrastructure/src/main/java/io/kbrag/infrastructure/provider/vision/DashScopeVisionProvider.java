package io.kbrag.infrastructure.provider.vision;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.model.ModelCallSpec;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.domain.port.ModelCallMeter;
import io.kbrag.infrastructure.provider.DashScopeHttp;
import io.kbrag.infrastructure.provider.ModelUsageSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope vision provider driven by plain HTTP.
 *
 * <p>Transport choice: the OpenAI compatible {@code {baseUrl}/chat/completions} endpoint with a
 * multimodal content array, so the same implementation serves any gateway that speaks that shape. No
 * vendor SDK is involved.
 *
 * <p><b>Why a data URL and not a pre signed link.</b> The bucket is private and the model provider is
 * outside the deployment, so handing it a URL would mean either opening the bucket or issuing an
 * internet reachable pre signed link for every image. Inlining the bytes keeps the storage private and
 * removes the dependency on the provider being able to reach this network at all.
 *
 * <p>The prompt asks for both a description and a verbatim transcription, which is what makes this
 * provider cover the scanned page case as well: a rendered page is just an image whose text matters more
 * than its appearance.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class DashScopeVisionProvider implements VisionProvider {

    /** Provider name reported by the model status endpoint. */
    public static final String PROVIDER_NAME = "dashscope";

    /**
     * Fixed instruction of the image stage.
     *
     * <p>Bump {@code VISION_PROMPT_VERSION} in the fingerprint factory whenever this text changes:
     * a different prompt yields a different proxy, which makes every existing build stale.
     */
    private static final String PROMPT =
            "Describe the content of this image and transcribe every piece of text it contains verbatim. "
                    + "Answer in the language of the image. Do not add commentary or speculation.";

    private static final String STAGE = "vision";
    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String FIELD_MODEL = "model";
    private static final String FIELD_MESSAGES = "messages";
    private static final String FIELD_ROLE = "role";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_IMAGE_URL = "image_url";
    private static final String FIELD_URL = "url";
    private static final String FIELD_TEMPERATURE = "temperature";
    private static final String FIELD_MAX_TOKENS = "max_tokens";
    private static final String FIELD_CHOICES = "choices";
    private static final String FIELD_MESSAGE = "message";
    private static final String ROLE_USER = "user";
    private static final String TYPE_TEXT = "text";
    private static final String TYPE_IMAGE_URL = "image_url";
    private static final String DATA_URL_PREFIX = "data:";
    private static final String DATA_URL_BASE64 = ";base64,";
    private static final String DEFAULT_MEDIA_TYPE = "image/png";

    /** One pixel PNG used by the health probe, so the check costs the smallest possible call. */
    private static final String HEALTH_PROBE_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";

    private final KbProperties.Vision config;
    private final RestClient restClient;
    private final ModelCallMeter modelCallMeter;

    public DashScopeVisionProvider(KbProperties properties) {
        this(properties, ModelCallMeter.NOOP);
    }

    /** Builds the production adapter with durable quota and usage metering. */
    public DashScopeVisionProvider(KbProperties properties, ModelCallMeter modelCallMeter) {
        this.config = properties.getVision();
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
    public String describeImage(byte[] content, String mediaType) {
        if (content == null || content.length == 0) {
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "vision requires a non empty image");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_MODEL, config.getModel());
        payload.put(FIELD_MESSAGES, List.of(userMessage(dataUrl(content, mediaType))));
        payload.put(FIELD_TEMPERATURE, config.getTemperature());
        payload.put(FIELD_MAX_TOKENS, config.getMaxTokens());

        return ModelUsageSupport.execute(modelCallMeter,
                new ModelCallSpec(ModelUsageSupport.billingProvider(config.getProvider(), PROVIDER_NAME),
                        ModelCallSpec.VISION, config.getModel(),
                        ModelUsageSupport.visionUpperBound(PROMPT, content, config.getMaxTokens())),
                () -> DashScopeHttp.post(restClient, COMPLETIONS_PATH, payload, PROVIDER_NAME, STAGE),
                this::parseContent);
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            describeImage(Base64.getDecoder().decode(HEALTH_PROBE_PNG), DEFAULT_MEDIA_TYPE);
            return HealthStatus.up(config.getModel());
        } catch (ProviderException e) {
            log.error("vision health check failed, errorCode={}, type={}",
                    e.getErrorCode(), e.getErrorType(), e);
            return HealthStatus.down(e.getErrorType().name());
        }
    }

    /**
     * Builds the single multimodal user message.
     *
     * @param dataUrl inlined image
     * @return message payload
     */
    private Map<String, Object> userMessage(String dataUrl) {
        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put(FIELD_TYPE, TYPE_IMAGE_URL);
        imagePart.put(FIELD_IMAGE_URL, Map.of(FIELD_URL, dataUrl));

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put(FIELD_TYPE, TYPE_TEXT);
        textPart.put(FIELD_TEXT, PROMPT);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put(FIELD_ROLE, ROLE_USER);
        // Image first, instruction second: the vendor documents that order for its vision models.
        message.put(FIELD_CONTENT, List.of(imagePart, textPart));
        return message;
    }

    private String dataUrl(byte[] content, String mediaType) {
        String type = mediaType == null || mediaType.isBlank() ? DEFAULT_MEDIA_TYPE : mediaType;
        return DATA_URL_PREFIX + type + DATA_URL_BASE64 + Base64.getEncoder().encodeToString(content);
    }

    private String parseContent(String body) {
        JsonNode root = JsonUtil.parse(body, JsonNode.class);
        JsonNode choices = root == null ? null : root.path(FIELD_CHOICES);
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            log.error("vision response carries no choice, errorCode={}", ErrorCode.UPSTREAM_MODEL_ERROR);
            throw new ProviderException(PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                    "vision response carries no choice");
        }
        return choices.get(0).path(FIELD_MESSAGE).path(FIELD_CONTENT).asText("");
    }
}
