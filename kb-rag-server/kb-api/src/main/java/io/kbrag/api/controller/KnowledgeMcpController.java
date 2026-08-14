package io.kbrag.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.api.dto.KnowledgeCallRequest;
import io.kbrag.api.dto.KnowledgeChatResponse;
import io.kbrag.api.dto.KnowledgeSearchResponse;
import io.kbrag.api.filter.ApiKeyAuthFilter;
import io.kbrag.api.mcp.McpArgumentBinder;
import io.kbrag.api.mcp.McpHttpRequestHeaders;
import io.kbrag.api.mcp.McpJsonSchemas;
import io.kbrag.api.mcp.McpServerEngine;
import io.kbrag.api.mcp.McpToolSpec;
import io.kbrag.app.openapi.ApiKeyPrincipal;
import io.kbrag.app.openapi.KnowledgeApiService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库应用的 MCP 双协议端点，把开放 API 的检索与问答暴露为 Agent 工具。
 *
 * <p>Lives under the open API prefix on purpose: {@link ApiKeyAuthFilter} authenticates and rate
 * limits it with the very same {@code kb-sk-*} credential, scope rule and token bucket as the REST
 * twin - MCP is a second transport over the same identity, never a second identity.
 *
 * <p>Chat is served body-only here. MCP's {@code tools/call} is a single request/response
 * exchange; an agent that wants token streaming uses the REST twin's SSE mapping.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
public class KnowledgeMcpController {

    /** Tool running the open search call. */
    public static final String TOOL_SEARCH = "knowledge_search";

    /** Tool running the open chat call, body transport. */
    public static final String TOOL_CHAT = "knowledge_chat";

    /** 在现代 discover 元数据与旧版 initialize 中公布的服务名。 */
    private static final String SERVER_NAME = "kb-rag-knowledge";

    /** 随服务信息公布的版本，与 OpenAPI 契约版本一致。 */
    private static final String SERVER_VERSION = "0.24.0";

    private final KnowledgeApiService knowledgeApiService;
    private final McpArgumentBinder binder;
    private final McpServerEngine engine;

    public KnowledgeMcpController(KnowledgeApiService knowledgeApiService, McpArgumentBinder binder) {
        this.knowledgeApiService = knowledgeApiService;
        this.binder = binder;
        this.engine = new McpServerEngine(SERVER_NAME, SERVER_VERSION, List.of(
                new McpToolSpec(TOOL_SEARCH,
                        "Retrieval-only call against a published knowledge application: returns the most "
                                + "relevant knowledge chunks for a query, without answer generation.",
                        callSchema(false)),
                new McpToolSpec(TOOL_CHAT,
                        "Retrieval-augmented generation call against a published knowledge application: "
                                + "retrieves relevant chunks and generates an answer with references.",
                        callSchema(true))));
    }

    /**
     * One MCP exchange over the Streamable HTTP transport.
     *
     * @param body        raw JSON-RPC body
     * @param httpRequest current request, source of the authenticated caller
     * @return JSON-RPC reply, or 202 without a body for a notification
     */
    @PostMapping(value = "/api/v1/knowledge/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody String body, HttpServletRequest httpRequest) {
        ApiKeyPrincipal principal = principalOf(httpRequest);
        McpServerEngine.McpReply reply = engine.handle(body, McpHttpRequestHeaders.from(httpRequest),
                (toolName, arguments) -> execute(principal, toolName, arguments));
        return ResponseEntity.status(reply.status()).body(reply.body());
    }

    private Object execute(ApiKeyPrincipal principal, String toolName, JsonNode arguments) {
        KnowledgeCallRequest request = binder.bind(arguments, KnowledgeCallRequest.class);
        if (request.getAppId() == null || request.getAppId().isBlank()) {
            throw BizException.invalidParam("app_id must not be blank");
        }
        if (request.isStream()) {
            throw BizException.invalidParam("MCP tools return a single response; streaming is only "
                    + "available on the REST endpoint POST /api/v1/knowledge/chat");
        }
        if (TOOL_SEARCH.equals(toolName)) {
            return KnowledgeSearchResponse.from(knowledgeApiService.search(principal, request.toCommand()));
        }
        return KnowledgeChatResponse.from(knowledgeApiService.chat(principal, request.toCommand()));
    }

    /**
     * Shared argument schema of the two tools; only the description of the conversational fields
     * differs, since chat is the one that uses history for generation.
     *
     * @param chat {@code true} for the chat tool
     * @return input schema
     */
    private static Map<String, Object> callSchema(boolean chat) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("app_id", McpJsonSchemas.string("Application id to call, e.g. app0001."));
        properties.put("query", McpJsonSchemas.string(chat
                ? "The user's question to answer from the application's knowledge bases."
                : "The query to retrieve knowledge chunks for."));
        properties.put("app_version", McpJsonSchemas.string(
                "Version literal, e.g. V2.0; absent routes to the released version."));
        properties.put("messages", McpJsonSchemas.array(
                McpJsonSchemas.object(new LinkedHashMap<>(Map.of(
                        "role", McpJsonSchemas.string("user or assistant"),
                        "content", McpJsonSchemas.string("turn content"))), List.of("role", "content")),
                "Conversation history in chronological order; the API keeps no session state."));
        properties.put("top_n", McpJsonSchemas.integer("Override: number of returned nodes, at least 1."));
        properties.put("score_threshold", McpJsonSchemas.number(
                "Override: absolute score threshold, 0.01 to 1.0."));
        properties.put("max_content_length", McpJsonSchemas.integer(
                "Override: character budget of the returned content."));
        return McpJsonSchemas.object(properties, List.of("app_id", "query"));
    }

    /**
     * Reads the caller the filter authenticated, same contract as the REST twin.
     *
     * @param request current request
     * @return authenticated caller
     */
    private ApiKeyPrincipal principalOf(HttpServletRequest request) {
        Object principal = request.getAttribute(ApiKeyAuthFilter.ATTR_PRINCIPAL);
        if (!(principal instanceof ApiKeyPrincipal resolved)) {
            log.error("mcp endpoint reached without an authenticated key, errorCode={}, uri={}",
                    ErrorCode.INVALID_API_KEY, request.getRequestURI());
            throw new BizException(ErrorCode.INVALID_API_KEY, "API Key 鉴权未通过");
        }
        return resolved;
    }
}
