import { describe, expect, it } from 'vitest';
import { consumeSse, type SseEvent } from './sse';

function streamingResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  return new Response(
    new ReadableStream({
      start(controller) {
        chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
        controller.close();
      },
    }),
  );
}

describe('consumeSse', () => {
  it('parses frames split across network chunks', async () => {
    const events: SseEvent[] = [];
    const response = streamingResponse([
      'event: message_delta\ndata: {"text":"hel',
      'lo"}\n\nevent: done\ndata: {"request_id":"r1"}\n\n',
    ]);

    await consumeSse(response, (event) => events.push(event));

    expect(events).toEqual([
      { event: 'message_delta', data: '{"text":"hello"}' },
      { event: 'done', data: '{"request_id":"r1"}' },
    ]);
  });

  it('joins multiline data only after a blank line and discards an incomplete trailing frame', async () => {
    const events: SseEvent[] = [];

    await consumeSse(streamingResponse(['data: first\ndata: second\n\ndata: incomplete\n']), (event) => events.push(event));

    expect(events).toEqual([{ event: 'message', data: 'first\nsecond' }]);
  });

  it.each(['\n', '\r\n', '\r'])('handles %j line endings split at every byte including UTF-8 text', async (ending) => {
    const events: SseEvent[] = [];
    const bytes = new TextEncoder().encode(`\uFEFF: heartbeat${ending}event: message_delta${ending}data: {"delta":"知识"}${ending}${ending}event: done${ending}data: {}${ending}${ending}`);
    const response = new Response(new ReadableStream({
      start(controller) {
        for (const byte of bytes) controller.enqueue(new Uint8Array([byte]));
        controller.close();
      },
    }));
    await consumeSse(response, (event) => events.push(event));
    expect(events).toEqual([
      { event: 'message_delta', data: '{"delta":"知识"}' },
      { event: 'done', data: '{}' },
    ]);
    expect(response.body?.locked).toBe(false);
  });

  it('preserves payload whitespace, accepts empty data and resets an empty event name', async () => {
    const events: SseEvent[] = [];
    await consumeSse(streamingResponse(['event:\ndata:  spaced  \ndata\n\n: ignored\n\n']), (event) => events.push(event));
    expect(events).toEqual([{ event: 'message', data: ' spaced  \n' }]);
  });

  it('releases and cancels the reader when the consumer fails', async () => {
    let cancelled = false;
    const response = new Response(new ReadableStream({
      start(controller) { controller.enqueue(new TextEncoder().encode('data: first\n\n')); },
      cancel() { cancelled = true; },
    }));
    await expect(consumeSse(response, () => { throw new Error('consumer failed'); })).rejects.toThrow('consumer failed');
    expect(response.body?.locked).toBe(false);
    expect(cancelled).toBe(true);
  });

  it('fast-fails when the response has no body', async () => {
    await expect(consumeSse(new Response(null), () => undefined)).rejects.toThrow('SSE response has no body');
  });
});
