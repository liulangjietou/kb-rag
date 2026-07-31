package io.kbrag.api.mcp;

import java.util.Map;

/**
 * Declaration of one MCP tool: what {@code tools/list} advertises to a connecting agent.
 *
 * <p>The input schema is a plain JSON Schema object rather than a typed model, because the MCP
 * client consumes it verbatim and the server never validates against it - argument validation
 * happens on the transport DTO the arguments are bound to, exactly where the REST twin validates.
 *
 * @param name        tool name, unique within one MCP endpoint
 * @param description what the tool does, shown to the calling model
 * @param inputSchema JSON Schema of the {@code arguments} object
 *
 * @author owlzhangfq@gmail.com
 */
public record McpToolSpec(String name, String description, Map<String, Object> inputSchema) {
}
