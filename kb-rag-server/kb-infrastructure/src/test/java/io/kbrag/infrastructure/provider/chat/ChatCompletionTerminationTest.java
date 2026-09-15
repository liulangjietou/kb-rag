package io.kbrag.infrastructure.provider.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.ModelCallTicket;
import io.kbrag.domain.model.ModelTokenUsage;
import io.kbrag.domain.port.ModelCallMeter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实 HTTP 的成功状态和 DONE 帧不能掩盖模型长度截断或其他非正常结束。 */
class ChatCompletionTerminationTest {
    private static final ModelTokenUsage USAGE = new ModelTokenUsage(20L, 8L, 28L, true);
    private static final ModelCallTicket TICKET = new ModelCallTicket("usage_test", 100L, true);
    private static final String USAGE_JSON = "{\"prompt_tokens\":20,\"completion_tokens\":8,\"total_tokens\":28}";

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "tool_calls", "unknown_private_reason"})
    void shouldRejectAbnormalTerminationAndKeepUsageThatArrivesAfterTheFinishReason(String reason) throws Exception {
        HttpServer server = server(reason);
        ModelCallMeter meter = mock(ModelCallMeter.class);
        when(meter.reserve(any())).thenReturn(TICKET);
        try {
            DashScopeChatProvider provider = provider(server, meter);
            ProviderException complete = assertThrows(ProviderException.class,
                    () -> provider.complete("系统", List.of(ChatMessage.user("问题"))));
            assertEquals(reason.equals("length") ? "OUTPUT_TRUNCATED" : "UNKNOWN", complete.getErrorType().name());
            verify(meter).succeed(TICKET, USAGE);
            verify(meter, never()).fail(any(), any());
            clearInvocations(meter);

            List<String> partial = new ArrayList<>();
            ProviderException stream = assertThrows(ProviderException.class,
                    () -> provider.stream("系统", List.of(ChatMessage.user("问题")), partial::add));
            assertEquals(complete.getErrorType(), stream.getErrorType());
            assertEquals(List.of("未完成的正文"), partial);
            verify(meter).incomplete(TICKET, USAGE, stream);
            verify(meter, never()).succeed(any(), any());
            verify(meter, never()).fail(any(), any());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "omitted"})
    void shouldKeepNormalStopAndExistingGatewaysWithoutAnExplicitReasonCompatible(String reason) throws Exception {
        HttpServer server = server(reason);
        try {
            DashScopeChatProvider provider = provider(server, ModelCallMeter.NOOP);
            assertEquals("未完成的正文", provider.complete(null, List.of(ChatMessage.user("问题"))));
            List<String> answer = new ArrayList<>();
            provider.stream(null, List.of(ChatMessage.user("问题")), answer::add);
            assertEquals(List.of("未完成的正文"), answer);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(String reason) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String finish = reason.equals("omitted") ? "" : ",\"finish_reason\":" + JsonUtil.toJson(reason);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = JsonUtil.parse(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8), JsonNode.class);
            boolean stream = request.path("stream").asBoolean();
            String response = stream
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"未完成的正文\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{}" + finish + "}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":" + USAGE_JSON + "}\n\n"
                        + "data: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"content\":\"未完成的正文\"}" + finish
                        + "}],\"usage\":" + USAGE_JSON + "}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private DashScopeChatProvider provider(HttpServer server, ModelCallMeter meter) {
        var config = new KbProperties.Chat();
        config.setApiKey("fixture-key");
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new DashScopeChatProvider(config, meter);
    }
}
