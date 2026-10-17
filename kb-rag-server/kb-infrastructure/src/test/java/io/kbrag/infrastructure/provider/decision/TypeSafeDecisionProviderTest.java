package io.kbrag.infrastructure.provider.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.ModelCallMeter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用本地 HTTP 服务驱动真实传输，覆盖 Jev 线协议的请求形状、答案回填与失败分类。
 *
 * @author owlzhangfq@gmail.com
 */
class TypeSafeDecisionProviderTest {

    private static final String MODEL = "jev-1.13.0";

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldSendTypedNoulQuestionsAndReturnProbabilitiesInRequestOrder() {
        responseBody = "{\"model\":\"jev-1.13.0\",\"answers\":{\"b\":{\"type\":\"noul\",\"noul\":0.2},"
                + "\"a\":{\"type\":\"noul\",\"noul\":0.9}},\"usage\":{\"input_tokens\":120,\"output_tokens\":8}}";
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("a", "Is it urgent?");
        questions.put("b", Map.of("question", "Is it billing?"));

        Map<String, Double> nouls = newProvider().evaluateNouls(Map.of("message", "付款失败三天了"), questions);

        assertThat(nouls).containsExactly(Map.entry("a", 0.9d), Map.entry("b", 0.2d));
        JsonNode sent = JsonUtil.parse(requestBody.get(), JsonNode.class);
        assertThat(sent.path("model").asText()).isEqualTo(MODEL);
        assertThat(sent.path("state").path("message").asText()).isEqualTo("付款失败三天了");
        assertThat(sent.path("questions").path("a").path("type").asText()).isEqualTo("noul");
        assertThat(sent.path("questions").path("b").path("instructions").path("question").asText())
                .isEqualTo("Is it billing?");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
    }

    @Test
    void shouldFailWholeCallWhenAnyAnswerIsMissing() {
        // 缺一个答案就整体失败：补 0 会让路由静默漏库、让重排静默沉底
        responseBody = "{\"model\":\"jev-1.13.0\",\"answers\":{\"a\":{\"type\":\"noul\",\"noul\":0.9}}}";

        assertThatThrownBy(() -> newProvider().evaluateNouls("state", Map.of("a", "q1", "b", "q2")))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).getErrorType())
                        .isEqualTo(ProviderErrorType.UNKNOWN));
    }

    @Test
    void shouldClassifyRejectedKeyAsAuthFailure() {
        status = 401;
        responseBody = "{\"detail\":\"invalid api key\"}";

        assertThatThrownBy(() -> newProvider().evaluateNouls("state", Map.of("a", "q")))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).getErrorType())
                        .isEqualTo(ProviderErrorType.AUTH_FAILED));
    }

    @Test
    void shouldClassifyRateLimitAsQuotaExceeded() {
        status = 429;
        responseBody = "{\"detail\":\"rate limited\"}";

        assertThatThrownBy(() -> newProvider().evaluateNouls("state", Map.of("a", "q")))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).getErrorType())
                        .isEqualTo(ProviderErrorType.QUOTA_EXCEEDED));
    }

    private TypeSafeDecisionProvider newProvider() {
        KbProperties.Decision config = new KbProperties.Decision();
        config.setApiKey("test-key");
        config.setModel(MODEL);
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new TypeSafeDecisionProvider(config, ModelCallMeter.NOOP);
    }
}
