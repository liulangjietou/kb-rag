package io.kbrag.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP JSON-RPC 引擎，同时承载旧版握手协议与 2026-07-28 逐请求元数据协议。
 *
 * <p>协议时代的判定只依赖当前请求：旧版继续通过 {@code initialize} 协商，现代版则由
 * {@code MCP-Protocol-Version}、镜像头和 {@code params._meta} 自描述。工具目录与执行器仍然
 * 是无状态的，因此两个时代可以安全共用同一端点和同一实例。
 *
 * <p>HTTP 规则集中在这里，不下沉到工具实现：现代版头校验失败返回 400，未知方法返回 404；
 * 旧版仍保持 JSON-RPC 错误承载于 HTTP 200。业务异常在两个时代都进入
 * {@code tools/call.result.isError}，不与协议错误混在一起。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public final class McpServerEngine {

    /** 现代无握手协议版本。 */
    public static final String PROTOCOL_VERSION = "2026-07-28";

    /** 当前兼容的旧版 Streamable HTTP 版本。 */
    public static final String LEGACY_PROTOCOL_VERSION = "2025-03-26";

    /** M20 已承诺兼容的早期版本。 */
    public static final String EARLIEST_PROTOCOL_VERSION = "2024-11-05";

    /** 所有支持版本，按优先级从新到旧返回。 */
    public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
            PROTOCOL_VERSION, LEGACY_PROTOCOL_VERSION, EARLIEST_PROTOCOL_VERSION);

    private static final Set<String> LEGACY_PROTOCOL_VERSIONS = Set.of(
            LEGACY_PROTOCOL_VERSION, EARLIEST_PROTOCOL_VERSION);
    private static final String JSONRPC_VERSION = "2.0";
    private static final String METHOD_DISCOVER = "server/discover";
    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_PING = "ping";
    private static final String METHOD_TOOLS_LIST = "tools/list";
    private static final String METHOD_TOOLS_CALL = "tools/call";
    private static final String NOTIFICATION_PREFIX = "notifications/";
    private static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    private static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    private static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    private static final String RESULT_TYPE_COMPLETE = "complete";
    private static final String CACHE_SCOPE_PUBLIC = "public";
    private static final long CATALOGUE_TTL_MS = 300_000L;
    private static final String BASE64_PREFIX = "=?base64?";
    private static final String BASE64_SUFFIX = "?=";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int HEADER_MISMATCH = -32020;
    private static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    private static final int STATUS_OK = 200;
    private static final int STATUS_ACCEPTED = 202;
    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_NOT_FOUND = 404;

    private final String serverName;
    private final String serverVersion;
    private final List<McpToolSpec> tools;

    public McpServerEngine(String serverName, String serverVersion, List<McpToolSpec> tools) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.tools = List.copyOf(tools);
    }

    /** 执行已在目录中确认存在的工具。 */
    public interface ToolExecutor {

        /**
         * @param toolName  工具名
         * @param arguments 参数对象，非空
         * @return 可序列化的工具结果
         */
        Object execute(String toolName, JsonNode arguments);
    }

    /**
     * @param status HTTP 状态
     * @param body   JSON 响应；通知成功时为空
     */
    public record McpReply(int status, String body) {
    }

    /** 保留 M20 的纯 JSON-RPC 调用入口，等价于无现代镜像头的旧版请求。 */
    public McpReply handle(String body, ToolExecutor executor) {
        return handle(body, McpHttpRequestHeaders.legacy(), executor);
    }

    /**
     * 处理单次 Streamable HTTP POST。
     *
     * @param body      原始 JSON-RPC body
     * @param headers   transport 镜像头
     * @param executor  绑定当前已认证身份的工具执行器
     * @return HTTP 与 JSON-RPC 双平面的完整响应
     */
    public McpReply handle(String body, McpHttpRequestHeaders headers, ToolExecutor executor) {
        JsonNode request;
        try {
            request = JsonUtil.mapper().readTree(body);
        } catch (Exception e) {
            return error(null, PARSE_ERROR, "request body is not valid JSON",
                    headers.hasModernMarker() ? STATUS_BAD_REQUEST : STATUS_OK, null);
        }
        boolean modern = isModernRequest(request, headers);
        if (request == null || !request.isObject()) {
            return error(null, INVALID_REQUEST, "request must be a single JSON-RPC object",
                    modern ? STATUS_BAD_REQUEST : STATUS_OK, null);
        }

        JsonNode idNode = request.get("id");
        JsonNode methodNode = request.path("method");
        String method = methodNode.isTextual() ? methodNode.asText() : null;
        if (!JSONRPC_VERSION.equals(request.path("jsonrpc").asText(null)) || method == null) {
            return error(valueOf(idNode), INVALID_REQUEST,
                    "jsonrpc must be \"2.0\" and method must be a string",
                    modern ? STATUS_BAD_REQUEST : STATUS_OK, null);
        }
        if (idNode == null || idNode.isNull()) {
            if (!method.startsWith(NOTIFICATION_PREFIX)) {
                return error(null, INVALID_REQUEST, "requests must carry an id, only notifications may omit it",
                        modern ? STATUS_BAD_REQUEST : STATUS_OK, null);
            }
            return new McpReply(STATUS_ACCEPTED, null);
        }

        Object id = valueOf(idNode);
        JsonNode params = request.path("params");
        if (modern) {
            McpReply validationFailure = validateModernRequest(idNode, id, method, params, headers);
            if (validationFailure != null) {
                return validationFailure;
            }
            return handleModern(id, method, params, executor);
        }
        return handleLegacy(id, method, params, executor);
    }

    private McpReply handleLegacy(Object id, String method, JsonNode params, ToolExecutor executor) {
        return switch (method) {
            case METHOD_INITIALIZE -> result(id, initializeResult(params));
            case METHOD_PING -> result(id, Map.of());
            case METHOD_TOOLS_LIST -> result(id, Map.of("tools", toolCatalogue(false)));
            case METHOD_TOOLS_CALL -> toolsCall(id, params, executor, false);
            default -> error(id, METHOD_NOT_FOUND, "method not supported: " + method);
        };
    }

    private McpReply handleModern(Object id, String method, JsonNode params, ToolExecutor executor) {
        return switch (method) {
            case METHOD_DISCOVER -> result(id, modernResult(discoverResult()));
            case METHOD_PING -> result(id, modernResult(Map.of()));
            case METHOD_TOOLS_LIST -> result(id, modernResult(Map.of(
                    "tools", toolCatalogue(true),
                    "ttlMs", CATALOGUE_TTL_MS,
                    "cacheScope", CACHE_SCOPE_PUBLIC)));
            case METHOD_TOOLS_CALL -> toolsCall(id, params, executor, true);
            default -> error(id, METHOD_NOT_FOUND, "method not supported: " + method,
                    STATUS_NOT_FOUND, null);
        };
    }

    private McpReply validateModernRequest(JsonNode idNode, Object id, String method, JsonNode params,
                                           McpHttpRequestHeaders headers) {
        if (!idNode.isNumber() && !idNode.isTextual()) {
            return error(id, INVALID_REQUEST, "id must be a string or number", STATUS_BAD_REQUEST, null);
        }
        if (!params.isObject()) {
            return error(id, INVALID_PARAMS, "params must be an object", STATUS_BAD_REQUEST, null);
        }
        JsonNode meta = params.path("_meta");
        JsonNode bodyVersionNode = meta.path(META_PROTOCOL_VERSION);
        String bodyVersion = bodyVersionNode.isTextual() ? bodyVersionNode.asText() : null;
        if (headers.protocolVersion() == null || bodyVersion == null
                || !isPlainHeaderValue(headers.protocolVersion())
                || !headers.protocolVersion().equals(bodyVersion)) {
            return headerMismatch(id, "MCP-Protocol-Version must match params._meta protocolVersion");
        }
        if (!SUPPORTED_PROTOCOL_VERSIONS.contains(bodyVersion) || !PROTOCOL_VERSION.equals(bodyVersion)) {
            return error(id, UNSUPPORTED_PROTOCOL_VERSION, "unsupported MCP protocol version",
                    STATUS_BAD_REQUEST, Map.of(
                            "supported", SUPPORTED_PROTOCOL_VERSIONS,
                            "requested", bodyVersion));
        }
        if (headers.method() == null || !isPlainHeaderValue(headers.method())
                || !headers.method().equals(method)) {
            return headerMismatch(id, "Mcp-Method must match request method");
        }
        if (!meta.path(META_CLIENT_CAPABILITIES).isObject()) {
            return error(id, INVALID_PARAMS,
                    "params._meta clientCapabilities must be an object", STATUS_BAD_REQUEST, null);
        }
        JsonNode clientInfo = meta.path(META_CLIENT_INFO);
        if (!clientInfo.isMissingNode() && (!clientInfo.isObject()
                || !clientInfo.path("name").isTextual()
                || !clientInfo.path("version").isTextual())) {
            return error(id, INVALID_PARAMS,
                    "params._meta clientInfo must contain string name and version", STATUS_BAD_REQUEST, null);
        }
        if (METHOD_TOOLS_CALL.equals(method)) {
            JsonNode bodyNameNode = params.path("name");
            String bodyName = bodyNameNode.isTextual() ? bodyNameNode.asText() : null;
            HeaderValue decodedName = decodeName(headers.name());
            if (bodyName == null || !decodedName.valid() || !bodyName.equals(decodedName.value())) {
                return headerMismatch(id, "Mcp-Name must match params.name");
            }
        }
        return null;
    }

    private boolean isModernRequest(JsonNode request, McpHttpRequestHeaders headers) {
        if (headers.hasModernMarker()) {
            return true;
        }
        if (request == null || !request.isObject()) {
            return false;
        }
        String method = request.path("method").asText(null);
        JsonNode bodyVersionNode = request.path("params").path("_meta").path(META_PROTOCOL_VERSION);
        String bodyVersion = bodyVersionNode.isTextual() ? bodyVersionNode.asText() : null;
        return METHOD_DISCOVER.equals(method) || bodyVersion != null;
    }

    private Map<String, Object> initializeResult(JsonNode params) {
        String requested = params.path("protocolVersion").asText(null);
        String negotiated = LEGACY_PROTOCOL_VERSIONS.contains(requested)
                ? requested : LEGACY_PROTOCOL_VERSION;
        return Map.of(
                "protocolVersion", negotiated,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", serverInfo());
    }

    private Map<String, Object> discoverResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supportedVersions", SUPPORTED_PROTOCOL_VERSIONS);
        result.put("capabilities", Map.of("tools", Map.of()));
        result.put("instructions", "Use tools/list to inspect the available tools, then call one tool with "
                + "arguments matching its inputSchema.");
        result.put("ttlMs", CATALOGUE_TTL_MS);
        result.put("cacheScope", CACHE_SCOPE_PUBLIC);
        return result;
    }

    private List<Map<String, Object>> toolCatalogue(boolean sorted) {
        List<McpToolSpec> source = sorted
                ? tools.stream().sorted(Comparator.comparing(McpToolSpec::name)).toList()
                : tools;
        List<Map<String, Object>> catalogue = new ArrayList<>(source.size());
        for (McpToolSpec tool : source) {
            catalogue.add(Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "inputSchema", tool.inputSchema()));
        }
        return catalogue;
    }

    private McpReply toolsCall(Object id, JsonNode params, ToolExecutor executor, boolean modern) {
        String toolName = params.path("name").asText(null);
        if (toolName == null || tools.stream().noneMatch(tool -> tool.name().equals(toolName))) {
            return error(id, INVALID_PARAMS, "unknown tool: " + toolName,
                    modern ? STATUS_BAD_REQUEST : STATUS_OK, null);
        }
        JsonNode arguments = params.path("arguments");
        if (arguments.isMissingNode() || arguments.isNull()) {
            arguments = JsonUtil.mapper().createObjectNode();
        } else if (!arguments.isObject()) {
            return error(id, INVALID_PARAMS, "tool arguments must be an object",
                    modern ? STATUS_BAD_REQUEST : STATUS_OK, null);
        }
        try {
            Object payload = executor.execute(toolName, arguments);
            return result(id, toolResult(payload, false, modern));
        } catch (BizException e) {
            log.info("mcp tool call rejected, tool={}, errorCode={}, reason={}",
                    toolName, e.getErrorCode(), e.getMessage());
            return result(id, toolError(e.getErrorCode().name(), e.getMessage(), modern));
        } catch (Exception e) {
            log.error("mcp tool call failed, errorCode={}, tool={}", ErrorCode.INTERNAL_ERROR, toolName, e);
            return result(id, toolError(ErrorCode.INTERNAL_ERROR.name(),
                    ErrorCode.INTERNAL_ERROR.getDefaultMessage(), modern));
        }
    }

    private Map<String, Object> toolResult(Object payload, boolean isError, boolean modern) {
        Map<String, Object> result = modern ? modernResult(Map.of()) : new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", JsonUtil.toJson(payload))));
        result.put("structuredContent", payload);
        result.put("isError", isError);
        return result;
    }

    private Map<String, Object> toolError(String code, String message, boolean modern) {
        Map<String, Object> result = modern ? modernResult(Map.of()) : new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", code + ": " + message)));
        result.put("isError", true);
        return result;
    }

    private Map<String, Object> modernResult(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", RESULT_TYPE_COMPLETE);
        result.put("_meta", Map.of(META_SERVER_INFO, serverInfo()));
        result.putAll(payload);
        return result;
    }

    private Map<String, Object> serverInfo() {
        return Map.of("name", serverName, "version", serverVersion);
    }

    private McpReply result(Object id, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        envelope.put("id", id);
        envelope.put("result", payload);
        return new McpReply(STATUS_OK, JsonUtil.toJson(envelope));
    }

    private McpReply error(Object id, int code, String message) {
        return error(id, code, message, STATUS_OK, null);
    }

    private McpReply headerMismatch(Object id, String message) {
        return error(id, HEADER_MISMATCH, message, STATUS_BAD_REQUEST, null);
    }

    private McpReply error(Object id, int code, String message, int status, Object data) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.put("data", data);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        envelope.put("id", id);
        envelope.put("error", error);
        return new McpReply(status, JsonUtil.toJson(envelope));
    }

    private HeaderValue decodeName(String headerValue) {
        if (headerValue == null) {
            return HeaderValue.invalid();
        }
        if (headerValue.startsWith(BASE64_PREFIX) && headerValue.endsWith(BASE64_SUFFIX)) {
            String encoded = headerValue.substring(BASE64_PREFIX.length(),
                    headerValue.length() - BASE64_SUFFIX.length());
            try {
                byte[] bytes = Base64.getDecoder().decode(encoded);
                String decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                return new HeaderValue(true, decoded);
            } catch (IllegalArgumentException | CharacterCodingException e) {
                return HeaderValue.invalid();
            }
        }
        return isPlainHeaderValue(headerValue)
                ? new HeaderValue(true, headerValue)
                : HeaderValue.invalid();
    }

    private boolean isPlainHeaderValue(String value) {
        if (value == null || value.isEmpty()
                || value.charAt(0) == ' ' || value.charAt(0) == '\t'
                || value.charAt(value.length() - 1) == ' '
                || value.charAt(value.length() - 1) == '\t') {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\t' && (current < 0x20 || current > 0x7E)) {
                return false;
            }
        }
        return true;
    }

    private Object valueOf(JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        return idNode.isNumber() ? idNode.numberValue() : idNode.asText();
    }

    private record HeaderValue(boolean valid, String value) {

        private static HeaderValue invalid() {
            return new HeaderValue(false, null);
        }
    }
}
