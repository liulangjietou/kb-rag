import { MessageOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, Drawer, Empty, Input, Select, Skeleton } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { clearToken } from '../../api/authStorage';
import { employeeWorkspace, EmployeeApiError, type ConversationPage, type EmployeeApplication } from '../../api/employeeWorkspace';
import { useAuth } from '../../auth/AuthContext';
import ConversationView from './ConversationView';
import './workspace.css';

const messageFor = (error: unknown) => error instanceof Error ? error.message : '暂时无法读取，请重试';

/** 应用与会话标识保留在地址中；正文和引用每次都从当前授权接口获取。 */
export default function EmployeeWorkspacePage() {
  const [params, setParams] = useSearchParams();
  const appId = params.get('app') ?? '';
  const conversationId = params.get('conversation') ?? '';
  const [applications, setApplications] = useState<EmployeeApplication[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [createError, setCreateError] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [catalogEpoch, setCatalogEpoch] = useState(0);
  const [listEpoch, setListEpoch] = useState(0);
  const mounted = useRef(true);
  const creationLock = useRef(false);
  const locationScope = `${appId}:${conversationId}`;
  const currentScope = useRef(locationScope);
  currentScope.current = locationScope;
  const { logout } = useAuth();
  const navigate = useNavigate();
  const changed = useCallback(() => setListEpoch((value) => value + 1), []);
  const onAuthorizationError = useCallback((failure: EmployeeApiError) => {
    if (failure.code === 'UNAUTHORIZED' || failure.code === '401') {
      clearToken();
      logout();
      navigate('/login', { replace: true });
    }
  }, [logout, navigate]);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  useEffect(() => { setCreateError(undefined); }, [locationScope]);

  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    setError(undefined);
    employeeWorkspace.applications(abort.signal).then((items) => {
      if (!abort.signal.aborted) setApplications(items);
    }).catch((failure: unknown) => {
      if (!abort.signal.aborted) {
        setError(messageFor(failure));
        if (failure instanceof EmployeeApiError) onAuthorizationError(failure);
      }
    }).finally(() => { if (!abort.signal.aborted) setLoading(false); });
    return () => abort.abort();
  }, [catalogEpoch, onAuthorizationError]);

  useEffect(() => {
    if (!appId && applications.length) setParams({ app: applications[0].app_id }, { replace: true });
  }, [appId, applications, setParams]);

  const selectConversation = useCallback((id: string) => {
    setParams({ app: appId, ...(id ? { conversation: id } : {}) });
    setHistoryOpen(false);
  }, [appId, setParams]);

  const create = async () => {
    if (creationLock.current || !appId) return;
    creationLock.current = true;
    setCreating(true);
    setCreateError(undefined);
    try {
      const item = await employeeWorkspace.create(appId, '新对话');
      if (!mounted.current || currentScope.current !== locationScope) return;
      setParams({ app: appId, conversation: item.conversation_id });
      setHistoryOpen(false);
      changed();
    } catch (failure) {
      if (!mounted.current || currentScope.current !== locationScope) return;
      setCreateError(failure instanceof EmployeeApiError && failure.retryable
        ? '新建结果尚未确认，请刷新会话列表核对后再新建。' : messageFor(failure));
      if (failure instanceof EmployeeApiError) onAuthorizationError(failure);
    } finally { creationLock.current = false; if (mounted.current) setCreating(false); }
  };

  const selectedApp = applications.find((item) => item.app_id === appId);
  const options = applications.map((item) => ({ value: item.app_id, label: item.name }));
  if (appId && !selectedApp && conversationId) options.push({ value: appId, label: '历史应用会话' });
  const history = <ConversationHistory key={appId} appId={appId} selectedId={conversationId} epoch={listEpoch}
    onSelect={selectConversation} onAuthorizationError={onAuthorizationError} />;

  return <section className="employee-workspace" aria-labelledby="workspace-title">
    <header className="workspace-heading">
      <div><span className="workspace-eyebrow">企业知识助手</span><h1 id="workspace-title">知识问答</h1>
        <p>从资料中获取回答，随时核对依据。</p></div>
      <div className="workspace-heading__actions">
        <Select aria-label="选择问答应用" placeholder="选择应用" value={appId || undefined} options={options}
          loading={loading} disabled={creating || loading || options.length === 0}
          onChange={(value) => setParams({ app: value })} className="workspace-app-select" />
        <Button type="primary" icon={<PlusOutlined />} loading={creating} disabled={!selectedApp || loading} onClick={() => void create()}>新对话</Button>
      </div>
    </header>
    {error && <Alert type="error" showIcon message={error} className="workspace-alert"
      action={<Button size="small" onClick={() => setCatalogEpoch((value) => value + 1)}>重新读取应用</Button>} />}
    {createError && <Alert type="warning" showIcon message={createError} className="workspace-alert"
      action={<Button size="small" onClick={changed}>刷新会话列表</Button>} />}
    {loading ? <div className="workspace-loading"><Skeleton active paragraph={{ rows: 7 }} /></div>
      : !appId || (!selectedApp && !conversationId) ? <div className="workspace-welcome">
        <MessageOutlined /><h2>还没有可使用的正式应用</h2><p>管理员发布应用并为你分配使用范围后，就可以从这里开始提问。</p>
        <Button icon={<ReloadOutlined />} onClick={() => setCatalogEpoch((value) => value + 1)}>重新读取应用</Button>
      </div> : <div className="workspace-layout">
        <aside className="workspace-history" aria-label="会话历史">{history}</aside>
        {conversationId ? <ConversationView key={`${appId}:${conversationId}`} appId={appId} conversationId={conversationId}
          applicationName={selectedApp?.name ?? '知识助手'} onChanged={changed}
          onDeleted={() => { selectConversation(''); changed(); }} onHistory={() => setHistoryOpen(true)}
          onAuthorizationError={onAuthorizationError} />
          : <div className="workspace-welcome workspace-welcome--conversation">
            <MessageOutlined /><h2>从一个具体问题开始</h2><p>{selectedApp?.description || '选择一段工作中的疑问，让回答和资料来源一起呈现。'}</p>
            <Button type="primary" icon={<PlusOutlined />} loading={creating} onClick={() => void create()}>开始新对话</Button>
            <Button className="workspace-mobile-history" onClick={() => setHistoryOpen(true)}>查看历史会话</Button>
          </div>}
      </div>}
    <Drawer title="会话历史" open={historyOpen} placement="left" width={320} onClose={() => setHistoryOpen(false)} destroyOnClose>
      {historyOpen && history}
    </Drawer>
  </section>;
}

function ConversationHistory({ appId, selectedId, epoch, onSelect, onAuthorizationError }: {
  appId: string; selectedId: string; epoch: number; onSelect: (id: string) => void;
  onAuthorizationError: (error: EmployeeApiError) => void;
}) {
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [data, setData] = useState<ConversationPage>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const timer = window.setTimeout(() => { setSearch(keyword); setPage(1); }, 300);
    return () => window.clearTimeout(timer);
  }, [keyword]);
  useEffect(() => {
    if (!appId) return;
    const abort = new AbortController();
    setLoading(true);
    employeeWorkspace.conversations(appId, search, page, abort.signal).then((result) => {
      if (!abort.signal.aborted) { setData(result); setError(undefined); }
    }).catch((failure: unknown) => {
      if (!abort.signal.aborted) {
        setData(undefined);
        setError(messageFor(failure));
        if (failure instanceof EmployeeApiError) onAuthorizationError(failure);
      }
    }).finally(() => { if (!abort.signal.aborted) setLoading(false); });
    return () => abort.abort();
  }, [appId, search, page, epoch, retry, onAuthorizationError]);
  return <div className="conversation-history">
    <div className="conversation-history__heading"><h2>我的会话</h2><span>仅自己可见</span></div>
    <Input aria-label="搜索历史会话" value={keyword} maxLength={120} allowClear prefix={<SearchOutlined />}
      placeholder="搜索标题或问题" onChange={(event) => setKeyword(event.target.value)} />
    <div className="conversation-history__items" aria-busy={loading}>
      {loading ? <Skeleton active paragraph={{ rows: 5 }} title={false} /> : error ? <Alert type="error" message="会话加载失败"
        description={error} action={<Button size="small" onClick={() => setRetry((value) => value + 1)}>重试</Button>} />
        : data?.items.length ? data.items.map((item) => <button key={item.conversation_id} type="button"
          className={`conversation-history__item${selectedId === item.conversation_id ? ' is-selected' : ''}`}
          aria-current={selectedId === item.conversation_id ? 'page' : undefined} onClick={() => onSelect(item.conversation_id)}>
          <span>{item.title}</span><small>{item.active_run_id ? '正在回答' : `${item.last_turn} 轮问答`}</small>
        </button>) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={search ? '没有匹配的会话' : '你的会话会保存在这里'} />}
    </div>
    {data && data.total > 20 && <div className="conversation-history__pages">
      <Button size="small" disabled={loading || page === 1} onClick={() => setPage((value) => value - 1)}>上一页</Button>
      <span>{page} / {Math.ceil(data.total / 20)}</span>
      <Button size="small" disabled={loading || page * 20 >= data.total} onClick={() => setPage((value) => value + 1)}>下一页</Button>
    </div>}
  </div>;
}
