import { Alert, Button, Empty, Table, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { listEmployeeFeedback, type EmployeeFeedbackSummary } from '../../../api/employeeFeedback';
import { useAuth } from '../../../auth/AuthContext';
import { PERMISSIONS } from '../../../auth/permissions';
import EmployeeFeedbackDrawer from './EmployeeFeedbackDrawer';

/** 跨知识库或登录身份时重建列表，迟到响应不能恢复上一身份的回答。 */
export default function EmployeeFeedbackTab(props: { kbId: string; onOpenIssue: (id: string) => void }) {
  const { token, can } = useAuth();
  if (!can(PERMISSIONS.FEEDBACK_MANAGE) || !can(PERMISSIONS.APP_READ)) return <Alert type="info" message="查看员工问答反馈需要反馈管理与应用读取权限。" />;
  return <Content key={`${token}:${props.kbId}`} {...props} />;
}

function Content({ kbId, onOpenIssue }: { kbId: string; onOpenIssue: (id: string) => void }) {
  const [items, setItems] = useState<EmployeeFeedbackSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [selected, setSelected] = useState<string>();
  const opener = useRef<HTMLElement | null>(null);
  const sequence = useRef(0);
  const load = useCallback(async () => {
    const request = ++sequence.current;
    setItems([]); setLoading(true); setError(false);
    try {
      const result = await listEmployeeFeedback(kbId, page);
      if (request !== sequence.current) return;
      const lastPage = Math.max(1, Math.ceil(result.total / 20));
      if (page > lastPage) { setPage(lastPage); return; }
      setItems(result.items); setTotal(result.total);
    } catch {
      if (request === sequence.current) { setError(true); setTotal(0); }
    } finally { if (request === sequence.current) setLoading(false); }
  }, [kbId, page]);
  useEffect(() => { void load(); return () => { sequence.current += 1; }; }, [load]);
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') void load(); };
    window.addEventListener('focus', refresh); document.addEventListener('visibilitychange', refresh);
    return () => { window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh); };
  }, [load]);
  const close = () => { setSelected(undefined); requestAnimationFrame(() => opener.current?.isConnected && opener.current.focus()); };
  return <section aria-label="员工问答反馈列表" className="quality-issue-list">
    <Typography.Title level={4}>把不准确的回答带回知识维护流程</Typography.Title>
    <p className="quality-muted">这里收集员工对完整回答的负面评价。核对问题与依据后，可建立质量问题并跟踪人工纠正和回归结果。</p>
    <div className="quality-detail-toolbar"><Button loading={loading} onClick={() => void load()}>刷新员工反馈</Button></div>
    {error ? <Alert type="error" showIcon message="员工反馈读取失败，请刷新重试。" /> : <Table<EmployeeFeedbackSummary>
      rowKey="run_id" dataSource={items} loading={loading} scroll={{ x: 660 }}
      locale={{ emptyText: loading ? '正在读取反馈…' : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无员工负面反馈" /> }}
      pagination={{ current: page, pageSize: 20, total, showSizeChanger: false, onChange: setPage, showTotal: count => `共 ${count} 条反馈` }}
      columns={[
        { title: '问题', key: 'question', width: 270, render: (_, item) => <span className="quality-record-note">{item.content_restricted ? '资料已不可访问，内容已隐藏' : item.question}</span> },
        { title: '员工说明', key: 'note', width: 230, render: (_, item) => <span className="quality-record-note">{item.content_restricted ? '—' : item.feedback_note || '未填写'}</span> },
        { title: '操作', key: 'action', width: 130, render: (_, item) => <Button disabled={item.content_restricted} onClick={() => {
          opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; setSelected(item.run_id);
        }}>查看原回答</Button> },
      ]} />}
    {selected && <EmployeeFeedbackDrawer kbId={kbId} runId={selected} onClose={close} onOpenIssue={id => {
      opener.current?.focus(); setSelected(undefined); onOpenIssue(id);
    }} />}
  </section>;
}
