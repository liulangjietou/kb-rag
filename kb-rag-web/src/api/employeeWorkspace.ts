import { clearToken, getToken, SESSION_HEADER } from './authStorage';
import { consumeSse } from '../utils/sse';

export interface EmployeeApplication {
  app_id: string;
  name: string;
  description?: string;
  released_version_id: string;
  released_version: string;
}

export interface EmployeeConversation {
  conversation_id: string;
  app_id: string;
  title: string;
  last_turn: number;
  active_run_id?: string | null;
  last_activity_at: string;
  created_at: string;
}

export interface EmployeeHomeOverview {
  applications: EmployeeApplication[];
  recent_conversations: Array<Pick<EmployeeConversation, 'conversation_id' | 'app_id' | 'title' | 'active_run_id' | 'last_activity_at'> & { app_name: string }>;
}

export interface EmployeeCitation {
  doc_id: string;
  document_version_id: string;
  chunk_id: string;
  kb_id: string;
  file_name: string;
  document_version: string;
  document_updated_at?: string | null;
  version_created_at?: string | null;
  chunk_title?: string | null;
  page_no?: number | null;
  chunk_ordinal?: number | null;
  content: string;
  inherited: boolean;
}

export type EmployeeRunStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED';
export interface EmployeeRun {
  run_id: string;
  conversation_id: string;
  turn_no: number;
  question: string;
  answer: string;
  references: EmployeeCitation[];
  status: EmployeeRunStatus;
  stage: 'QUEUED' | 'RETRIEVING' | 'GENERATING' | 'FINISHED';
  app_version_id: string;
  app_version: string;
  snapshot_bound: boolean;
  revision: number;
  checkpoint_seq: number;
  degraded: boolean;
  error_code?: string | null;
  error_message?: string | null;
  restricted: boolean;
  created_at: string;
  finished_at?: string | null;
}

export interface ConversationPage {
  items: EmployeeConversation[];
  page: number;
  size: number;
  total: number;
}

/** 员工界面以内联状态处理失败，后台重连不产生连续全局提示。 */
export class EmployeeApiError extends Error {
  readonly code: string;
  readonly retryable: boolean;
  readonly clearContent: boolean;

  constructor(code: string, message: string, retryable = false, clearContent = false) {
    super(message);
    this.name = 'EmployeeApiError';
    this.code = code;
    this.retryable = retryable;
    this.clearContent = clearContent;
  }
}

export function isActiveRun(run: EmployeeRun): boolean {
  return run.status === 'PENDING' || run.status === 'RUNNING';
}

const conversationPath = (appId: string, conversationId?: string) =>
  `/workspace/apps/${encodeURIComponent(appId)}/conversations${conversationId ? `/${encodeURIComponent(conversationId)}` : ''}`;
const runPath = (appId: string, conversationId: string, runId?: string) =>
  `${conversationPath(appId, conversationId)}/runs${runId ? `/${encodeURIComponent(runId)}` : ''}`;
// 服务端订阅最长 120 秒；额外预留传输时间，避免失联连接无限占用读取状态。
const SUBSCRIPTION_DEADLINE_MS = 135_000;

function headers(accept = 'application/json'): Record<string, string> {
  return { Accept: accept, 'Content-Type': 'application/json', [SESSION_HEADER]: getToken() ?? '' };
}

function apiError(body: unknown, status: number): EmployeeApiError {
  const data = body && typeof body === 'object' ? body as Record<string, unknown> : {};
  const code = typeof data.code === 'string' ? data.code : String(status);
  const revoked = [401, 403, 404].includes(status) || ['UNAUTHORIZED', 'FORBIDDEN', 'NOT_FOUND'].includes(code);
  if (status === 401 || code === 'UNAUTHORIZED') clearToken();
  return new EmployeeApiError(code, typeof data.message === 'string' ? data.message : '请求暂时无法完成，请重试',
    !revoked && (status >= 500 || status === 429), revoked);
}

async function request<T>(path: string, method: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const cancellation = new AbortController();
  const abort = () => cancellation.abort();
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) cancellation.abort();
  const timeout = window.setTimeout(abort, 30_000);
  try {
    const response = await fetch(`/api/v1${path}`, { method, headers: headers(), cache: 'no-store',
      body: body === undefined ? undefined : JSON.stringify(body), signal: cancellation.signal });
    const result = await response.json().catch(() => null);
    if (!response.ok || result?.code !== 'OK') throw apiError(result, response.status);
    return result.data as T;
  } catch (error) {
    if (signal?.aborted || error instanceof EmployeeApiError) throw error;
    throw new EmployeeApiError('NETWORK_ERROR', '连接暂时不可用，请重试。已提交的问题不会自动重复发送', true);
  } finally {
    window.clearTimeout(timeout);
    signal?.removeEventListener('abort', abort);
  }
}

export const employeeWorkspace = {
  overview: (signal?: AbortSignal) => request<EmployeeHomeOverview>('/workspace/overview', 'GET', undefined, signal),
  applications: (signal?: AbortSignal) => request<EmployeeApplication[]>('/workspace/apps', 'GET', undefined, signal),
  conversations: (appId: string, keyword = '', page = 1, signal?: AbortSignal) =>
    request<ConversationPage>(`${conversationPath(appId)}?${new URLSearchParams({ keyword, page: String(page), size: '20' })}`, 'GET', undefined, signal),
  create: (appId: string, title: string) => request<EmployeeConversation>(conversationPath(appId), 'POST', { title }),
  conversation: (appId: string, id: string, signal?: AbortSignal) =>
    request<EmployeeConversation>(conversationPath(appId, id), 'GET', undefined, signal),
  rename: (appId: string, id: string, title: string) => request<EmployeeConversation>(conversationPath(appId, id), 'PATCH', { title }),
  delete: (appId: string, id: string) => request<void>(conversationPath(appId, id), 'DELETE'),
  history: (appId: string, id: string, beforeTurn = 2147483647, signal?: AbortSignal) =>
    request<EmployeeRun[]>(`${runPath(appId, id)}?before_turn=${beforeTurn}&limit=20`, 'GET', undefined, signal),
  submit: (appId: string, id: string, requestId: string, query: string) =>
    request<EmployeeRun>(runPath(appId, id), 'POST', { request_id: requestId, query }),
  run: (appId: string, id: string, runId: string, signal?: AbortSignal) =>
    request<EmployeeRun>(runPath(appId, id, runId), 'GET', undefined, signal),
  stop: (appId: string, id: string, runId: string) => request<EmployeeRun>(`${runPath(appId, id, runId)}/stop`, 'POST'),
};

/** 一次 GET 订阅只读取既有运行；调用方负责有界退避，绝不在此重发问题。 */
export async function subscribeEmployeeRun(appId: string, conversationId: string, runId: string,
  onSnapshot: (run: EmployeeRun) => void, signal: AbortSignal): Promise<void> {
  let terminal = false;
  let timedOut = false;
  const connection = new AbortController();
  const cancel = () => connection.abort();
  signal.addEventListener('abort', cancel, { once: true });
  if (signal.aborted) cancel();
  const deadline = window.setTimeout(() => { timedOut = true; connection.abort(); }, SUBSCRIPTION_DEADLINE_MS);
  try {
    const response = await fetch(`/api/v1${runPath(appId, conversationId, runId)}/events`, {
      method: 'GET', headers: headers('text/event-stream'), cache: 'no-store', signal: connection.signal,
    });
    if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream')) {
      throw apiError(await response.json().catch(() => null), response.status);
    }
    await consumeSse(response, (event) => {
      if (signal.aborted) return false;
      if (!['snapshot', 'done', 'error'].includes(event.event)) return;
      const data = JSON.parse(event.data);
      if (event.event === 'error') {
        throw new EmployeeApiError(data.code ?? 'STREAM_ERROR', data.message ?? '回答连接中断',
          data.retryable === true, data.clear_content === true);
      }
      if (event.event === 'done') {
        // 成功或其他终态快照本身已证明落库；缺少快照时通过重连读取，不猜测答案内容。
        if (!terminal) throw new EmployeeApiError('STREAM_INCOMPLETE', '正在重新读取回答状态', true);
        return false;
      }
      if (!validRun(data) || data.run_id !== runId || data.conversation_id !== conversationId) {
        throw new EmployeeApiError('INVALID_SNAPSHOT', '回答状态格式不完整，请重新连接', true);
      }
      onSnapshot(data);
      terminal = !isActiveRun(data);
      if (terminal) return false;
    });
    if (!terminal && !signal.aborted) throw new EmployeeApiError('STREAM_INCOMPLETE', '连接已结束，正在恢复同一回答', true);
  } catch (error) {
    if (signal.aborted) return;
    if (timedOut) throw new EmployeeApiError('STREAM_TIMEOUT', '连接等待超时，正在重新读取同一回答', true);
    if (error instanceof EmployeeApiError) throw error;
    throw new EmployeeApiError('NETWORK_ERROR', '回答连接中断，正在恢复同一回答', true);
  } finally {
    window.clearTimeout(deadline);
    signal.removeEventListener('abort', cancel);
  }
}

function validRun(value: unknown): value is EmployeeRun {
  if (!value || typeof value !== 'object') return false;
  const run = value as EmployeeRun;
  return typeof run.run_id === 'string' && typeof run.conversation_id === 'string'
    && typeof run.question === 'string' && typeof run.answer === 'string' && Array.isArray(run.references)
    && Number.isInteger(run.revision) && Number.isInteger(run.turn_no) && Number.isInteger(run.checkpoint_seq)
    && typeof run.restricted === 'boolean' && typeof run.snapshot_bound === 'boolean'
    && ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(run.status);
}
