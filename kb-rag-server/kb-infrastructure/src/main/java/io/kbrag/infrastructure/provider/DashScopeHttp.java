package io.kbrag.infrastructure.provider;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Plain HTTP transport shared by the DashScope backed providers.
 *
 * <p>No vendor SDK is involved anywhere in this package. The providers speak the documented wire
 * format directly, which is what lets the same implementation serve a different vendor by changing a
 * base URL, and what keeps the dependency tree free of a client library per capability.
 *
 * <p>Failure classification lives here rather than in each provider because the mapping from HTTP
 * status to actionable cause is a property of the vendor, not of the capability: a 401 means the same
 * thing for embedding, rerank and chat, and the console shows the same remediation for all three.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public final class DashScopeHttp {

    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_PAYMENT_REQUIRED = 402;

    private DashScopeHttp() {
    }

    /**
     * Builds a client with the credential and the timeouts already applied.
     *
     * @param baseUrl   base URL, may be blank when every call passes an absolute URL
     * @param apiKey    bearer credential
     * @param timeoutMs connect and read timeout in milliseconds
     * @return configured client
     */
    public static RestClient client(String baseUrl, String apiKey, int timeoutMs) {
        return client(baseUrl, apiKey, timeoutMs, timeoutMs);
    }

    /**
     * Builds a client with separate connect and read ceilings.
     *
     * <p>Needed by the chat provider: connecting must fail fast (a short connect timeout keeps network
     * problems visible), while reading a full generated answer legitimately takes far longer than any
     * control-plane call is allowed to.
     *
     * @param baseUrl       base URL, may be blank when every call passes an absolute URL
     * @param apiKey        bearer credential
     * @param connectMs     connect timeout in milliseconds
     * @param readTimeoutMs read timeout in milliseconds
     * @return configured client
     */
    public static RestClient client(String baseUrl, String apiKey, int connectMs, int readTimeoutMs) {
        Duration timeout = Duration.ofMillis(connectMs);
        // JdkClientHttpRequestFactory over HttpURLConnection on purpose: HttpURLConnection cannot
        // replay a streamed request body, so a 401 carrying WWW-Authenticate surfaces as an I/O
        // failure ("cannot retry due to server authentication") instead of a status code. That made
        // every credential problem land in the NETWORK_UNREACHABLE bucket and sent operators
        // debugging connectivity while the real cause was the API key. java.net.http returns the
        // status normally, so classify() can do its job.
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /**
     * Issues a JSON POST and returns the raw response body.
     *
     * @param restClient   configured client
     * @param uri          absolute URL or path relative to the client base URL
     * @param payload      request body
     * @param providerName provider name carried by the classified failure
     * @param stage        capability name used in the log line, for example {@code rerank}
     * @return raw response body
     */
    public static String post(RestClient restClient, String uri, Map<String, Object> payload,
                              String providerName, String stage) {
        try {
            return restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JsonUtil.toJson(payload))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        String responseBody = new String(response.getBody().readAllBytes());
                        if (status.isError()) {
                            throw classify(providerName, stage, status.value(), responseBody);
                        }
                        return responseBody;
                    });
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} call failed, errorCode={}, type={}",
                    stage, ErrorCode.UPSTREAM_MODEL_ERROR, ProviderErrorType.NETWORK_UNREACHABLE, e);
            throw new ProviderException(providerName, ProviderErrorType.NETWORK_UNREACHABLE,
                    stage + " provider unreachable", e);
        }
    }

    /**
     * Maps an HTTP failure onto the classified provider error the console displays.
     *
     * @param providerName provider name
     * @param stage        capability name used in the log line
     * @param status       HTTP status code
     * @param body         raw response body, only inspected for keywords
     * @return classified exception
     */
    public static ProviderException classify(String providerName, String stage, int status, String body) {
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
        log.error("{} provider rejected request, errorCode={}, type={}, httpStatus={}",
                stage, ErrorCode.UPSTREAM_MODEL_ERROR, type, status);
        return new ProviderException(providerName, type, stage + " provider returned status " + status);
    }
}
