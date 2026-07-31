package io.kbrag.api.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.api.dto.MemoryAddRequest;
import io.kbrag.api.dto.MemoryAddResponse;
import io.kbrag.api.dto.MemoryNodePageResponse;
import io.kbrag.api.dto.MemoryNodeResponse;
import io.kbrag.api.dto.MemoryProfileResponse;
import io.kbrag.api.dto.MemorySearchRequest;
import io.kbrag.api.dto.MemorySearchResponse;
import io.kbrag.api.filter.MemoryKeyAuthFilter;
import io.kbrag.api.mcp.McpArgumentBinder;
import io.kbrag.api.mcp.McpJsonSchemas;
import io.kbrag.api.mcp.McpServerEngine;
import io.kbrag.api.mcp.McpToolSpec;
import io.kbrag.app.memory.MemoryApiService;
import io.kbrag.app.memory.MemoryKeyPrincipal;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * The memory library's MCP endpoint, the M20 contract: the six memory open API operations as MCP
 * tools, so any MCP capable agent maintains long term user memories without bespoke glue code.
 *
 * <p>Lives under the memory API prefix on purpose: {@link MemoryKeyAuthFilter} authenticates and
 * rate limits it with the very same {@code kb-mk-*} credential, library binding and token bucket
 * as the REST twin - the library every tool touches is the principal's, never the arguments'.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
public class MemoryMcpController {

    /** Tool writing memories, the AddMemory twin. */
    public static final String TOOL_ADD = "memory_add";

    /** Tool recalling memories, the SearchMemory twin. */
    public static final String TOOL_SEARCH = "memory_search";

    /** Tool paging memories, the ListMemory twin. */
    public static final String TOOL_LIST = "memory_list";

    /** Tool replacing one memory's content, the UpdateMemory twin. */
    public static final String TOOL_UPDATE = "memory_update";

    /** Tool removing one memory, the DeleteMemory twin. */
    public static final String TOOL_DELETE = "memory_delete";

    /** Tool reading the user's profiles, the GetUserProfile twin. */
    public static final String TOOL_GET_PROFILE = "memory_get_profile";

    /** Server name advertised on {@code initialize}. */
    private static final String SERVER_NAME = "kb-rag-memory";

    /** Server version advertised on {@code initialize}, follows the OpenAPI contract version. */
    private static final String SERVER_VERSION = "0.20.0";

    /** Contract defaults of the list tool, same as the REST twin's query parameter defaults. */
    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final MemoryApiService memoryApiService;
    private final McpArgumentBinder binder;
    private final McpServerEngine engine;

    public MemoryMcpController(MemoryApiService memoryApiService, McpArgumentBinder binder) {
        this.memoryApiService = memoryApiService;
        this.binder = binder;
        this.engine = new McpServerEngine(SERVER_NAME, SERVER_VERSION, List.of(
                new McpToolSpec(TOOL_ADD,
                        "Writes long term memories for one end user: extracts memory fragments from a "
                                + "conversation and/or stores a verbatim memory entry.",
                        addSchema()),
                new McpToolSpec(TOOL_SEARCH,
                        "Semantic recall of one end user's live memories, returned together with the "
                                + "user's structured profiles.",
                        searchSchema()),
                new McpToolSpec(TOOL_LIST,
                        "Pages one end user's stored memories, newest first, expired entries included.",
                        listSchema()),
                new McpToolSpec(TOOL_UPDATE,
                        "Replaces the content of one memory entry and refreshes its search copy.",
                        updateSchema()),
                new McpToolSpec(TOOL_DELETE,
                        "Deletes one memory entry of one end user.",
                        deleteSchema()),
                new McpToolSpec(TOOL_GET_PROFILE,
                        "Reads one end user's structured profiles, one per profile rule, with unfilled "
                                + "fields falling back to the rule's initial value.",
                        profileSchema())));
    }

    /**
     * One MCP exchange over the Streamable HTTP transport.
     *
     * @param body        raw JSON-RPC body
     * @param httpRequest current request, source of the authenticated caller
     * @return JSON-RPC reply, or 202 without a body for a notification
     */
    @PostMapping(value = "/api/v1/memory/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody String body, HttpServletRequest httpRequest) {
        MemoryKeyPrincipal principal = principalOf(httpRequest);
        McpServerEngine.McpReply reply = engine.handle(body,
                (toolName, arguments) -> execute(principal, toolName, arguments));
        return ResponseEntity.status(reply.status()).body(reply.body());
    }

    private Object execute(MemoryKeyPrincipal principal, String toolName, JsonNode arguments) {
        return switch (toolName) {
            case TOOL_ADD -> MemoryAddResponse.from(memoryApiService.add(principal,
                    binder.bind(arguments, MemoryAddRequest.class).toCommand()));
            case TOOL_SEARCH -> MemorySearchResponse.from(memoryApiService.search(principal,
                    binder.bind(arguments, MemorySearchRequest.class).toCommand()));
            case TOOL_LIST -> listNodes(principal, binder.bind(arguments, ListArgs.class));
            case TOOL_UPDATE -> updateNode(principal, binder.bind(arguments, UpdateArgs.class));
            case TOOL_DELETE -> deleteNode(principal, binder.bind(arguments, DeleteArgs.class));
            case TOOL_GET_PROFILE -> profiles(principal, binder.bind(arguments, ProfileArgs.class));
            // The engine only dispatches catalogued names, so this is a wiring fault, not input.
            default -> throw new BizException(ErrorCode.INTERNAL_ERROR, "tool not wired: " + toolName);
        };
    }

    private Object listNodes(MemoryKeyPrincipal principal, ListArgs args) {
        return MemoryNodePageResponse.from(memoryApiService.listNodes(principal, args.userId(),
                args.ruleId(),
                args.pageNum() == null ? DEFAULT_PAGE_NUM : args.pageNum(),
                args.pageSize() == null ? DEFAULT_PAGE_SIZE : args.pageSize()));
    }

    private Object updateNode(MemoryKeyPrincipal principal, UpdateArgs args) {
        return MemoryNodeResponse.from(memoryApiService.updateNode(principal,
                args.memoryNodeId(), args.userId(), args.customContent(), args.metaData()));
    }

    private Object deleteNode(MemoryKeyPrincipal principal, DeleteArgs args) {
        memoryApiService.deleteNode(principal, args.memoryNodeId(), args.userId());
        return Map.of("deleted", true, "memory_node_id", args.memoryNodeId());
    }

    private Object profiles(MemoryKeyPrincipal principal, ProfileArgs args) {
        return memoryApiService.profiles(principal, args.userId(), args.ruleId())
                .stream().map(MemoryProfileResponse::from).toList();
    }

    // ------------------------------------------------------------------ argument shapes

    /**
     * Arguments of {@code memory_list}, the ListMemory twin's query parameters.
     *
     * @param userId   memory entity id
     * @param ruleId   restricts to one fragment rule, optional
     * @param pageNum  1-based page number, defaults to 1
     * @param pageSize page size, defaults to 10
     */
    record ListArgs(@JsonProperty("user_id") @NotBlank(message = "user_id must not be blank") String userId,
                    @JsonProperty("rule_id") String ruleId,
                    @JsonProperty("page_num") @Min(value = 1, message = "page_num must be at least 1")
                    Integer pageNum,
                    @JsonProperty("page_size") @Min(value = 1, message = "page_size must be at least 1")
                    @Max(value = 100, message = "page_size must be at most 100") Integer pageSize) {
    }

    /**
     * Arguments of {@code memory_update}, the UpdateMemory twin's path variable plus payload.
     *
     * @param memoryNodeId  node business id
     * @param userId        memory entity id the node must belong to
     * @param customContent new content
     * @param metaData      new metadata, absent keeps the stored one
     */
    record UpdateArgs(@JsonProperty("memory_node_id")
                      @NotBlank(message = "memory_node_id must not be blank") String memoryNodeId,
                      @JsonProperty("user_id") @NotBlank(message = "user_id must not be blank") String userId,
                      @JsonProperty("custom_content")
                      @NotBlank(message = "custom_content must not be blank")
                      @Size(max = 4000, message = "custom_content must be at most 4000 characters")
                      String customContent,
                      @JsonProperty("meta_data") Map<String, Object> metaData) {
    }

    /**
     * Arguments of {@code memory_delete}.
     *
     * @param memoryNodeId node business id
     * @param userId       memory entity id the node must belong to
     */
    record DeleteArgs(@JsonProperty("memory_node_id")
                      @NotBlank(message = "memory_node_id must not be blank") String memoryNodeId,
                      @JsonProperty("user_id") @NotBlank(message = "user_id must not be blank") String userId) {
    }

    /**
     * Arguments of {@code memory_get_profile}.
     *
     * @param userId memory entity id
     * @param ruleId restricts to one profile rule, optional
     */
    record ProfileArgs(@JsonProperty("user_id") @NotBlank(message = "user_id must not be blank") String userId,
                       @JsonProperty("rule_id") String ruleId) {
    }

    // ------------------------------------------------------------------ tool schemas

    private static Map<String, Object> messagesSchema() {
        return McpJsonSchemas.array(
                McpJsonSchemas.object(new LinkedHashMap<>(Map.of(
                        "role", McpJsonSchemas.string("user or assistant"),
                        "content", McpJsonSchemas.string("turn content"))), List.of("role", "content")),
                "Conversation turns to extract memories from, chronological order, at most 50.");
    }

    private static Map<String, Object> addSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("user_id", McpJsonSchemas.string("End user id owning the memories."));
        properties.put("messages", messagesSchema());
        properties.put("custom_content", McpJsonSchemas.string(
                "Verbatim memory content written without extraction; at least one of messages and "
                        + "custom_content is required."));
        properties.put("fragment_rule_id", McpJsonSchemas.string(
                "Fragment rule to apply; absent takes the library's builtin default."));
        properties.put("profile_rule_id", McpJsonSchemas.string(
                "Profile rule to extract under, requires messages; absent skips profile extraction."));
        properties.put("meta_data", McpJsonSchemas.freeObject(
                "Caller metadata attached to every created memory, stored and returned verbatim."));
        return McpJsonSchemas.object(properties, List.of("user_id"));
    }

    private static Map<String, Object> searchSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("user_id", McpJsonSchemas.string("End user id owning the memories."));
        properties.put("query", McpJsonSchemas.string("The question to recall memories for."));
        properties.put("fragment_rule_id", McpJsonSchemas.string("Restricts recall to one fragment rule."));
        properties.put("max_results", McpJsonSchemas.integer("Most nodes returned, 1 to 100, default 10."));
        properties.put("intent_recognition", McpJsonSchemas.bool(
                "Lets the model veto the recall for questions that need no memory."));
        properties.put("rewrite", McpJsonSchemas.bool("Rewrites the colloquial question before recall."));
        properties.put("rerank", McpJsonSchemas.bool("Reranks the candidates with the rerank model."));
        properties.put("similarity_threshold", McpJsonSchemas.number(
                "Drops reranked results below it, 0.0 to 1.0; only honoured when rerank is on."));
        return McpJsonSchemas.object(properties, List.of("user_id", "query"));
    }

    private static Map<String, Object> listSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("user_id", McpJsonSchemas.string("End user id owning the memories."));
        properties.put("rule_id", McpJsonSchemas.string("Restricts to one fragment rule."));
        properties.put("page_num", McpJsonSchemas.integer("1-based page number, default 1."));
        properties.put("page_size", McpJsonSchemas.integer("Page size, 1 to 100, default 10."));
        return McpJsonSchemas.object(properties, List.of("user_id"));
    }

    private static Map<String, Object> updateSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("memory_node_id", McpJsonSchemas.string("Memory node id to update."));
        properties.put("user_id", McpJsonSchemas.string("End user id the node must belong to."));
        properties.put("custom_content", McpJsonSchemas.string("New content, at most 4000 characters."));
        properties.put("meta_data", McpJsonSchemas.freeObject(
                "New metadata; absent keeps the stored one, an empty object clears it."));
        return McpJsonSchemas.object(properties, List.of("memory_node_id", "user_id", "custom_content"));
    }

    private static Map<String, Object> deleteSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("memory_node_id", McpJsonSchemas.string("Memory node id to delete."));
        properties.put("user_id", McpJsonSchemas.string("End user id the node must belong to."));
        return McpJsonSchemas.object(properties, List.of("memory_node_id", "user_id"));
    }

    private static Map<String, Object> profileSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("user_id", McpJsonSchemas.string("End user id owning the profiles."));
        properties.put("rule_id", McpJsonSchemas.string("Restricts to one profile rule."));
        return McpJsonSchemas.object(properties, List.of("user_id"));
    }

    /**
     * Reads the caller the filter authenticated, same contract as the REST twin.
     *
     * @param request current request
     * @return authenticated caller
     */
    private MemoryKeyPrincipal principalOf(HttpServletRequest request) {
        Object principal = request.getAttribute(MemoryKeyAuthFilter.ATTR_PRINCIPAL);
        if (!(principal instanceof MemoryKeyPrincipal resolved)) {
            log.error("memory mcp endpoint reached without an authenticated key, errorCode={}, uri={}",
                    ErrorCode.INVALID_API_KEY, request.getRequestURI());
            throw new BizException(ErrorCode.INVALID_API_KEY, "Memory Key 鉴权未通过");
        }
        return resolved;
    }
}
