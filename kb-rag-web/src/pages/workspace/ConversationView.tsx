import { ArrowDownOutlined, BookOutlined, DeleteOutlined, EditOutlined, HistoryOutlined, SendOutlined, StopOutlined } from '@ant-design/icons';
import { Alert, Button, Drawer, Input, Modal, Skeleton, Tag } from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import { memo, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { employeeWorkspace, isActiveRun, type EmployeeApiError, type EmployeeCitation, type EmployeeRun, type AnswerFeedbackVerdict } from '../../api/employeeWorkspace';
import AnswerMarkdown, { CopyTextButton } from './AnswerMarkdown';
import AnswerFeedbackControls, { AnswerFeedbackDialog } from './AnswerFeedbackControls';
import { useEmployeeConversation } from './useEmployeeConversation';

const STATUS: Record<EmployeeRun['status'], string> = {
  PENDING: '等待执行', RUNNING: '正在回答', SUCCEEDED: '已保存', FAILED: '回答未完成', CANCELLED: '已停止', INTERRUPTED: '运行已中断',
};
const stageLabel = (run: EmployeeRun) => run.status === 'RUNNING'
  ? run.stage === 'RETRIEVING' ? '正在查找资料' : '正在生成回答' : STATUS[run.status];

export default function ConversationView({ appId, conversationId, applicationName, onChanged, onDeleted, onHistory, onAuthorizationError }: {
  appId: string; conversationId: string; applicationName: string; onChanged: () => void; onDeleted: () => void;
  onHistory: () => void; onAuthorizationError: (error: EmployeeApiError) => void;
}) {
  const state = useEmployeeConversation(appId, conversationId, onChanged);
  const { refresh } = state;
  const [draft, setDraft] = useState('');
  const [reference, setReference] = useState<{ runId: string; index: number }>();
  const [evidenceRunId, setEvidenceRunId] = useState<string>();
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const [renameOpen, setRenameOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [title, setTitle] = useState('');
  const [actionBusy, setActionBusy] = useState(false);
  const [actionError, setActionError] = useState<string>();
  const [feedbackRun, setFeedbackRun] = useState<EmployeeRun>();
  const [hasNewContent, setHasNewContent] = useState(false);
  const input = useRef<TextAreaRef>(null);
  const scroll = useRef<HTMLDivElement>(null);
  const nearBottom = useRef(true);
  const composing = useRef(false);
  const mounted = useRef(true);
  const initialFocusHandled = useRef(false);
  const scrollTopAfterLoad = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  useEffect(() => {
    if (initialFocusHandled.current || state.loading || !state.conversation || state.authorizationError) return;
    initialFocusHandled.current = true;
    // 桌面新会话在数据就绪后可直接输入；历史阅读和手机不自动弹出输入焦点。
    if (!state.runs.length && window.matchMedia('(min-width: 768px)').matches) input.current?.focus();
  }, [state.loading, state.conversation, state.authorizationError, state.runs.length]);
  useEffect(() => {
    if (!state.authorizationError) return;
    setRenameOpen(false);
    setDeleteOpen(false);
    onAuthorizationError(state.authorizationError);
  }, [state.authorizationError, onAuthorizationError]);

  const signature = state.runs.map((run) => `${run.run_id}:${run.answer.length}:${run.status}:${run.restricted}`).join('|');
  const scrollToLatest = useCallback(() => {
    if (scroll.current) scroll.current.scrollTop = scroll.current.scrollHeight;
    nearBottom.current = true;
    setHasNewContent(false);
  }, []);
  useLayoutEffect(() => {
    if (state.loading) return;
    if (scrollTopAfterLoad.current) {
      if (scroll.current) scroll.current.scrollTop = 0;
      scrollTopAfterLoad.current = false;
      return;
    }
    if (nearBottom.current) scrollToLatest();
    else setHasNewContent(true);
  }, [signature, state.loading, scrollToLatest]);

  const send = async (query: string, fromComposer = true) => {
    if (!query.trim() || query.length > 8000) return;
    nearBottom.current = true;
    if (await state.submit(query)) {
      if (fromComposer) setDraft('');
      input.current?.focus();
    }
  };
  const openReference = useCallback((runId: string, index: number) => {
    setReference({ runId, index });
    setEvidenceRunId(runId);
    void refresh();
  }, [refresh]);
  const evidenceRun = state.runs.find((run) => run.run_id === evidenceRunId) ?? state.runs.at(-1);
  const selectedRun = state.runs.find((run) => run.run_id === reference?.runId);
  const selectedReference = !selectedRun?.restricted && reference ? selectedRun?.references[reference.index] : undefined;
  const currentFeedbackRun = state.runs.find((run) => run.run_id === feedbackRun?.run_id);
  useEffect(() => {
    if (state.authorizationError || currentFeedbackRun?.restricted
      || (!state.loading && !state.loadError && feedbackRun && !currentFeedbackRun)) setFeedbackRun(undefined);
  }, [state.authorizationError, state.loading, state.loadError, currentFeedbackRun, feedbackRun]);
  const active = Boolean(state.conversation?.active_run_id);
  const blocked = state.loading || !!state.authorizationError || !state.conversation;
  // 权限清理仍由会话状态负责；页面按错误码表达可执行的下一步，不展示后端权限表达式。
  const authorizationMessage = state.authorizationError
    ? ['UNAUTHORIZED', '401'].includes(state.authorizationError.code) ? '登录已失效，请重新登录。'
      : ['NOT_FOUND', '404'].includes(state.authorizationError.code) ? '这段会话已不存在或当前不可访问，请从会话历史中重新选择。'
        : '当前已无权访问这段会话，回答与引用已隐藏。权限恢复后可重新读取。'
    : undefined;
  const conversationTitle = authorizationMessage ? '会话不可访问' : state.conversation?.title
    ?? (state.loading ? '正在读取会话' : state.loadError ? '会话读取失败' : '会话尚未就绪');

  const saveTitle = async () => {
    if (!title.trim() || actionBusy) return;
    setActionBusy(true);
    setActionError(undefined);
    try {
      const changed = await employeeWorkspace.rename(appId, conversationId, title.trim());
      if (mounted.current) {
        state.setConversation((previous) => previous ? { ...previous, title: changed.title } : changed);
        setRenameOpen(false);
        onChanged();
      }
    } catch (error) {
      if (mounted.current) { state.authorizeFailure(error); setActionError(error instanceof Error ? error.message : '标题保存失败，请重试'); }
    }
    finally { if (mounted.current) setActionBusy(false); }
  };
  const deleteConversation = async () => {
    if (actionBusy) return;
    setActionBusy(true);
    setActionError(undefined);
    try { await employeeWorkspace.delete(appId, conversationId); if (mounted.current) onDeleted(); }
    catch (error) {
      if (mounted.current) { state.authorizeFailure(error); setActionError(error instanceof Error ? error.message : '删除失败，请重试'); }
    }
    finally { if (mounted.current) setActionBusy(false); }
  };

  const evidence = <div className="conversation-evidence">
    <div className="conversation-evidence__heading"><BookOutlined /><h2>回答依据</h2></div>
    <p>点击来源，核对本次引用片段。</p>
    {authorizationMessage ? <Alert type="info" message={authorizationMessage} />
      : state.loading ? <Skeleton active paragraph={{ rows: 4 }} /> : state.loadError ? <Alert type="warning" message="暂时无法重新核验引用"
      action={<Button size="small" onClick={() => void state.refresh()}>重试</Button>} /> : evidenceRun?.restricted
      ? <Alert type="info" message="资料权限或状态已变化，该回答与引用已隐藏" />
      : evidenceRun?.references.length ? evidenceRun.references.map((source, index) => <button
        className="evidence-card" key={`${source.chunk_id}:${index}`} type="button" onClick={() => openReference(evidenceRun.run_id, index)}>
        <span className="evidence-card__number">{source.inherited ? '上下文来源' : `引用 ${index + 1}`}</span>
        <strong>{source.file_name}</strong><small>{sourceLocator(source)}</small><p>{source.content}</p>
      </button>) : <div className="evidence-empty"><BookOutlined /><strong>{evidenceRun && !isActiveRun(evidenceRun) ? '本次没有可展示的来源' : '依据会随回答一起出现'}</strong>
        <span>仅展示本次实际读取且当前可访问的资料。</span></div>}
  </div>;

  return <>
    <section className="conversation-pane" aria-label="当前会话">
      <header className="conversation-header">
        <Button type="text" className="workspace-mobile-history" icon={<HistoryOutlined />} aria-label="打开会话历史" onClick={onHistory} />
        <div className="conversation-header__title"><h2>{conversationTitle}</h2><span>{applicationName}</span></div>
        <div className="conversation-header__actions">
          <Button type="text" icon={<EditOutlined />} aria-label="重命名会话" disabled={blocked}
            onClick={() => { setTitle(state.conversation?.title ?? ''); setActionError(undefined); setRenameOpen(true); }} />
          <Button type="text" icon={<DeleteOutlined />} aria-label="删除会话" disabled={blocked}
            onClick={() => { setActionError(undefined); setDeleteOpen(true); }} />
          <Button type="text" className="workspace-mobile-evidence" icon={<BookOutlined />} aria-label="打开回答依据" disabled={blocked}
            onClick={() => setEvidenceOpen(true)} />
        </div>
      </header>
      {(authorizationMessage || state.loadError) && <Alert className="conversation-banner" type="error" message={authorizationMessage ?? state.loadError}
        action={<Button size="small" onClick={() => void state.refresh()}>重新读取</Button>} />}
      {['retrying', 'paused'].includes(state.connection) && <Alert className="conversation-banner" type="warning" showIcon
        message={state.connection === 'retrying' ? '连接中断，正在恢复同一回答' : '暂时无法连接，后台任务可能仍在继续'}
        action={<Button size="small" onClick={state.reconnect}>重新连接</Button>} />}
      <div className="conversation-scroll" ref={scroll} onScroll={() => {
        const region = scroll.current;
        if (region) nearBottom.current = region.scrollHeight - region.scrollTop - region.clientHeight < 72;
        if (nearBottom.current) setHasNewContent(false);
      }}>
        {state.loading || !state.visible ? <Skeleton active paragraph={{ rows: 6 }} /> : <>
          <div className="conversation-history-controls">
            {state.hasEarlier && <Button type="text" size="small" onClick={() => {
              nearBottom.current = false; scrollTopAfterLoad.current = true; state.earlier();
            }}>更早记录</Button>}
            {!state.isNewest && <Button type="text" size="small" onClick={() => { nearBottom.current = true; state.newest(); }}>返回最近问答</Button>}
          </div>
          {!state.runs.length && !state.loadError && <div className="conversation-empty"><BookOutlined /><h3>这次想了解什么？</h3>
            <p>描述你的问题，必要时补充背景。回答中的引用可以直接打开核对。</p></div>}
          {state.runs.map((run) => <RunMessage key={run.run_id} run={run} applicationName={applicationName}
            onFeedback={state.feedback} onEditFeedback={setFeedbackRun}
            onReference={openReference} onRetry={(question) => void send(question, false)} canRetry={!blocked && !active && !state.pending && !state.sending}
            onEvidence={() => { setEvidenceRunId(run.run_id); setEvidenceOpen(true); }} />)}
        </>}
      </div>
      {hasNewContent && !blocked && <button type="button" className="conversation-new-content" onClick={scrollToLatest}><ArrowDownOutlined /> 回到最新回答</button>}
      <div className="conversation-composer">
        {state.commandError && !authorizationMessage && <Alert type="error" message={state.commandError} action={state.pending && !state.sending
          ? <Button size="small" onClick={async () => { if (await state.submit(state.pending!.query, true)) setDraft(''); }}>确认发送结果</Button> : undefined} />}
        {!state.isNewest ? <Button block onClick={() => { nearBottom.current = true; state.newest(); }}>返回最近问答后继续提问</Button> : <>
          <Input.TextArea ref={input} aria-label="输入知识问题" placeholder="输入问题，或继续追问…" value={draft} maxLength={8000}
            autoSize={{ minRows: 2, maxRows: 5 }} disabled={blocked || state.sending || !!state.pending}
            onChange={(event) => setDraft(event.target.value)}
            onCompositionStart={() => { composing.current = true; }} onCompositionEnd={() => { composing.current = false; }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.keyCode !== 229 && !composing.current && !active) {
                event.preventDefault(); void send(draft);
              }
            }} />
          <div className="conversation-composer__footer"><span>{state.pending && !state.sending ? '发送结果待确认，请勿重复提问'
            : active ? '离开页面后仍会继续，停止需点击右侧按钮' : 'Enter 发送 · Shift + Enter 换行'}</span>
            {active ? <Button icon={<StopOutlined />} loading={state.stopping} onClick={() => void state.stop()}>停止回答</Button>
              : <Button type="primary" icon={<SendOutlined />} loading={state.sending} disabled={blocked || !draft.trim() || !!state.pending}
                onClick={() => void send(draft)}>发送</Button>}
          </div>
        </>}
      </div>
    </section>
    <aside className="workspace-evidence" aria-label="当前回答依据">{evidence}</aside>
    {feedbackRun && <AnswerFeedbackDialog key={feedbackRun.run_id} initialRun={feedbackRun}
      currentRun={currentFeedbackRun ?? feedbackRun} onSave={state.feedback}
      visible={state.visible && !state.loading && !state.loadError && !!currentFeedbackRun && !currentFeedbackRun.restricted && !state.authorizationError}
      onClose={() => {
        const triggerId = `answer-feedback-${feedbackRun.run_id}`;
        setFeedbackRun(undefined);
        requestAnimationFrame(() => document.getElementById(triggerId)?.focus());
      }} />}
    <Drawer title="回答依据" open={evidenceOpen} width={360} onClose={() => setEvidenceOpen(false)} destroyOnClose>{evidence}</Drawer>
    <Drawer title={selectedReference?.file_name ?? '引用资料'} open={!!reference} width={520} onClose={() => setReference(undefined)} destroyOnClose>
      {authorizationMessage ? <Alert type="info" message={authorizationMessage} />
        : state.loading ? <Skeleton active paragraph={{ rows: 6 }} /> : state.loadError ? <Alert type="error" message="暂时无法核验这条引用，请重试"
        action={<Button onClick={() => void state.refresh()}>重新核验</Button>} /> : selectedReference ? <div className="reference-reader">
          <Tag>{selectedReference.inherited ? '上下文来源' : `引用 ${reference!.index + 1}`}</Tag><h2>{selectedReference.file_name}</h2>
          <dl><dt>文档版本</dt><dd>{selectedReference.document_version}</dd><dt>定位</dt><dd>{sourceLocator(selectedReference)}</dd>
            {selectedReference.document_updated_at && <><dt>文档更新时间</dt><dd>{selectedReference.document_updated_at.replace('T', ' ')}</dd></>}
            {selectedReference.chunk_title && <><dt>片段标题</dt><dd>{selectedReference.chunk_title}</dd></>}
          </dl><h3>本次引用片段</h3><blockquote>{selectedReference.content}</blockquote>
          <CopyTextButton text={selectedReference.content} label="复制引用片段" />
        </div> : <Alert type="info" message="这条引用当前已不可访问" />}
    </Drawer>
    <Modal title="重命名会话" open={renameOpen} onCancel={() => setRenameOpen(false)} onOk={() => void saveTitle()} okText="保存" cancelText="取消"
      confirmLoading={actionBusy} closable={!actionBusy} maskClosable={!actionBusy} keyboard={!actionBusy}
      cancelButtonProps={{ disabled: actionBusy }} okButtonProps={{ disabled: !title.trim() }} destroyOnHidden>
      <Input aria-label="会话标题" value={title} maxLength={120} showCount disabled={actionBusy} onChange={(event) => setTitle(event.target.value)}
        onPressEnter={() => void saveTitle()} />{actionError && <Alert type="error" message={actionError} />}
    </Modal>
    <Modal title="删除这段会话？" open={deleteOpen} onCancel={() => setDeleteOpen(false)} onOk={() => void deleteConversation()}
      okText="删除会话" cancelText="保留会话" confirmLoading={actionBusy} closable={!actionBusy} maskClosable={!actionBusy} keyboard={!actionBusy}
      cancelButtonProps={{ disabled: actionBusy }} okButtonProps={{ danger: true }} destroyOnHidden>
      <p>删除后会话将从历史中移除，正在执行的回答也会停止。</p>{actionError && <Alert type="error" message={actionError} />}
    </Modal>
  </>;
}

function sourceLocator(source: EmployeeCitation): string {
  const location = source.page_no ? `第 ${source.page_no} 页` : source.chunk_ordinal ? `片段 ${source.chunk_ordinal}` : '引用片段';
  return `${location} · 版本 ${source.document_version}`;
}

const RunMessage = memo(function RunMessage({ run, applicationName, onReference, onRetry, canRetry, onEvidence, onFeedback, onEditFeedback }: {
  run: EmployeeRun; applicationName: string; onReference: (runId: string, index: number) => void;
  onRetry: (question: string) => void; canRetry: boolean; onEvidence: () => void;
  onFeedback: (run: EmployeeRun, verdict: AnswerFeedbackVerdict, note?: string) => Promise<boolean>;
  onEditFeedback: (run: EmployeeRun) => void;
}) {
  const citation = useCallback((index: number) => onReference(run.run_id, index), [onReference, run.run_id]);
  return <article className="conversation-turn" aria-label={`第 ${run.turn_no} 轮问答`}>
    <div className="conversation-question"><span>你</span><p>{run.question}</p></div>
    <div className="conversation-answer" aria-busy={isActiveRun(run)}>
      <header><strong>{applicationName}</strong><span className={`run-status run-status--${run.status.toLowerCase()}`}>{stageLabel(run)}</span></header>
      <div className="conversation-version">正式版 {run.app_version} · {run.snapshot_bound ? '发布时知识快照' : '当前知识资料'}</div>
      {run.restricted ? <Alert type="info" message="资料权限或状态已变化，该回答和引用已隐藏" /> : run.answer
        ? <AnswerMarkdown content={run.answer} citationCount={run.references.filter((source) => !source.inherited).length} onCitation={citation} />
        : isActiveRun(run) ? <div className="answer-waiting" role="status"><span />{stageLabel(run)}…</div> : <p className="conversation-muted">本次没有生成可展示的回答。</p>}
      {!isActiveRun(run) && run.status !== 'SUCCEEDED' && <Alert type={run.status === 'FAILED' ? 'warning' : 'info'}
        message={run.error_message || (run.status === 'INTERRUPTED' ? '执行进程已中断，已保留保存过的内容。可重新发起一次问答。' : '已停止，保存过的内容会继续保留。')} />}
      {run.degraded && <p className="conversation-muted">本次使用了部分可用检索能力，请结合来源核对回答。</p>}
      <footer>{run.answer && !run.restricted && <CopyTextButton text={run.answer} label="复制回答" />}
        {run.status === 'SUCCEEDED' && !run.restricted && <AnswerFeedbackControls run={run} onSave={onFeedback} onEdit={() => onEditFeedback(run)} />}
        {!!run.references.length && !run.restricted && <button type="button" className="workspace-text-action" onClick={onEvidence}><BookOutlined /> 查看依据</button>}
        {!isActiveRun(run) && <button type="button" className="workspace-text-action" disabled={!canRetry} onClick={() => onRetry(run.question)}>重新生成</button>}
      </footer>
    </div>
  </article>;
});
