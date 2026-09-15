import { Button, Empty, Skeleton, Alert } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { listKnowledgeTodos, type KnowledgeTodo, type KnowledgeTodoKind } from '../../api/knowledgeTodo';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';

const LABELS: Record<KnowledgeTodoKind, string> = {
  PENDING_CONFIRM: '解析待确认', PENDING_REVIEW: '文档待审核',
  WEB_SOURCE_FAILED: '网页抓取失败', EXT_SOURCE_ATTENTION: '外部来源需处理',
};
const INITIAL_VISIBLE = 6;

/** 切换会话或授权范围时同步卸载摘要，旧请求不能恢复上一范围的名称与数量。 */
export default function KnowledgeTodos() {
  const { token, user, can } = useAuth();
  if (!can(PERMISSIONS.KB_READ)) return null;
  return <KnowledgeTodoContent key={JSON.stringify([token, user])} />;
}

function KnowledgeTodoContent() {
  const navigate = useNavigate();
  const [items, setItems] = useState<KnowledgeTodo[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const sequence = useRef(0);
  const load = useCallback(async () => {
    const current = ++sequence.current;
    setLoading(true); setFailed(false); setItems([]);
    try {
      const result = await listKnowledgeTodos();
      if (current === sequence.current) setItems(result);
    } catch {
      if (current === sequence.current) setFailed(true);
    } finally {
      if (current === sequence.current) setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
    const onFocus = () => { if (document.visibilityState !== 'hidden') void load(); };
    window.addEventListener('focus', onFocus);
    return () => { sequence.current += 1; window.removeEventListener('focus', onFocus); };
  }, [load]);
  return <section className="atlas-panel knowledge-todos" aria-label="知识处理待办" aria-busy={loading}>
    <header className="atlas-panel__head"><h2>知识待办</h2>
      <Button size="small" disabled={loading} onClick={() => void load()}>刷新待办</Button></header>
    {loading ? <div className="atlas-panel__loading"><Skeleton active paragraph={{ rows: 3 }} /></div>
      : failed ? <Alert type="error" showIcon message="待办加载失败" description="无法确认当前数量，请刷新重试。" />
        : items.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前授权范围内暂无知识待办" />
          : <><ul className="knowledge-todos__list">
            {(expanded ? items : items.slice(0, INITIAL_VISIBLE)).map((item) => <li key={`${item.kb_id}:${item.kind}`}>
              <button type="button" onClick={() => navigate(`/kb/${encodeURIComponent(item.kb_id)}?todo=${item.kind}`)}>
                <span><strong>{LABELS[item.kind]}</strong><small>{item.kb_name}</small></span>
                <span className="knowledge-todos__count"><b>{item.total}</b><small>{item.can_process ? '去处理' : '查看'}</small></span>
              </button></li>)}
          </ul>{items.length > INITIAL_VISIBLE && <Button type="link" aria-expanded={expanded}
            onClick={() => setExpanded(!expanded)}>{expanded ? '收起待办' : `查看全部 ${items.length} 组待办`}</Button>}</>}
  </section>;
}
