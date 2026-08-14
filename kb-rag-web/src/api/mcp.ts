// Author: owlzhangfq@gmail.com

/** MCP 调试页使用的双协议 JSON-RPC 客户端，不经过管理台 JWT axios 实例。 */

/** 两个 MCP 服务端点。 */
export type McpEndpoint = 'knowledge' | 'memory';

/** 现代逐请求元数据协议与旧版 initialize 握手协议。 */
export type McpProtocolEra = 'modern' | 'legacy';

export const MCP_PATHS: Record<McpEndpoint, string> = {
  knowledge: '/api/v1/knowledge/mcp',
  memory: '/api/v1/memory/mcp',
};

export const MCP_PROTOCOL_VERSIONS: Record<McpProtocolEra, string> = {
  modern: '2026-07-28',
  legacy: '2025-03-26',
};

const MCP_CLIENT_INFO = { name: 'kb-rag-console', version: '1.0.0' };

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
  /** HTTP 状态；现代协议的 400/404 与 JSON-RPC error 同时保留。 */
  http_status: number;
  response: JsonRpcResponse | null;
  request_body: string;
}

let nextId = 1;

/** 构造可直接上 wire 的 JSON-RPC 请求对象，也供页面生成准确的 curl 预览。 */
export function buildMcpRequest(
  era: McpProtocolEra,
  id: number,
  method: string,
  params?: Record<string, unknown>,
): Record<string, unknown> {
  const requestParams = era === 'modern'
    ? {
        ...(params ?? {}),
        _meta: {
          'io.modelcontextprotocol/protocolVersion': MCP_PROTOCOL_VERSIONS.modern,
          'io.modelcontextprotocol/clientCapabilities': {},
          'io.modelcontextprotocol/clientInfo': MCP_CLIENT_INFO,
        },
      }
    : params;
  return {
    jsonrpc: '2.0',
    id,
    method,
    ...(requestParams !== undefined ? { params: requestParams } : {}),
  };
}

/** 构造 transport 请求头；Mcp-Name 的非 ASCII 值按规范使用精确 Base64 sentinel。 */
export function buildMcpHeaders(
  era: McpProtocolEra,
  method: string,
  params?: Record<string, unknown>,
): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
  };
  if (era === 'legacy') return headers;
  headers['MCP-Protocol-Version'] = MCP_PROTOCOL_VERSIONS.modern;
  headers['Mcp-Method'] = method;
  if (method === 'tools/call' && typeof params?.name === 'string') {
    headers['Mcp-Name'] = encodeHeaderValue(params.name);
  }
  return headers;
}

/** 发起一次请求并原样保留 HTTP、JSON-RPC、工具业务三类结果平面。 */
export async function mcpRpc(
  endpoint: McpEndpoint,
  apiKey: string,
  era: McpProtocolEra,
  method: string,
  params?: Record<string, unknown>,
): Promise<McpRpcOutcome> {
  const request = buildMcpRequest(era, nextId++, method, params);
  const requestHeaders = {
    ...buildMcpHeaders(era, method, params),
    Authorization: `Bearer ${apiKey}`,
  };
  const body = JSON.stringify(request, null, 2);
  let response: Response;
  try {
    response = await fetch(MCP_PATHS[endpoint], {
      method: 'POST',
      headers: requestHeaders,
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

/** 现代协议的无状态能力发现。 */
export function mcpDiscover(endpoint: McpEndpoint, apiKey: string): Promise<McpRpcOutcome> {
  return mcpRpc(endpoint, apiKey, 'modern', 'server/discover', {});
}

/** 旧版协议的 initialize 握手。 */
export function mcpInitialize(endpoint: McpEndpoint, apiKey: string): Promise<McpRpcOutcome> {
  return mcpRpc(endpoint, apiKey, 'legacy', 'initialize', {
    protocolVersion: MCP_PROTOCOL_VERSIONS.legacy,
    capabilities: {},
    clientInfo: MCP_CLIENT_INFO,
  });
}

/** 列出工具目录。 */
export async function mcpListTools(
  endpoint: McpEndpoint,
  apiKey: string,
  era: McpProtocolEra,
): Promise<{ outcome: McpRpcOutcome; tools: McpToolInfo[] }> {
  const outcome = await mcpRpc(endpoint, apiKey, era, 'tools/list', era === 'modern' ? {} : undefined);
  const tools = (outcome.response?.result?.tools as McpToolInfo[] | undefined) ?? [];
  return { outcome, tools };
}

/** 调用一个工具。 */
export function mcpCallTool(
  endpoint: McpEndpoint,
  apiKey: string,
  era: McpProtocolEra,
  toolName: string,
  args: Record<string, unknown>,
): Promise<McpRpcOutcome> {
  return mcpRpc(endpoint, apiKey, era, 'tools/call', { name: toolName, arguments: args });
}

function encodeHeaderValue(value: string): string {
  const isVisibleAscii = /^[\x21-\x7E](?:[\x20-\x7E]*[\x21-\x7E])?$/.test(value);
  const isSentinel = value.startsWith('=?base64?') && value.endsWith('?=');
  if (isVisibleAscii && !isSentinel) return value;
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return `=?base64?${btoa(binary)}?=`;
}
