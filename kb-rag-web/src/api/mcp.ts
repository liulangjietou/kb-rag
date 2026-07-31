// Author: owlzhangfq@gmail.com

/**
 * Direct JSON-RPC 2.0 calls against the MCP endpoints (M20-CONTRACTS.md), used only by the MCP
 * 调试 page. Deliberately bypasses request.ts's shared axios `client` for the same reason
 * publicApi.ts does: these endpoints are authenticated with a pasted-in API Key / Memory Key, not
 * the admin JWT, and a bad/disabled/rate-limited key is exactly what the page exists to observe.
 */

/** The two MCP servers this console can debug, one per credential family. */
export type McpEndpoint = 'knowledge' | 'memory';

/** Path of each MCP server; both live under their REST twin's auth-filter prefix. */
export const MCP_PATHS: Record<McpEndpoint, string> = {
  knowledge: '/api/v1/knowledge/mcp',
  memory: '/api/v1/memory/mcp',
};

/** Protocol revision this console speaks; the server negotiates down from its own if needed. */
export const MCP_PROTOCOL_VERSION = '2025-03-26';

export interface McpToolInfo {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export interface JsonRpcError {
  code: number;
  message: string;
  data?: unknown;
}

export interface JsonRpcResponse {
  jsonrpc: '2.0';
  id: number | string | null;
  result?: Record<string, unknown>;
  error?: JsonRpcError;
}

export interface McpRpcOutcome {
  /** HTTP status; 202 means an accepted notification without a body. */
  http_status: number;
  /** Parsed JSON-RPC reply, null on a bodyless 202 or an unparsable body. */
  response: JsonRpcResponse | null;
  /** The exact request body that was sent, for the raw exchange view. */
  request_body: string;
}

let nextId = 1;

/**
 * Fires one JSON-RPC request at an MCP endpoint and returns both planes untouched: a JSON-RPC
 * `error` (protocol violation) and a `result` with `isError: true` (business failure) are results
 * to display, not exceptions -- distinguishing them is the point of the debug page.
 */
export async function mcpRpc(
  endpoint: McpEndpoint,
  apiKey: string,
  method: string,
  params?: Record<string, unknown>,
): Promise<McpRpcOutcome> {
  const body = JSON.stringify(
    { jsonrpc: '2.0', id: nextId++, method, ...(params !== undefined ? { params } : {}) },
    null,
    2,
  );
  let response: Response;
  try {
    response = await fetch(MCP_PATHS[endpoint], {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
      body,
    });
  } catch {
    return {
      http_status: 0,
      response: {
        jsonrpc: '2.0',
        id: null,
        error: { code: 0, message: '请求发送失败，请检查网络或后端服务状态' },
      },
      request_body: body,
    };
  }
  const parsed = (await response.json().catch(() => null)) as JsonRpcResponse | null;
  return { http_status: response.status, response: parsed, request_body: body };
}

/** The `initialize` handshake, the first exchange every MCP client performs. */
export function mcpInitialize(endpoint: McpEndpoint, apiKey: string): Promise<McpRpcOutcome> {
  return mcpRpc(endpoint, apiKey, 'initialize', {
    protocolVersion: MCP_PROTOCOL_VERSION,
    capabilities: {},
    clientInfo: { name: 'kb-rag-console', version: '1.0.0' },
  });
}

/** `tools/list`: the server's tool catalogue with JSON Schema argument shapes. */
export async function mcpListTools(
  endpoint: McpEndpoint,
  apiKey: string,
): Promise<{ outcome: McpRpcOutcome; tools: McpToolInfo[] }> {
  const outcome = await mcpRpc(endpoint, apiKey, 'tools/list');
  const tools = (outcome.response?.result?.tools as McpToolInfo[] | undefined) ?? [];
  return { outcome, tools };
}

/** `tools/call`: runs one tool with the given arguments object. */
export function mcpCallTool(
  endpoint: McpEndpoint,
  apiKey: string,
  toolName: string,
  args: Record<string, unknown>,
): Promise<McpRpcOutcome> {
  return mcpRpc(endpoint, apiKey, 'tools/call', { name: toolName, arguments: args });
}
