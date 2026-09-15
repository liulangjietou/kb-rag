import { expect, it, vi } from 'vitest';
import { readChatDiagnostics } from './chatDiagnostics';
import { streamChat } from './chatStream';

it('保留实测零值，缺失阶段不转换为零', () => {
  expect(readChatDiagnostics({ outcome: 'FAILED', failed_stage: 'RETRIEVAL', configuration_ms: 0, total_ms: 12 }))
    .toEqual(expect.objectContaining({ configuration_ms: 0, retrieval_ms: undefined, generation_ms: undefined, total_ms: 12 }));
});

it.each([-1, NaN, Infinity, '12', {}, 1.5])('非法可选诊断不能变成可信耗时：%s', value => {
  expect(readChatDiagnostics({ outcome: 'SUCCEEDED', total_ms: 30, retrieval_ms: value })).toBeUndefined();
});

it('错误诊断不使正文失败，诊断之后仍必须有业务终态，终态之后忽略诊断', async () => {
  const onDiagnostics = vi.fn();
  const onDone = vi.fn();
  const onError = vi.fn();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response([
    'event: diagnostics\ndata: invalid',
    'event: diagnostics\ndata: {"outcome":"SUCCEEDED","total_ms":12}',
    'event: done\ndata: {"request_id":"req_1"}',
    'event: diagnostics\ndata: {"outcome":"SUCCEEDED","total_ms":999}', '', '',
  ].join('\n\n'), { headers: { 'content-type': 'text/event-stream' } })));
  try {
    await streamChat('/preview', {}, { query: '问题' }, { onDiagnostics, onDone, onError, onDelta: vi.fn(), onReferences: vi.fn() });
    expect(onDiagnostics).toHaveBeenCalledTimes(1);
    expect(onDiagnostics).toHaveBeenCalledWith(expect.objectContaining({ total_ms: 12 }));
    expect(onDone).toHaveBeenCalledExactlyOnceWith('req_1', [], []);
    expect(onError).not.toHaveBeenCalled();
  } finally { vi.unstubAllGlobals(); }
});

it('只有诊断而没有完成事件时仍报告回答未完成', async () => {
  const onError = vi.fn();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('event: diagnostics\ndata: {"outcome":"SUCCEEDED","total_ms":12}\n\n',
    { headers: { 'content-type': 'text/event-stream' } })));
  try {
    await streamChat('/preview', {}, { query: '问题' }, { onDiagnostics: vi.fn(), onDone: vi.fn(), onError, onDelta: vi.fn(), onReferences: vi.fn() });
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'STREAM_INCOMPLETE' }));
  } finally { vi.unstubAllGlobals(); }
});
