import { Alert, Button, Empty, Select, Space, Switch, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { listQualityIssues, qualityFailure, type QualityIssue, type QualityIssueStatus } from '../../../api/qualityIssue';
import { QUALITY_REASONS, QUALITY_STATUS } from './qualityMeta';

/** 状态筛选和详情打开保持独立，旧分页响应不能覆盖新筛选结果。 */
export default function QualityIssueTab({ kbId, refreshKey, onOpen }: {
  kbId: string; refreshKey: number; onOpen: (issueId: string) => void;
}) {
  const [status, setStatus] = useState<QualityIssueStatus>();
  const [mine, setMine] = useState(false);
  const [page, setPage] = useState(1);
  const [items, setItems] = useState<QualityIssue[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const sequence = useRef(0);
  const load = useCallback(async () => {
    const request = ++sequence.current;
    setLoading(true);
    try {
      const result = await listQualityIssues(kbId, { status, mine, page });
      if (request !== sequence.current) return;
      const last = Math.max(1, Math.ceil(result.total / 20));
      if (page > last) { setPage(last); return; }
      setItems(result.items); setTotal(result.total); setError(undefined);
    } catch (failure) {
      if (request !== sequence.current) return;
      setItems([]); setError(qualityFailure(failure).message);
    } finally { if (request === sequence.current) setLoading(false); }
  }, [kbId, status, mine, page]);
  useEffect(() => { void load(); return () => { sequence.current += 1; }; }, [load, refreshKey]);
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') void load(); };
    window.addEventListener('focus', refresh); document.addEventListener('visibilitychange', refresh);
    return () => { window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh); };
  }, [load]);
  return <section className="quality-issue-list" aria-label="质量问题列表">
    <div className="quality-list-intro"><Typography.Title level={4}>让反馈得到处理，让纠正得到验证</Typography.Title>
      <p className="quality-muted">从反馈管理或零命中报告建立问题，领取后确认原因、补齐正确依据，再用实际评测结果验证。</p></div>
    <Space wrap className="quality-list-filters"><Select aria-label="质量问题状态" allowClear placeholder="全部状态" value={status}
      style={{ minWidth: 150 }} options={Object.entries(QUALITY_STATUS).map(([value, meta]) => ({ value, label: meta.label }))}
      onChange={(value) => { setStatus(value); setPage(1); }} />
      <label className="quality-mine-filter"><Switch aria-label="只看我负责的" checked={mine} onChange={(value) => { setMine(value); setPage(1); }} />只看我负责的</label>
      <Button loading={loading} onClick={() => void load()}>刷新问题</Button></Space>
    {error && <Alert type="error" showIcon message={error} />}
    <Table<QualityIssue> rowKey="issue_id" loading={loading} dataSource={items} scroll={{ x: 760 }}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的质量问题，可从反馈或零命中报告开始处理" /> }}
      pagination={{ current: page, pageSize: 20, total, showSizeChanger: false, showTotal: (count) => `共 ${count} 个问题`, onChange: setPage }}
      columns={[
        { title: '问题', key: 'summary', width: 320, render: (_, issue) => <>
          {issue.content_restricted ? <span className="quality-muted">资料访问受限，仅显示处理状态</span>
            : <Button type="link" className="quality-summary-link" onClick={() => onOpen(issue.issue_id)}>{issue.summary || '未提供摘要'}</Button>}
          <div className="quality-muted">{issue.source_type === 'BAD_FEEDBACK' ? '负面反馈' : '零命中报告'}</div>
        </> },
        { title: '阶段', dataIndex: 'status', width: 110, render: (value: QualityIssueStatus) => <Tag color={QUALITY_STATUS[value].color}>{QUALITY_STATUS[value].label}</Tag> },
        { title: '负责人', dataIndex: 'owner_name', width: 140, render: (value: string | null) => value || '待领取' },
        { title: '问题原因', dataIndex: 'reason', width: 140, render: (value: QualityIssue['reason']) => value ? QUALITY_REASONS[value] : '待判断' },
        { title: '操作', key: 'action', width: 100, render: (_, issue) => <Button disabled={issue.content_restricted} onClick={() => onOpen(issue.issue_id)}>查看处理</Button> },
      ]} />
  </section>;
}
