// Author: owlzhangfq@gmail.com

/** One parsed Server-Sent Event frame: an optional named event type plus its raw data payload. */
export interface SseEvent {
  event: string;
  data: string;
}

/**
 * Reads a fetch Response body as an SSE stream and invokes onEvent for every complete frame as
 * soon as it arrives (M4c-CONTRACTS.md section 3 external chat framing: message_delta* ->
 * references -> done -> 或 error). Frames are separated by a blank line; each frame may carry one
 * `event:` line (defaults to 'message' per the SSE spec when omitted) and one or more `data:`
 * lines joined by \n.
 */
export async function consumeSse(response: Response, onEvent: (evt: SseEvent) => void): Promise<void> {
  if (!response.body) {
    throw new Error('SSE response has no body');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    let separatorIndex = buffer.indexOf('\n\n');
    while (separatorIndex !== -1) {
      const rawFrame = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);
      const frame = parseFrame(rawFrame);
      if (frame) {
        onEvent(frame);
      }
      separatorIndex = buffer.indexOf('\n\n');
    }
  }
  const trailing = buffer.trim();
  if (trailing) {
    const frame = parseFrame(trailing);
    if (frame) {
      onEvent(frame);
    }
  }
}

function parseFrame(rawFrame: string): SseEvent | null {
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of rawFrame.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim());
    }
  }
  if (dataLines.length === 0) {
    return null;
  }
  return { event, data: dataLines.join('\n') };
}
