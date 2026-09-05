import { afterEach, describe, expect, it, vi } from 'vitest';
import { streamChat } from './chatStream';

const handlers = () => ({ onDelta: vi.fn(), onReferences: vi.fn(), onDone: vi.fn(), onError: vi.fn() });
afterEach(() => vi.unstubAllGlobals());

describe('可取消的回答流', () => {
  it('保留增量、引用、路由和完成标识的真实 SSE 协议', async () => {
    const callbacks = handlers();
    const content = 'event: message_delta\ndata: {"delta":"根据资料"}\n\nevent: references\ndata: {"references":[]}\n\nevent: done\ndata: {"request_id":"req-1","degraded":["bm25_only"],"routed_kb_ids":["kb-1"]}\n\n';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(content, { headers: { 'content-type': 'text/event-stream' } })));
    await streamChat('/api/chat', {}, { app_id: 'app-1', query: '问题' }, callbacks);
    expect(callbacks.onDelta).toHaveBeenCalledWith('根据资料');
    expect(callbacks.onReferences).toHaveBeenCalledWith([]);
    expect(callbacks.onDone).toHaveBeenCalledWith('req-1', ['bm25_only'], ['kb-1']);
    expect(callbacks.onError).not.toHaveBeenCalled();
  });

  it('取消发送会中止 fetch，且不伪装成网络错误', async () => {
    const controller = new AbortController();
    const callbacks = handlers();
    vi.stubGlobal('fetch', vi.fn((_url, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    })));
    const pending = streamChat('/api/chat', {}, { app_id: 'app-1', query: '问题' }, callbacks, controller.signal);
    controller.abort();
    await pending;
    expect(callbacks.onError).not.toHaveBeenCalled();
    expect(callbacks.onDone).not.toHaveBeenCalled();
  });

  it('流读取期间的真实断线通过当前轮次的错误回调展示', async () => {
    const callbacks = handlers();
    const stream = new ReadableStream({ start(controller) { controller.error(new Error('connection lost')); } });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(stream, { headers: { 'content-type': 'text/event-stream' } })));
    await streamChat('/api/chat', {}, { app_id: 'app-1', query: '问题' }, callbacks);
    expect(callbacks.onError).toHaveBeenCalledWith({ code: 'NETWORK_ERROR', message: '回答连接中断，请重试' });
    expect(callbacks.onDone).not.toHaveBeenCalled();
  });
});
