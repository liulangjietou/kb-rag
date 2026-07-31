package io.kbrag.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The MCP server engine shared by both MCP endpoints: one JSON-RPC 2.0 dispatcher over the
 * Streamable HTTP transport, the M20 contract.
 *
 * <p><b>Deliberately hand rolled, no SDK.</b> The surface an agent actually needs to call our two
 * services is five methods ({@code initialize}, {@code notifications/*}, {@code ping},
 * {@code tools/list}, {@code tools/call}); a framework dependency would bring a session store,
 * SSE plumbing and its own threading model for a request/response exchange the existing stack
 * already handles. Every reply is a single JSON body, which the Streamable HTTP transport
 * explicitly permits, so any MCP client speaking that transport can connect.
 *
 * <p><b>Stateless on purpose.</b> No session id is issued and none is required back: the caller's
 * identity comes from the {@code Authorization} header on every POST, verified by the same filter
 * chain that guards the REST twin. That is what makes one engine instance per controller safe
 * under concurrency - the engine holds only the immutable tool catalogue.
 *
 * <p><b>Two failure planes, kept apart as the spec draws them.</b> A protocol violation (unknown
 * method, malformed body, unknown tool) is a JSON-RPC error; a tool that ran and failed
 * (bad argument, missing resource, upstream model down) is a successful {@code tools/call}
 * response with {@code isError=true}, so the calling model sees the business message and can
 * self-correct instead of the client tearing the exchange down.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public final class McpServerEngine {

    /** Protocol revision this engine speaks; echoed when the client requests an unknown one. */
    public static final String PROTOCOL_VERSION = "2025-03-26";

    /** Earlier revision still accepted, negotiated down when a client asks for it. */
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of("2024-11-05", PROTOCOL_VERSION);

    private static final String JSONRPC_VERSION = "2.0";
    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_PING = "ping";
    private static final String METHOD_TOOLS_LIST = "tools/list";
    private static final String METHOD_TOOLS_CALL = "tools/call";
    private static final String NOTIFICATION_PREFIX = "notifications/";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    /** HTTP status of a handled request carrying a response body. */
    private static final int STATUS_OK = 200;

    /** HTTP status of an accepted notification, which by contract has no body. */
    private static final int STATUS_ACCEPTED = 202;

    private final String serverName;
    private final String serverVersion;
    private final List<McpToolSpec> tools;

    public McpServerEngine(String serverName, String serverVersion, List<McpToolSpec> tools) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.tools = List.copyOf(tools);
    }

    /**
     * Executes one tool named by a {@code tools/call} request.
     *
     * <p>Business failures travel as {@link BizException}: the engine maps them onto the
     * {@code isError} result plane instead of a JSON-RPC error, see the class comment.
     */
    public interface ToolExecutor {

        /**
         * Runs the named tool.
         *
         * @param toolName  tool the request named, guaranteed to be in the catalogue
         * @param arguments arguments object of the call, never {@code null}
         * @return payload serialised into the tool result
         */
        Object execute(String toolName, JsonNode arguments);
    }

    /**
     * One handled MCP exchange, ready for the HTTP layer.
     *
     * @param status HTTP status to answer with
     * @param body   JSON body, {@code null} for an accepted notification
     */
    public record McpReply(int status, String body) {
    }

    /**
     * Handles one POST body of the Streamable HTTP transport.
     *
     * @param body     raw request body
     * @param executor tool executor bound to the authenticated caller
     * @return reply for the HTTP layer
     */
    public McpReply handle(String body, ToolExecutor executor) {
        JsonNode request;
        try {
            request = JsonUtil.mapper().readTree(body);
        } catch (Exception e) {
            return error(null, PARSE_ERROR, "request body is not valid JSON");
        }
        if (request == null || !request.isObject()) {
            // JSON-RPC batches are dropped from the 2025 revision of the MCP transport, so an
            // array is rejected as a shape rather than iterated.
            return error(null, INVALID_REQUEST, "request must be a single JSON-RPC object");
        }
        JsonNode idNode = request.get("id");
        String method = request.path("method").asText(null);
        if (!JSONRPC_VERSION.equals(request.path("jsonrpc").asText(null)) || method == null) {
            return error(valueOf(idNode), INVALID_REQUEST, "jsonrpc must be \"2.0\" and method must be present");
        }
        if (idNode == null || idNode.isNull()) {
            // A notification expects nothing back; initialized/cancelled both land here.
            if (!method.startsWith(NOTIFICATION_PREFIX)) {
                return error(null, INVALID_REQUEST, "requests must carry an id, only notifications may omit it");
            }
            return new McpReply(STATUS_ACCEPTED, null);
        }
        Object id = valueOf(idNode);
        JsonNode params = request.path("params");
        return switch (method) {
            case METHOD_INITIALIZE -> result(id, initializeResult(params));
            case METHOD_PING -> result(id, Map.of());
            case METHOD_TOOLS_LIST -> result(id, Map.of("tools", toolCatalogue()));
            case METHOD_TOOLS_CALL -> toolsCall(id, params, executor);
            default -> error(id, METHOD_NOT_FOUND, "method not supported: " + method);
        };
    }

    private Map<String, Object> initializeResult(JsonNode params) {
        String requested = params.path("protocolVersion").asText(null);
        String negotiated = SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;
        return Map.of(
                "protocolVersion", negotiated,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", serverName, "version", serverVersion));
    }

    private List<Map<String, Object>> toolCatalogue() {
        List<Map<String, Object>> catalogue = new ArrayList<>(tools.size());
        for (McpToolSpec tool : tools) {
            catalogue.add(Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "inputSchema", tool.inputSchema()));
        }
        return catalogue;
    }

    private McpReply toolsCall(Object id, JsonNode params, ToolExecutor executor) {
        String toolName = params.path("name").asText(null);
        if (toolName == null || tools.stream().noneMatch(tool -> tool.name().equals(toolName))) {
            return error(id, INVALID_PARAMS, "unknown tool: " + toolName);
        }
        JsonNode arguments = params.path("arguments");
        if (arguments.isMissingNode() || arguments.isNull()) {
            arguments = JsonUtil.mapper().createObjectNode();
        }
        try {
            Object payload = executor.execute(toolName, arguments);
            return result(id, toolResult(payload, false));
        } catch (BizException e) {
            log.info("mcp tool call rejected, tool={}, errorCode={}, reason={}",
                    toolName, e.getErrorCode(), e.getMessage());
            return result(id, toolError(e.getErrorCode().name(), e.getMessage()));
        } catch (Exception e) {
            log.error("mcp tool call failed, errorCode={}, tool={}", ErrorCode.INTERNAL_ERROR, toolName, e);
            return result(id, toolError(ErrorCode.INTERNAL_ERROR.name(),
                    ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
        }
    }

    private Map<String, Object> toolResult(Object payload, boolean isError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", JsonUtil.toJson(payload))));
        result.put("structuredContent", payload);
        result.put("isError", isError);
        return result;
    }

    private Map<String, Object> toolError(String code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", code + ": " + message)));
        result.put("isError", true);
        return result;
    }

    private McpReply result(Object id, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        envelope.put("id", id);
        envelope.put("result", payload);
        return new McpReply(STATUS_OK, JsonUtil.toJson(envelope));
    }

    private McpReply error(Object id, int code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", JSONRPC_VERSION);
        envelope.put("id", id);
        envelope.put("error", Map.of("code", code, "message", message));
        return new McpReply(STATUS_OK, JsonUtil.toJson(envelope));
    }

    private Object valueOf(JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        return idNode.isNumber() ? idNode.numberValue() : idNode.asText();
    }
}
