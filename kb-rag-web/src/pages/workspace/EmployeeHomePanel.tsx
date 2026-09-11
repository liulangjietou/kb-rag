import { ArrowRightOutlined, MessageOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Skeleton, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { EmployeeApiError, employeeWorkspace, type EmployeeHomeOverview } from '../../api/employeeWorkspace';
import { useAuth } from '../../auth/AuthContext';
import './employee-home.css';

function activityLabel(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '时间未知' : new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(date);
}

/** 只展示本人当前可用的应用与会话；打开入口不会提交问题或重复执行模型。 */
export default function EmployeeHomePanel() {
  const navigate = useNavigate();
  const location = useLocation();
  const { logout } = useAuth();
  const [overview, setOverview] = useState<EmployeeHomeOverview>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    setOverview(undefined);
    setError(undefined);
    employeeWorkspace.overview(abort.signal).then((data) => {
      if (!abort.signal.aborted) setOverview(data);
    }).catch((failure: unknown) => {
      if (abort.signal.aborted) return;
      if (failure instanceof EmployeeApiError && ['UNAUTHORIZED', '401'].includes(failure.code)) {
        logout();
        navigate('/login', { replace: true, state: { from: location } });
        return;
      }
      setError(failure instanceof Error ? failure.message : '暂时无法读取，请重试');
    }).finally(() => { if (!abort.signal.aborted) setLoading(false); });
    return () => abort.abort();
  }, [generation, location, logout, navigate]);

  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') setGeneration((value) => value + 1); };
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, []);

  const open = (app: string, conversation?: string) => navigate(`/workspace?${new URLSearchParams({
    app, ...(conversation ? { conversation } : {}),
  })}`);

  return <section className="atlas-panel employee-home" aria-labelledby="employee-home-title">
    <header className="atlas-panel__head">
      <div><h2 id="employee-home-title">知识问答</h2><p>选择正式应用，或继续自己的会话</p></div>
      <Button icon={<ReloadOutlined />} aria-label="刷新知识问答入口" loading={loading}
        onClick={() => setGeneration((value) => value + 1)} />
    </header>
    {loading ? <div className="employee-home__body" role="status" aria-label="正在读取问答入口"><Skeleton active paragraph={{ rows: 3 }} /></div>
      : error ? <div className="employee-home__body"><Alert type="error" showIcon message="问答入口加载失败" description={error}
        action={<Button onClick={() => setGeneration((value) => value + 1)}>重试</Button>} /></div>
      : overview && <>
        {overview.applications.length === 0 ? <Empty className="employee-home__body" image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="还没有可使用的正式应用，请联系管理员确认应用授权与发布状态。" />
          : <ul className="employee-home__applications" aria-label="可使用的正式应用">
            {overview.applications.map((app) => <li key={app.app_id}><button type="button" onClick={() => open(app.app_id)}>
              <MessageOutlined aria-hidden="true" /><span><strong>{app.name}</strong><small>{app.description || '基于已授权知识提供回答与来源'}</small></span>
              <Tag>{app.released_version}</Tag><ArrowRightOutlined aria-hidden="true" />
            </button></li>)}
          </ul>}
        {overview.applications.length > 0 && <div className="employee-home__recent">
          <h3>最近会话</h3><p>按会话活动时间排列，仅自己可见</p>
          {overview.recent_conversations.length === 0 ? <p className="employee-home__empty">还没有会话，从上方选择应用开始提问。</p>
            : <ul>{overview.recent_conversations.map((conversation) => <li key={conversation.conversation_id}>
              <button type="button" onClick={() => open(conversation.app_id, conversation.conversation_id)}>
                <span><strong>{conversation.title}</strong><small>{conversation.app_name} · {activityLabel(conversation.last_activity_at)}</small></span>
                {conversation.active_run_id && <Tag color="processing">进行中</Tag>}<ArrowRightOutlined aria-hidden="true" />
              </button>
            </li>)}</ul>}
        </div>}
      </>}
  </section>;
}
