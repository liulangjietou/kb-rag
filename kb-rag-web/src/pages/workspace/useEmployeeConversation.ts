import { useCallback, useEffect, useRef, useState, type SetStateAction } from 'react';
import { employeeWorkspace, EmployeeApiError, isActiveRun, subscribeEmployeeRun,
  type EmployeeConversation, type EmployeeRun } from '../../api/employeeWorkspace';

const NEWEST = 2147483647;
const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 16000];
const errorMessage = (error: unknown) => error instanceof Error ? error.message : '请求未完成，请重试';

/** 旧检查点与迟到的活动状态不能覆盖更新结果；撤权信息始终优先清除已有正文。 */
export function mergeEmployeeRun(previous: EmployeeRun | undefined, incoming: EmployeeRun): EmployeeRun {
  if (!previous) return incoming.restricted ? { ...incoming, answer: '', references: [] } : incoming;
  const keepPrevious = previous.revision > incoming.revision || (!isActiveRun(previous) && isActiveRun(incoming));
  const result = keepPrevious ? previous : incoming;
  // 本次页面生命周期内不自动恢复被撤权的内容；重新打开会话会从服务器重新取得授权视图。
  return previous.restricted || incoming.restricted ? { ...result, restricted: true, answer: '', references: [] } : result;
}

/** 会话摘要和回答必须一起更新，已知运行的终态与轮次优先于迟到的摘要。 */
function reconcileConversation(conversation: EmployeeConversation | undefined, runs: EmployeeRun[]): EmployeeConversation | undefined {
  if (!conversation) return conversation;
  const latest = runs.at(-1);
  if (latest && latest.turn_no >= conversation.last_turn) return { ...conversation, last_turn: latest.turn_no,
    active_run_id: isActiveRun(latest) ? latest.run_id : null };
  const active = runs.find((run) => run.run_id === conversation.active_run_id);
  return active && !isActiveRun(active) ? { ...conversation, active_run_id: null } : conversation;
}

function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const finish = () => { window.clearTimeout(timer); signal.removeEventListener('abort', finish); resolve(); };
    const timer = window.setTimeout(finish, milliseconds);
    signal.addEventListener('abort', finish, { once: true });
    if (signal.aborted) finish();
  });
}

/** 单个已存在会话的展示与命令状态；组件以应用/会话为 key，导航只回收读取资源。 */
export function useEmployeeConversation(appId: string, conversationId: string, onChanged: () => void) {
  const [{ conversation, runs }, setView] = useState<{ conversation?: EmployeeConversation; runs: EmployeeRun[] }>({ runs: [] });
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string>();
  const [commandError, setCommandError] = useState<string>();
  const [authorizationError, setAuthorizationError] = useState<EmployeeApiError>();
  const [sending, setSending] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [pending, setPending] = useState<{ requestId: string; query: string }>();
  const [cursor, setCursor] = useState(NEWEST);
  const [connection, setConnection] = useState<'idle' | 'connecting' | 'live' | 'retrying' | 'paused'>('idle');
  const [connectionMessage, setConnectionMessage] = useState<string>();
  const [connectionEpoch, setConnectionEpoch] = useState(0);
  const [visible, setVisible] = useState(document.visibilityState !== 'hidden');
  const alive = useRef(true);
  const submitLock = useRef(false);
  const stopLock = useRef(false);
  const requestSequence = useRef(0);
  const requestAbort = useRef<AbortController>();
  const authorizationBlocked = useRef(false);
  const authorizationEpoch = useRef(0);
  const cursorRef = useRef(cursor);
  cursorRef.current = cursor;

  const setConversation = useCallback((update: SetStateAction<EmployeeConversation | undefined>) => {
    setView((previous) => ({ ...previous, conversation: reconcileConversation(
      typeof update === 'function' ? update(previous.conversation) : update, previous.runs) }));
  }, []);

  const authorizeFailure = useCallback((error: unknown) => {
    if (error instanceof EmployeeApiError && error.clearContent) {
      authorizationBlocked.current = true;
      ++authorizationEpoch.current;
      setView({ runs: [] });
      setAuthorizationError(error);
    }
  }, []);

  const merge = useCallback((incoming: EmployeeRun) => {
    if (incoming.conversation_id !== conversationId || authorizationBlocked.current) return;
    setView((previous) => {
      const old = previous.runs.find((item) => item.run_id === incoming.run_id);
      const next = [...previous.runs.filter((item) => item.run_id !== incoming.run_id), mergeEmployeeRun(old, incoming)]
        .sort((left, right) => left.turn_no - right.turn_no);
      return { conversation: reconcileConversation(previous.conversation, next), runs: next };
    });
  }, [conversationId]);

  const refresh = useCallback(async (quiet = false) => {
    requestAbort.current?.abort();
    const abort = new AbortController();
    requestAbort.current = abort;
    const sequence = ++requestSequence.current;
    const accessEpoch = authorizationEpoch.current;
    if (!quiet) setLoading(true);
    try {
      const [summary, history] = await Promise.all([
        employeeWorkspace.conversation(appId, conversationId, abort.signal),
        employeeWorkspace.history(appId, conversationId, cursorRef.current, abort.signal),
      ]);
      if (abort.signal.aborted || !alive.current || sequence !== requestSequence.current || accessEpoch !== authorizationEpoch.current) return;
      authorizationBlocked.current = false;
      setView((previous) => {
        const latestReadTurn = Math.max(0, ...history.map((run) => run.turn_no));
        // 最近页读取期间新接受的回答不能丢失；翻阅更早页时则严格遵守服务器游标。
        const acceptedDuringRead = cursorRef.current === NEWEST ? previous.runs.filter((run) => run.turn_no > latestReadTurn) : [];
        const next = [...history.map((item) => mergeEmployeeRun(previous.runs.find((old) => old.run_id === item.run_id), item)), ...acceptedDuringRead]
          .sort((left, right) => left.turn_no - right.turn_no).slice(-20);
        return { conversation: reconcileConversation(summary, next), runs: next };
      });
      setLoadError(undefined);
      setAuthorizationError(undefined);
    } catch (error) {
      if (abort.signal.aborted || !alive.current || sequence !== requestSequence.current) return;
      authorizeFailure(error);
      setLoadError(errorMessage(error));
    } finally {
      if (!abort.signal.aborted && alive.current && sequence === requestSequence.current) setLoading(false);
    }
  }, [appId, conversationId, authorizeFailure]);

  const releaseReads = useCallback(() => {
    ++requestSequence.current;
    requestAbort.current?.abort();
  }, []);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; releaseReads(); };
  }, [releaseReads]);

  useEffect(() => { void refresh(); }, [cursor, refresh]);

  useEffect(() => {
    const revalidate = () => {
      const nextVisible = document.visibilityState !== 'hidden';
      setVisible(nextVisible);
      if (nextVisible) void refresh();
    };
    window.addEventListener('focus', revalidate);
    document.addEventListener('visibilitychange', revalidate);
    const timer = window.setInterval(() => { if (document.visibilityState !== 'hidden') void refresh(true); }, 15_000);
    return () => {
      window.removeEventListener('focus', revalidate);
      document.removeEventListener('visibilitychange', revalidate);
      window.clearInterval(timer);
    };
  }, [refresh]);

  const activeId = cursor === NEWEST ? conversation?.active_run_id : undefined;
  useEffect(() => {
    if (!activeId || !visible || authorizationError) { setConnection('idle'); return; }
    const abort = new AbortController();
    void (async () => {
      let failures = 0;
      while (!abort.signal.aborted) {
        const started = Date.now();
        let receivedSnapshot = false;
        setConnection(failures === 0 ? 'connecting' : 'retrying');
        try {
          await subscribeEmployeeRun(appId, conversationId, activeId, (snapshot) => {
            if (abort.signal.aborted || authorizationBlocked.current) return;
            receivedSnapshot = true;
            merge(snapshot);
            setConnection('live');
            setConnectionMessage(undefined);
            if (!isActiveRun(snapshot)) {
              setConversation((previous) => previous?.active_run_id === activeId ? { ...previous, active_run_id: null } : previous);
              onChanged();
            }
          }, abort.signal);
          return;
        } catch (error) {
          if (abort.signal.aborted) return;
          authorizeFailure(error);
          setConnectionMessage(errorMessage(error));
          if (receivedSnapshot && error instanceof EmployeeApiError && error.code !== 'STREAM_TIMEOUT'
            && Date.now() - started > 10_000) failures = 0;
          if (!(error instanceof EmployeeApiError) || !error.retryable || failures >= RECONNECT_DELAYS.length) {
            setConnection('paused');
            return;
          }
          setConnection('retrying');
          await delay(RECONNECT_DELAYS[failures++], abort.signal);
        }
      }
    })();
    return () => abort.abort();
  }, [appId, conversationId, activeId, visible, authorizationError, connectionEpoch, merge, onChanged, authorizeFailure, setConversation]);

  const submit = useCallback(async (query: string, retryPending = false): Promise<boolean> => {
    if (submitLock.current || authorizationError || (!retryPending && (conversation?.active_run_id || pending))) return false;
    const command = retryPending && pending ? pending : { requestId: crypto.randomUUID(), query };
    submitLock.current = true;
    setSending(true);
    setPending(command);
    setCommandError(undefined);
    try {
      const accepted = await employeeWorkspace.submit(appId, conversationId, command.requestId, command.query);
      if (!alive.current) return false;
      merge(accepted);
      setPending(undefined);
      setConversation((previous) => previous ? { ...previous, active_run_id: isActiveRun(accepted) ? accepted.run_id : null,
        last_turn: Math.max(previous.last_turn, accepted.turn_no) } : previous);
      onChanged();
      if (conversation?.last_turn === 0 && conversation.title === '新对话') {
        const title = Array.from(command.query.trim()).slice(0, 45).join('');
        employeeWorkspace.rename(appId, conversationId, title).then((renamed) => {
          if (alive.current) { setConversation((previous) => previous ? { ...previous, title: renamed.title } : previous); onChanged(); }
        }).catch(() => undefined);
      }
      return true;
    } catch (error) {
      if (!alive.current) return false;
      authorizeFailure(error);
      setCommandError(errorMessage(error));
      if (error instanceof EmployeeApiError && !error.retryable) setPending(undefined);
      if (error instanceof EmployeeApiError && error.code === 'CONVERSATION_BUSY') void refresh();
      return false;
    } finally {
      submitLock.current = false;
      if (alive.current) setSending(false);
    }
  }, [appId, conversationId, conversation, pending, authorizationError, authorizeFailure, merge, onChanged, refresh, setConversation]);

  const stop = useCallback(async () => {
    const runId = conversation?.active_run_id;
    if (!runId || stopLock.current) return;
    stopLock.current = true;
    setStopping(true);
    setCommandError(undefined);
    try {
      const stopped = await employeeWorkspace.stop(appId, conversationId, runId);
      if (!alive.current) return;
      merge(stopped);
      setConversation((previous) => previous?.active_run_id === runId ? { ...previous, active_run_id: null } : previous);
      onChanged();
    } catch (error) {
      if (alive.current) { authorizeFailure(error); setCommandError('停止结果尚未确认，请重试停止或重新读取状态'); }
    } finally {
      stopLock.current = false;
      if (alive.current) setStopping(false);
    }
  }, [appId, conversationId, conversation?.active_run_id, merge, onChanged, authorizeFailure, setConversation]);

  return { conversation, runs, loading, loadError, commandError, authorizationError, sending, stopping, pending,
    connection, connectionMessage, visible, refresh, submit, stop, setConversation, authorizeFailure,
    reconnect: () => setConnectionEpoch((value) => value + 1),
    earlier: () => { if (runs.length) setCursor(Math.min(...runs.map((run) => run.turn_no))); },
    newest: () => setCursor(NEWEST), isNewest: cursor === NEWEST, hasEarlier: runs.length === 20 && runs[0].turn_no > 1,
  };
}
