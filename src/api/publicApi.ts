// Author: owlzhangfq@gmail.com
import { streamChat, type ChatStreamHandlers } from './chatStream';
import type { ChatRequest, ChatResponse, PublicSearchRequest, PublicSearchResponse } from './types';

/**
 * Direct calls against the external, API-Key-gated endpoints (M4c-CONTRACTS.md section 3), used
 * only by the API 调试 tab. Deliberately bypasses request.ts's shared axios `client` for the same
 * reason chatStream.ts does: that instance auto-attaches the admin JWT and treats 401 as "session
 * expired", neither of which applies to a debug call authenticated with a pasted-in API Key -- a
 * bad/disabled/rate-limited Key is exactly the case this page exists to let an operator observe.
 */

export interface PublicApiError {
  code: string;
  message: string;
  request_id?: string;
  http_status: number;
  /** Present on 429 RATE_LIMITED responses (M4c-CONTRACTS.md section 3: "Retry-After:1"). */
  retry_after: string | null;
}

export type PublicApiResult<T> = { ok: true; data: T } | { ok: false; error: PublicApiError };

async function postJson<T>(url: string, apiKey: string, body: unknown): Promise<PublicApiResult<T>> {
  let response: Response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
      body: JSON.stringify(body),
    });
  } catch {
    return {
      ok: false,
      error: { code: 'NETWORK_ERROR', message: '请求发送失败，请检查网络或后端服务状态', http_status: 0, retry_after: null },
    };
  }
  const envelope = await response.json().catch(() => null);
  if (!response.ok || !envelope || envelope.code !== 'OK') {
    return {
      ok: false,
      error: {
        code: envelope?.code ?? String(response.status),
        message: envelope?.message ?? response.statusText ?? '请求失败',
        request_id: envelope?.request_id,
        http_status: response.status,
        retry_after: response.headers.get('Retry-After'),
      },
    };
  }
  return { ok: true, data: envelope.data as T };
}

/** POST /api/v1/knowledge/search (M4c-CONTRACTS.md section 3), authenticated by the given API Key plaintext. */
export function searchViaApiKey(
  apiKey: string,
  payload: PublicSearchRequest,
): Promise<PublicApiResult<PublicSearchResponse>> {
  return postJson<PublicSearchResponse>('/api/v1/knowledge/search', apiKey, payload);
}

/** Non-streaming POST /api/v1/knowledge/chat (M4c-CONTRACTS.md section 3). */
export function chatViaApiKey(apiKey: string, payload: ChatRequest): Promise<PublicApiResult<ChatResponse>> {
  return postJson<ChatResponse>('/api/v1/knowledge/chat', apiKey, { ...payload, stream: false });
}

/** Streaming POST /api/v1/knowledge/chat (stream=true); see chatStream.ts for the shared SSE driver. */
export function streamChatViaApiKey(apiKey: string, payload: Omit<ChatRequest, 'stream'>, handlers: ChatStreamHandlers): Promise<void> {
  return streamChat('/api/v1/knowledge/chat', { Authorization: `Bearer ${apiKey}` }, payload, handlers);
}
