package io.kbrag.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.port.ModelCallMeter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 评分 JSON 的输出预算独立于短查询改写，并通过实际 HTTP 请求验证配置装配。 */
class EvalJudgeBudgetTest {

    private static final int REWRITE_BUDGET = 128;
    private static final String VERDICT = "{\"correctness\":5,\"reason\":\"证据完整\"}";

    @ParameterizedTest
    @ValueSource(ints = {0, 3072})
    void shouldUseAnIndependentDefaultOrConfiguredJudgeBudget(int configuredBudget) throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = JsonUtil.parse(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8), JsonNode.class);
            requests.add(request);
            // 复现短改写预算使多字段评分 JSON 被截断；普通改写仍可正常结束。
            boolean truncated = request.path("model").asText().equals("judge-fixture")
                    && request.path("max_tokens").asInt() <= REWRITE_BUDGET;
            String response = JsonUtil.toJson(Map.of("choices", List.of(Map.of(
                    "finish_reason", truncated ? "length" : "stop",
                    "message", Map.of("content", truncated ? "{\"correctness\":" : VERDICT)))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            KbProperties properties = new KbProperties();
            if (configuredBudget > 0) {
                new Binder(new MapConfigurationPropertySource(Map.of(
                        "kb.eval.judge-max-tokens", configuredBudget)))
                        .bind("kb", Bindable.ofInstance(properties));
            }
            properties.getChat().setApiKey("fixture-key");
            properties.getChat().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getChat().setModel("rewrite-fixture");
            properties.getChat().setMaxTokens(REWRITE_BUDGET);
            properties.getChat().setTemperature(0.7d);
            properties.getEval().setJudgeModel("judge-fixture");
            ModelProviderConfig config = new ModelProviderConfig();

            assertEquals(VERDICT, config.judgeChatProvider(properties, ModelCallMeter.NOOP)
                    .complete("评分", List.of(ChatMessage.user("答案与证据"))));
            assertEquals(VERDICT, config.chatProvider(properties, ModelCallMeter.NOOP)
                    .complete("改写", List.of(ChatMessage.user("问题"))));

            assertEquals(configuredBudget == 0 ? 2048 : configuredBudget,
                    requests.get(0).path("max_tokens").asInt());
            assertEquals(0.0d, requests.get(0).path("temperature").asDouble());
            assertEquals(REWRITE_BUDGET, requests.get(1).path("max_tokens").asInt());
            assertEquals(0.7d, requests.get(1).path("temperature").asDouble());
            assertEquals(REWRITE_BUDGET, properties.getChat().getMaxTokens());
        } finally {
            server.stop(0);
        }
    }
}
