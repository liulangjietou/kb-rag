// Author: owlzhangfq@gmail.com
import { consumeSse } from '../utils/sse';
import type { ChatDeltaEvent, ChatDoneEvent, ChatErrorEvent, ChatReferencesEvent, ChatRequest, RetrievalNode } from './types';

export interface ChatStreamHandlers {
  onDelta: (delta: string) => void;
  onReferences: (references: RetrievalNode[]) => void;
  onDone: (requestId: string, degraded: string[]) => void;
  onError: (error: { code: string; message: string }) => void;
}

/**
 * Shared SSE driver for stream=true chat, used by both the API-debug tab (API Key auth, external
 * `/api/v1/knowledge/chat`) and the 问答调试 page's admin-authenticated preview (JWT auth,
 * `/api/v1/apps/{id}/chat-preview`) -- both endpoints share the same message_delta (repeated),
 * references, done, error event framing (M4c-CONTRACTS.md section 3), only the URL and auth
 * header differ.
 *
 * Deliberately bypasses the shared axios `client` in request.ts: that instance's response
 * interceptor treats any 401 as "admin session expired -> redirect to login" and pops a global
 * message toast, neither of which is correct here -- a bad/expired API Key or a transient upstream
 * error must render inline in the debug UI, not blow away the admin's own session.
 */
export async function streamChat(
  url: string,
  headers: Record<string, string>,
  payload: Omit<ChatRequest, 'stream'>,
  handlers: ChatStreamHandlers,
): Promise<void> {
  let response: Response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...headers },
      body: JSON.stringify({ ...payload, stream: true }),
    });
  } catch {
    handlers.onError({ code: 'NETWORK_ERROR', message: '请求发送失败，请检查网络或后端服务状态' });
    return;
  }

  const contentType = response.headers.get('content-type') ?? '';
  if (!response.ok || !contentType.includes('text/event-stream')) {
    const body = await response.json().catch(() => null);
    handlers.onError({
      code: body?.code ?? String(response.status),
      message: body?.message ?? response.statusText ?? '请求失败',
    });
    return;
  }

  await consumeSse(response, (evt) => {
    let data: unknown;
    try {
      data = JSON.parse(evt.data);
    } catch {
      handlers.onError({ code: 'PARSE_ERROR', message: 'SSE 数据解析失败' });
      return;
    }
    switch (evt.event) {
      case 'message_delta': {
        const delta = data as ChatDeltaEvent;
        handlers.onDelta(delta.delta ?? '');
        break;
      }
      case 'references': {
        const references = data as ChatReferencesEvent;
        handlers.onReferences(references.references ?? []);
        break;
      }
      case 'done': {
        const done = data as ChatDoneEvent;
        handlers.onDone(done.request_id, done.degraded ?? []);
        break;
      }
      case 'error': {
        const error = data as ChatErrorEvent;
        handlers.onError({ code: error.code ?? 'UPSTREAM_MODEL_ERROR', message: error.message ?? '生成失败' });
        break;
      }
      default:
        break;
    }
  });
}
