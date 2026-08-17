package io.kbrag.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 双协议 JSON-RPC 引擎离线测试。
 *
 * @author owlzhangfq@gmail.com
 */
class McpServerEngineTest {

    private static final McpServerEngine.ToolExecutor NOOP_EXECUTOR =
            (toolName, arguments) -> Map.of("echo", toolName);

    private final McpServerEngine engine = new McpServerEngine("kb-rag-test", "0.24.0", List.of(
            new McpToolSpec("z_tool", "last tool", McpJsonSchemas.object(Map.of(), List.of())),
            new McpToolSpec("demo_tool", "a demo tool",
                    McpJsonSchemas.object(Map.of("query", McpJsonSchemas.string("query")), List.of("query")))));

    @Test
    void legacyInitializeNegotiatesProtocolAndAdvertisesToolsCapability() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(legacyRequest("initialize", 1,
                "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}"), NOOP_EXECUTOR);

        assertThat(reply.status()).isEqualTo(200);
        JsonNode body = bodyOf(reply);
        assertThat(body.path("id").asInt()).isEqualTo(1);
        assertThat(body.path("result").path("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(body.path("result").path("capabilities").has("tools")).isTrue();
        assertThat(body.path("result").path("serverInfo").path("name").asText()).isEqualTo("kb-rag-test");
        assertThat(body.path("result").has("resultType")).isFalse();
    }

    @Test
    void legacyInitializeFallsBackToLatestLegacyProtocolOnUnknownRevision() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(legacyRequest("initialize", 1,
                "{\"protocolVersion\":\"1999-01-01\"}"), NOOP_EXECUTOR);

        assertThat(bodyOf(reply).path("result").path("protocolVersion").asText())
                .isEqualTo(McpServerEngine.LEGACY_PROTOCOL_VERSION);
    }

    @Test
    void legacyNotificationAnswersAcceptedWithoutBody() {
        McpServerEngine.McpReply reply = engine.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", NOOP_EXECUTOR);

        assertThat(reply.status()).isEqualTo(202);
        assertThat(reply.body()).isNull();
    }

    @Test
    void legacyToolsListPreservesTheCatalogue() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(legacyRequest("tools/list", 2, null), NOOP_EXECUTOR);

        JsonNode tools = bodyOf(reply).path("result").path("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("z_tool");
        assertThat(tools.get(1).path("inputSchema").path("type").asText()).isEqualTo("object");
    }

    @Test
    void legacyToolsCallWrapsThePayloadAsTextAndStructuredContent() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(legacyRequest("tools/call", 3,
                "{\"name\":\"demo_tool\",\"arguments\":{\"query\":\"hi\"}}"), NOOP_EXECUTOR);

        JsonNode result = bodyOf(reply).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("echo").asText()).isEqualTo("demo_tool");
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(result.path("content").get(0).path("text").asText()).contains("demo_tool");
    }

    @Test
    void toolBusinessFailureRemainsOnTheResultPlane() throws Exception {
        McpServerEngine.ToolExecutor failing = (toolName, arguments) -> {
            throw BizException.invalidParam("query must not be blank");
        };
        McpServerEngine.McpReply reply = engine.handle(legacyRequest("tools/call", 4,
                "{\"name\":\"demo_tool\",\"arguments\":{}}"), failing);

        JsonNode body = bodyOf(reply);
        assertThat(body.has("error")).isFalse();
        assertThat(body.path("result").path("isError").asBoolean()).isTrue();
        assertThat(body.path("result").path("content").get(0).path("text").asText())
                .isEqualTo("INVALID_PARAM: query must not be blank");
    }

    @Test
    void legacyUnknownToolIsAProtocolErrorOverHttp200() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(legacyRequest("tools/call", 5,
                "{\"name\":\"missing_tool\",\"arguments\":{}}"), NOOP_EXECUTOR);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(bodyOf(reply).path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void legacyUnknownMethodRemainsMethodNotFoundOverHttp200() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(legacyRequest("resources/list", 6, null), NOOP_EXECUTOR);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(bodyOf(reply).path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void legacyMalformedJsonAndBatchRemainJsonRpcErrorsOverHttp200() throws Exception {
        McpServerEngine.McpReply malformed = engine.handle("{not json", NOOP_EXECUTOR);
        McpServerEngine.McpReply batch = engine.handle("[]", NOOP_EXECUTOR);

        assertThat(malformed.status()).isEqualTo(200);
        assertThat(bodyOf(malformed).path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(batch.status()).isEqualTo(200);
        assertThat(bodyOf(batch).path("error").path("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void requestWithoutIdOutsideNotificationsIsRejected() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}", NOOP_EXECUTOR);

        assertThat(bodyOf(reply).path("error").path("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void toolArgumentsMustBeAnObject() throws Exception {
        McpServerEngine.McpReply reply = engine.handle(legacyRequest("tools/call", 8,
                "{\"name\":\"demo_tool\",\"arguments\":\"bad\"}"), NOOP_EXECUTOR);

        assertThat(bodyOf(reply).path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void modernDiscoverAdvertisesVersionsCapabilitiesAndCacheHints() throws Exception {
        McpServerEngine.McpReply reply = modernCall("server/discover", 10, "{}", null);

        assertThat(reply.status()).isEqualTo(200);
        JsonNode result = bodyOf(reply).path("result");
        assertThat(result.path("resultType").asText()).isEqualTo("complete");
        assertThat(result.path("supportedVersions")).containsExactly(
                JsonUtil.mapper().getNodeFactory().textNode("2026-07-28"),
                JsonUtil.mapper().getNodeFactory().textNode("2025-03-26"),
                JsonUtil.mapper().getNodeFactory().textNode("2024-11-05"));
        assertThat(result.path("capabilities").has("tools")).isTrue();
        assertThat(result.path("ttlMs").asLong()).isEqualTo(300_000L);
        assertThat(result.path("cacheScope").asText()).isEqualTo("public");
        assertThat(result.path("_meta").path("io.modelcontextprotocol/serverInfo")
                .path("version").asText()).isEqualTo("0.24.0");
    }

    @Test
    void modernToolsListIsSortedAndCacheable() throws Exception {
        McpServerEngine.McpReply reply = modernCall("tools/list", 11, "{}", null);

        JsonNode result = bodyOf(reply).path("result");
        assertThat(result.path("tools").get(0).path("name").asText()).isEqualTo("demo_tool");
        assertThat(result.path("tools").get(1).path("name").asText()).isEqualTo("z_tool");
        assertThat(result.path("ttlMs").asLong()).isEqualTo(300_000L);
        assertThat(result.path("cacheScope").asText()).isEqualTo("public");
    }

    @Test
    void modernToolCallIncludesRequiredResultMetadata() throws Exception {
        McpServerEngine.McpReply reply = modernCall("tools/call", 12,
                "{\"name\":\"demo_tool\",\"arguments\":{\"query\":\"hi\"}}", "demo_tool");

        JsonNode result = bodyOf(reply).path("result");
        assertThat(result.path("resultType").asText()).isEqualTo("complete");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("echo").asText()).isEqualTo("demo_tool");
    }

    @Test
    void modernNameHeaderAcceptsExactBase64SentinelEncoding() throws Exception {
        String encoded = "=?base64?" + Base64.getEncoder()
                .encodeToString("demo_tool".getBytes(StandardCharsets.UTF_8)) + "?=";

        McpServerEngine.McpReply reply = modernCall("tools/call", 13,
                "{\"name\":\"demo_tool\",\"arguments\":{}}", encoded);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(bodyOf(reply).path("result").path("isError").asBoolean()).isFalse();
    }

    @Test
    void modernMissingProtocolHeaderIsAHeaderMismatch() throws Exception {
        String body = modernRequest("tools/list", 14, "{}");
        McpHttpRequestHeaders headers = new McpHttpRequestHeaders(null, "tools/list", null);

        McpServerEngine.McpReply reply = engine.handle(body, headers, NOOP_EXECUTOR);

        assertModernError(reply, 400, -32020);
    }

    @Test
    void modernMethodOrNameMismatchIsRejected() throws Exception {
        String listBody = modernRequest("tools/list", 15, "{}");
        McpServerEngine.McpReply methodMismatch = engine.handle(listBody,
                new McpHttpRequestHeaders(McpServerEngine.PROTOCOL_VERSION, "ping", null), NOOP_EXECUTOR);
        String callBody = modernRequest("tools/call", 16,
                "{\"name\":\"demo_tool\",\"arguments\":{}}");
        McpServerEngine.McpReply nameMismatch = engine.handle(callBody,
                new McpHttpRequestHeaders(McpServerEngine.PROTOCOL_VERSION, "tools/call", "other"),
                NOOP_EXECUTOR);

        assertModernError(methodMismatch, 400, -32020);
        assertModernError(nameMismatch, 400, -32020);
    }

    @Test
    void modernUnsupportedVersionReturnsNegotiationData() throws Exception {
        String version = "2027-01-01";
        String body = modernRequest("tools/list", 17, "{}")
                .replace(McpServerEngine.PROTOCOL_VERSION, version);
        McpServerEngine.McpReply reply = engine.handle(body,
                new McpHttpRequestHeaders(version, "tools/list", null), NOOP_EXECUTOR);

        assertModernError(reply, 400, -32022);
        JsonNode data = bodyOf(reply).path("error").path("data");
        assertThat(data.path("requested").asText()).isEqualTo(version);
        assertThat(data.path("supported").toString()).contains(McpServerEngine.PROTOCOL_VERSION);
    }

    @Test
    void modernRequestRequiresClientCapabilities() throws Exception {
        String body = modernRequest("ping", 18, "{}")
                .replace("\"io.modelcontextprotocol/clientCapabilities\":{},", "");
        McpServerEngine.McpReply reply = engine.handle(body, modernHeaders("ping", null), NOOP_EXECUTOR);

        assertModernError(reply, 400, -32602);
    }

    @Test
    void modernUnknownMethodUsesHttp404() throws Exception {
        McpServerEngine.McpReply reply = modernCall("resources/list", 19, "{}", null);

        assertModernError(reply, 404, -32601);
    }

    @Test
    void modernInitializeIsNotPartOfTheProtocol() throws Exception {
        McpServerEngine.McpReply reply = modernCall("initialize", 20, "{}", null);

        assertModernError(reply, 404, -32601);
    }

    @Test
    void modernMalformedJsonUsesHttp400WhenHeadersIdentifyTheEra() throws Exception {
        McpServerEngine.McpReply reply = engine.handle("{not json", modernHeaders("tools/list", null),
                NOOP_EXECUTOR);

        assertModernError(reply, 400, -32700);
    }

    private McpServerEngine.McpReply modernCall(String method, int id, String params, String name) {
        return engine.handle(modernRequest(method, id, params), modernHeaders(method, name), NOOP_EXECUTOR);
    }

    private McpHttpRequestHeaders modernHeaders(String method, String name) {
        return new McpHttpRequestHeaders(McpServerEngine.PROTOCOL_VERSION, method, name);
    }

    private String modernRequest(String method, int id, String params) {
        String content = params.substring(1, params.length() - 1);
        String prefix = "\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"" + McpServerEngine.PROTOCOL_VERSION + "\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{},"
                + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0\"}}";
        String merged = "{" + prefix + (content.isBlank() ? "" : "," + content) + "}";
        return legacyRequest(method, id, merged);
    }

    private String legacyRequest(String method, int id, String params) {
        StringBuilder body = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
                .append(",\"method\":\"").append(method).append("\"");
        if (params != null) {
            body.append(",\"params\":").append(params);
        }
        return body.append("}").toString();
    }

    private JsonNode bodyOf(McpServerEngine.McpReply reply) throws Exception {
        return JsonUtil.mapper().readTree(reply.body());
    }

    private void assertModernError(McpServerEngine.McpReply reply, int status, int code) throws Exception {
        assertThat(reply.status()).isEqualTo(status);
        assertThat(bodyOf(reply).path("error").path("code").asInt()).isEqualTo(code);
    }
}
