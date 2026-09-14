import { AppstoreOutlined, DatabaseOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Popconfirm, Skeleton, Space, Tag } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { clearResourceVisits, listResourceVisits, type ResourceVisit } from '../../api/resourceVisit';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';

function visitedLabel(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '时间未知' : new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(date);
}

/** 会话或授权范围变化时重新挂载，旧读写请求不能恢复上一轮记录。 */
export default function RecentVisitsList() {
  const { token, user, can } = useAuth();
  const canReadKb = can(PERMISSIONS.KB_READ);
  const canReadApps = can(PERMISSIONS.APP_READ);
  const scopeKey = JSON.stringify([token, user, canReadKb, canReadApps]);
  return <RecentVisitsContent key={scopeKey} canReadKb={canReadKb} canReadApps={canReadApps} />;
}

/** 独立读取访问记录，资源目录查询失败不会被当作访问记录为空。 */
function RecentVisitsContent({ canReadKb, canReadApps }: { canReadKb: boolean; canReadApps: boolean }) {
  const enabled = canReadKb || canReadApps;
  const navigate = useNavigate();
  const [items, setItems] = useState<ResourceVisit[]>([]);
  const [loading, setLoading] = useState(enabled);
  const [clearing, setClearing] = useState(false);
  const [error, setError] = useState<string>();
  const sequence = useRef(0);
  const clearingRef = useRef(false);

  const load = useCallback(async () => {
    if (clearingRef.current) return;
    const current = ++sequence.current;
    setItems([]);
    setError(undefined);
    setLoading(enabled);
    if (!enabled) return;
    try {
      const recent = await listResourceVisits();
      if (current === sequence.current) {
        setItems(recent.filter((item) => item.resource_type === 'KB' ? canReadKb : item.resource_type === 'APP' && canReadApps));
      }
    } catch {
      if (current === sequence.current) setError('访问记录加载失败，请刷新重试。');
    } finally {
      if (current === sequence.current) setLoading(false);
    }
  }, [enabled, canReadKb, canReadApps]);

  useEffect(() => {
    void load();
    const onFocus = () => { if (document.visibilityState !== 'hidden') void load(); };
    window.addEventListener('focus', onFocus);
    return () => { sequence.current += 1; window.removeEventListener('focus', onFocus); };
  }, [load]);

  const clear = async () => {
    clearingRef.current = true;
    const current = ++sequence.current;
    setClearing(true);
    try {
      await clearResourceVisits();
      if (current === sequence.current) { setItems([]); setError(undefined); }
    } catch {
      if (current === sequence.current) {
        setItems([]);
        setError('未能确认清空结果，请刷新访问记录后核对。');
      }
    } finally {
      clearingRef.current = false;
      if (current === sequence.current) setClearing(false);
    }
  };

  if (!enabled) return <Empty className="atlas-panel__empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有可展示的知识库或应用" />;

  return (
    <section aria-label="最近资源访问" aria-busy={loading || clearing}>
      <Space wrap style={{ padding: '12px 20px 0' }}>
        <Button size="small" icon={<ReloadOutlined aria-hidden />} onClick={() => void load()} disabled={loading || clearing}>刷新记录</Button>
        <Popconfirm title="清空自己的访问记录？" description="知识库和应用仍会保留。" okText="清空" cancelText="取消" onConfirm={clear}>
          <Button size="small" disabled={loading || items.length === 0} loading={clearing}>清空记录</Button>
        </Popconfirm>
      </Space>
      {loading ? <div className="atlas-panel__loading"><Skeleton active paragraph={{ rows: 4 }} /></div>
        : error ? <Alert className="atlas-panel__resource-error" type="error" showIcon message={error} />
          : items.length === 0 ? <Empty className="atlas-panel__empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="打开知识库或应用后，会在这里显示最近访问" />
            : <ul className="atlas-work-list">
              {items.map((item) => <li key={`${item.resource_type}:${item.resource_id}`}>
                <button type="button" onClick={() => navigate(`/${item.resource_type === 'KB' ? 'kb' : 'apps'}/${encodeURIComponent(item.resource_id)}`)}>
                  <span className={`atlas-work-list__icon${item.resource_type === 'APP' ? ' is-app' : ''}`}>
                    {item.resource_type === 'APP' ? <AppstoreOutlined aria-hidden /> : <DatabaseOutlined aria-hidden />}
                  </span>
                  <span className="atlas-work-list__copy"><strong>{item.name}</strong><small>继续查看{item.resource_type === 'KB' ? '知识库' : '应用'}</small></span>
                  <span className="atlas-work-list__meta"><Tag>{item.resource_type === 'KB' ? '知识库' : '应用'}</Tag><small>访问于 {visitedLabel(item.visited_at)}</small></span>
                </button>
              </li>)}
            </ul>}
    </section>
  );
}
