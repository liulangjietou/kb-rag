// Author: owlzhangfq@gmail.com

/** 一条完整 SSE 事件，包含事件名和原始数据。 */
export interface SseEvent {
  event: string;
  data: string;
}

/**
 * 按 SSE 协议读取完整事件，支持 LF、CRLF、CR 及跨网络块的 UTF-8 字符。
 * 消费方返回 false 时主动结束读取；EOF 未以空行结束的事件必须丢弃，业务终态由调用方判断。
 */
export async function consumeSse(response: Response, onEvent: (evt: SseEvent) => unknown): Promise<void> {
  if (!response.body) {
    throw new Error('SSE response has no body');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let line = '';
  let lines: string[] = [];
  let skipLf = false;
  let ended = false;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      ended = done;
      const text = decoder.decode(value, { stream: !done });
      for (const character of text) {
        // CR 已结束上一行；紧邻的 LF 即使落在下一个网络块也不能再产生空行。
        if (skipLf && character === '\n') {
          skipLf = false;
          continue;
        }
        skipLf = character === '\r';
        if (character !== '\r' && character !== '\n') {
          line += character;
          continue;
        }
        if (line === '') {
          const frame = parseFrame(lines);
          lines = [];
          if (frame && onEvent(frame) === false) return;
        } else {
          lines.push(line);
          line = '';
        }
      }
      if (done) return;
    }
  } finally {
    try {
      if (!ended) await reader.cancel();
    } finally {
      reader.releaseLock();
    }
  }
}

function parseFrame(lines: string[]): SseEvent | null {
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of lines) {
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    const rawValue = colon === -1 ? '' : line.slice(colon + 1);
    // 协议只移除冒号后的一个空格，正文的缩进和末尾空格属于数据。
    const value = rawValue.startsWith(' ') ? rawValue.slice(1) : rawValue;
    if (field === 'event') event = value || 'message';
    else if (field === 'data') dataLines.push(value);
  }
  if (dataLines.length === 0) {
    return null;
  }
  return { event, data: dataLines.join('\n') };
}
