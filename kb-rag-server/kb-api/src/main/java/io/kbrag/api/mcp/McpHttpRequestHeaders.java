package io.kbrag.api.mcp;

import jakarta.servlet.http.HttpServletRequest;

/**
 * MCP HTTP transport 的标准镜像头，只负责从 Servlet 边界读取值，不解释协议语义。
 *
 * @param protocolVersion MCP-Protocol-Version
 * @param method          Mcp-Method
 * @param name            Mcp-Name
 * @author owlzhangfq@gmail.com
 */
public record McpHttpRequestHeaders(String protocolVersion, String method, String name) {

    /** 协议版本头。 */
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

    /** JSON-RPC 方法镜像头。 */
    public static final String HEADER_METHOD = "Mcp-Method";

    /** 工具名、资源 URI 或提示词名镜像头。 */
    public static final String HEADER_NAME = "Mcp-Name";

    /**
     * @param request 当前 HTTP 请求
     * @return 三个镜像头的原始值
     */
    public static McpHttpRequestHeaders from(HttpServletRequest request) {
        return new McpHttpRequestHeaders(
                request.getHeader(HEADER_PROTOCOL_VERSION),
                request.getHeader(HEADER_METHOD),
                request.getHeader(HEADER_NAME));
    }

    /** @return 不携带现代协议头的旧版上下文 */
    public static McpHttpRequestHeaders legacy() {
        return new McpHttpRequestHeaders(null, null, null);
    }

    boolean hasModernMarker() {
        return protocolVersion != null || method != null || name != null;
    }
}
