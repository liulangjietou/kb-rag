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

  it('joins multiline data and consumes a trailing frame', async () => {
    const events: SseEvent[] = [];

    await consumeSse(streamingResponse(['data: first\ndata: second']), (event) => events.push(event));

    expect(events).toEqual([{ event: 'message', data: 'first\nsecond' }]);
  });

  it('fast-fails when the response has no body', async () => {
    await expect(consumeSse(new Response(null), () => undefined)).rejects.toThrow('SSE response has no body');
  });
});
