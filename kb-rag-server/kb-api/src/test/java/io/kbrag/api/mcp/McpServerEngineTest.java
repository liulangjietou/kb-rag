package io.kbrag.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline tests of the MCP JSON-RPC dispatcher, the M20 contract.
 *
 * @author owlzhangfq@gmail.com
 */
class McpServerEngineTest {

    private static final McpServerEngine.ToolExecutor NOOP_EXECUTOR =
            (toolName, arguments) -> Map.of("echo", toolName);

    private final McpServerEngine engine = new McpServerEngine("kb-rag-test", "0.20.0", List.of(
            new McpToolSpec("demo_tool", "a demo tool",
                    McpJsonSchemas.object(Map.of("query", McpJsonSchemas.string("query")), List.of("query")))));

    @Test
    void initializeNegotiatesProtocolAndAdvertisesToolsCapability() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(request("initialize", 1,
                "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}"), NOOP_EXECUTOR);
        assertThat(reply.status()).isEqualTo(200);
        JsonNode body = JsonUtil.mapper().readTree(reply.body());
        assertThat(body.path("id").asInt()).isEqualTo(1);
        assertThat(body.path("result").path("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(body.path("result").path("capabilities").has("tools")).isTrue();
        assertThat(body.path("result").path("serverInfo").path("name").asText()).isEqualTo("kb-rag-test");
    }

    @Test
    void initializeFallsBackToOwnProtocolOnUnknownRevision() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(request("initialize", 1,
                "{\"protocolVersion\":\"1999-01-01\"}"), NOOP_EXECUTOR);
        JsonNode body = JsonUtil.mapper().readTree(reply.body());
        assertThat(body.path("result").path("protocolVersion").asText())
                .isEqualTo(McpServerEngine.PROTOCOL_VERSION);
    }

    @Test
    void notificationAnswersAcceptedWithoutBody() {
        McpServerEngine.McpReply reply = engine.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", NOOP_EXECUTOR);
        assertThat(reply.status()).isEqualTo(202);
        assertThat(reply.body()).isNull();
    }

    @Test
    void toolsListReturnsTheCatalogue() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(request("tools/list", 2, null), NOOP_EXECUTOR);
        JsonNode tools = JsonUtil.mapper().readTree(reply.body()).path("result").path("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("demo_tool");
        assertThat(tools.get(0).path("inputSchema").path("type").asText()).isEqualTo("object");
    }

    @Test
    void toolsCallWrapsThePayloadAsTextAndStructuredContent() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(request("tools/call", 3,
                "{\"name\":\"demo_tool\",\"arguments\":{\"query\":\"hi\"}}"), NOOP_EXECUTOR);
        JsonNode result = JsonUtil.mapper().readTree(reply.body()).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("echo").asText()).isEqualTo("demo_tool");
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(result.path("content").get(0).path("text").asText()).contains("demo_tool");
    }

    @Test
    void toolsCallMapsBizExceptionOntoTheErrorResultPlane() throws Exception {
        McpServerEngine.ToolExecutor failing = (toolName, arguments) -> {
            throw BizException.invalidParam("query must not be blank");
        };
        McpServerEngine.McpReply reply = engine.handle(request("tools/call", 4,
                "{\"name\":\"demo_tool\",\"arguments\":{}}"), failing);
        JsonNode body = JsonUtil.mapper().readTree(reply.body());
        assertThat(body.has("error")).isFalse();
        JsonNode result = body.path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText())
                .isEqualTo("INVALID_PARAM: query must not be blank");
    }

    @Test
    void toolsCallOnAnUnknownToolIsAProtocolError() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(request("tools/call", 5,
                "{\"name\":\"missing_tool\",\"arguments\":{}}"), NOOP_EXECUTOR);
        JsonNode body = JsonUtil.mapper().readTree(reply.body());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void unknownMethodAnswersMethodNotFound() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(request("resources/list", 6, null), NOOP_EXECUTOR);
        JsonNode body = JsonUtil.mapper().readTree(reply.body());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void malformedJsonAnswersParseError() throws Exception {
        McpServerEngine.McpReply reply = engine.handle("{not json", NOOP_EXECUTOR);
        JsonNode body = JsonUtil.mapper().readTree(reply.body());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void batchArrayIsRejectedAsInvalidRequest() throws Exception {
        McpServerEngine.McpReply reply = engine.handle("[]", NOOP_EXECUTOR);
        JsonNode body = JsonUtil.mapper().readTree(reply.body());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void requestWithoutIdOutsideNotificationsIsRejected() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}", NOOP_EXECUTOR);
        JsonNode body = JsonUtil.mapper().readTree(reply.body());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void pingAnswersAnEmptyResult() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(request("ping", 7, null), NOOP_EXECUTOR);
        JsonNode body = JsonUtil.mapper().readTree(reply.body());
        assertThat(body.path("result").isObject()).isTrue();
        assertThat(body.path("result")).isEmpty();
    }

    private String request(String method, int id, String params) {
        StringBuilder body = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
                .append(",\"method\":\"").append(method).append("\"");
        if (params != null) {
            body.append(",\"params\":").append(params);
        }
        return body.append("}").toString();
    }
}
