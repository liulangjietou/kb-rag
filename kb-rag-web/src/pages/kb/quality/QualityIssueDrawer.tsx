import { Alert, Button, Descriptions, Drawer, Empty, Input, Modal, Pagination, Space, Spin, Tag, Timeline, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  correctQualityIssue, getQualityIssue, listQualityIssueRecords, qualityFailure, updateQualityIssue,
  type QualityCorrection, type QualityIssue, type QualityIssueAction, type QualityIssueRecord,
} from '../../../api/qualityIssue';
import { useAuth } from '../../../auth/AuthContext';
import { PERMISSIONS } from '../../../auth/permissions';
import QualityCorrectionForm from './QualityCorrectionForm';
import QualityRegressionPanel from './QualityRegressionPanel';
import { QUALITY_ACTIONS, QUALITY_REASONS, QUALITY_STATUS, QUALITY_SOURCES } from './qualityMeta';
import EmployeeFeedbackDrawer from './EmployeeFeedbackDrawer';

/** 详情和草稿分开维护，焦点刷新不覆盖编辑；失去资料权限时立即销毁内容与草稿。 */
export default function QualityIssueDrawer({ kbId, issueId, onClose, onChanged }: {
  kbId: string; issueId: string; onClose: () => void; onChanged: () => void;
}) {
  const { can } = useAuth();
  const [issue, setIssue] = useState<QualityIssue>();
  const [records, setRecords] = useState<QualityIssueRecord[]>([]);
  const [recordPage, setRecordPage] = useState(1);
  const [recordTotal, setRecordTotal] = useState(0);
  const [checking, setChecking] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ReturnType<typeof qualityFailure>>();
  const [editing, setEditing] = useState<QualityIssue>();
  const [editGeneration, setEditGeneration] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [note, setNote] = useState('');
  const [sourceOpen, setSourceOpen] = useState(false);
  const sequence = useRef(0);
  const pending = useRef(false);
  const alive = useRef(true);
  const canCorrect = can(PERMISSIONS.EVAL_WRITE) && can(PERMISSIONS.EVAL_READ) && can(PERMISSIONS.APP_READ);
  const canVerify = can(PERMISSIONS.EVAL_READ) && can(PERMISSIONS.APP_READ);
  const restricted = useCallback(() => {
    sequence.current += 1;
    setIssue(undefined); setRecords([]); setEditing(undefined); setNote(''); setDirty(false); setChecking(false);
    setError({ message: '相关资料或应用的访问权限已变化，内容已隐藏。请恢复权限后重新加载。', restricted: true, conflict: false, uncertain: false });
  }, []);

  const load = useCallback(async () => {
    const request = ++sequence.current;
    setChecking(true);
    try {
      const [latest, history] = await Promise.all([getQualityIssue(kbId, issueId), listQualityIssueRecords(kbId, issueId, recordPage)]);
      if (!alive.current || request !== sequence.current) return;
      if (latest.content_restricted) { restricted(); return; }
      setIssue(latest); setRecords(history.items); setRecordTotal(history.total); setError(undefined);
      return latest;
    } catch (failure) {
      if (!alive.current || request !== sequence.current) return;
      const problem = qualityFailure(failure);
      if (problem.restricted) restricted(); else setError(problem);
    } finally { if (alive.current && request === sequence.current) setChecking(false); }
  }, [kbId, issueId, recordPage, restricted]);

  useEffect(() => { alive.current = true; return () => { alive.current = false; sequence.current += 1; }; }, []);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible' && !pending.current) void load(); };
    window.addEventListener('focus', refresh); document.addEventListener('visibilitychange', refresh);
    return () => { window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh); };
  }, [load]);

  const owned = !!issue && issue.owned_by_me && issue.status !== 'RESOLVED';
  const staleDraft = !!editing && !!issue && (editing.revision !== issue.revision || editing.case_revision !== issue.case_revision);
  const blocked = checking || !!error || staleDraft;
  const resetEditing = (latest: QualityIssue) => { setEditing(latest); setEditGeneration((value) => value + 1); setDirty(false); };
  const discardThen = (action: () => void) => {
    if (!dirty) { action(); return; }
    Modal.confirm({ title: '放弃尚未保存的内容？', content: '本次未提交的纠正或处理说明将被清除。', okText: '放弃更改', cancelText: '继续编辑', onOk: action });
  };
  const close = () => { if (!pending.current) discardThen(onClose); };
  const loadLatestForEditing = () => discardThen(() => {
    void load().then((latest) => {
      if (latest) { setNote(''); if (editing) resetEditing(latest); else setDirty(false); }
    });
  });

  const mutate = async (operation: () => Promise<QualityIssue>) => {
    if (pending.current || blocked) return;
    pending.current = true; setBusy(true);
    try {
      await operation();
      if (!alive.current) return;
      setNote(''); setEditing(undefined); setDirty(false); setRecordPage(1); onChanged();
      await load();
    } catch (failure) {
      if (!alive.current) return;
      const problem = qualityFailure(failure);
      if (problem.restricted) restricted(); else setError(problem);
    } finally { pending.current = false; if (alive.current) setBusy(false); }
  };
  const act = async (action: QualityIssueAction, extra: { note?: string; run_id?: string } = {}) => {
    if (!issue) return;
    await mutate(() => updateQualityIssue(kbId, issueId, action, { revision: issue.revision, ...extra }));
  };
  const saveCorrection = async (payload: QualityCorrection) => { await mutate(() => correctQualityIssue(kbId, issueId, payload)); };
  const status = issue ? QUALITY_STATUS[issue.status] : undefined;

  return <Drawer open rootClassName="quality-issue-drawer" title="处理质量问题" width="min(820px, 100vw)" onClose={close}
    maskClosable={!busy} keyboard={!busy} closable={!busy} destroyOnHidden>
    <div className="quality-detail-toolbar"><Button loading={checking} disabled={busy} onClick={() => void load()}>刷新状态</Button>
      {checking && <span role="status" className="quality-muted">正在确认当前访问权限…</span>}</div>
    {error && <Alert type={error.restricted ? 'error' : 'warning'} showIcon message={error.message}
      description={error.conflict || error.uncertain ? '草稿仍保留。请先核对最新状态，再决定是否重新提交。' : undefined}
      action={!error.restricted ? <Button disabled={busy} onClick={loadLatestForEditing}>载入最新内容</Button> : undefined} />}
    {!issue ? (checking ? <Spin /> : !error && <Empty description="问题详情暂不可用" />) : <div style={{ display: checking || error?.uncertain ? 'none' : undefined }}>
      <div className="quality-issue-heading"><Tag color={status?.color}>{status?.label}</Tag>
        <span className="quality-muted">来自{QUALITY_SOURCES[issue.source_type]}</span>
        <Typography.Title level={3}>{issue.summary || '未提供问题摘要'}</Typography.Title>
        {issue.source_type === 'EMPLOYEE_ANSWER' && issue.source_id && <Button onClick={() => setSourceOpen(true)}>查看员工原回答</Button>}
      </div>
      {sourceOpen && !checking && !error && issue.source_type === 'EMPLOYEE_ANSWER' && issue.source_id &&
        <EmployeeFeedbackDrawer kbId={kbId} runId={issue.source_id} onClose={() => setSourceOpen(false)} />}
      <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: 'owner', label: '负责人', children: issue.owner_name || '尚未领取' },
        { key: 'reason', label: '原因', children: issue.reason ? QUALITY_REASONS[issue.reason] : '待判断' },
        { key: 'created', label: '建立时间', children: issue.created_at || '—' },
        { key: 'resolved', label: '解决时间', children: issue.resolved_at || '—' },
      ]} />
      <Space wrap className="quality-detail-actions">
        {issue.status === 'NEW' && <Button type="primary" loading={busy} disabled={blocked} onClick={() => void act('claim')}>领取问题</Button>}
        {owned && !editing && canCorrect && <Button type="primary" disabled={blocked || busy} onClick={() => { resetEditing(issue); setNote(''); }}>
          {issue.case_id ? '修订纠正用例' : '填写纠正方案'}</Button>}
        {owned && <Button disabled={blocked || busy || dirty} onClick={() => void act('release')}>释放给其他人</Button>}
        {issue.dataset_id && can(PERMISSIONS.EVAL_READ) && <Link target="_blank" rel="noreferrer"
          to={`/eval?kb_id=${encodeURIComponent(kbId)}&dataset_id=${encodeURIComponent(issue.dataset_id)}&tab=cases`}>打开关联评测集</Link>}
      </Space>
      {staleDraft && <Alert type="warning" showIcon message="问题或关联用例已被更新，当前草稿尚未覆盖新内容"
        action={<Button onClick={loadLatestForEditing}>载入最新内容</Button>} />}
      {editing && !owned && <Alert type="warning" showIcon message="负责人或处理阶段已变化，草稿已保留。请核对最新状态后再处理。" />}
      {editing && canCorrect && <QualityCorrectionForm key={`${issueId}:${editGeneration}`} issue={editing}
        blocked={blocked || !owned} busy={busy} onSave={saveCorrection} onDirty={() => setDirty(true)} onRestricted={restricted}
        onCancel={() => discardThen(() => { setEditing(undefined); setDirty(false); })} />}
      {owned && !canCorrect && <Alert type="info" showIcon message="填写纠正方案需要评测读写和应用读取权限，可先补充处理说明。" />}
      {owned && !editing && issue.status === 'WAITING_REGRESSION' && canVerify && <QualityRegressionPanel key={issue.revision}
        issue={issue} blocked={blocked} busy={busy} canRun={can(PERMISSIONS.EVAL_RUN)} onRestricted={restricted}
        onDirty={() => setDirty(true)} onResolve={async (runId, confirmation) => { await act('resolve', { run_id: runId, note: confirmation }); }} />}
      {(owned || issue.status === 'RESOLVED') && !editing && <section className="quality-processing-note">
        <label className="quality-field-label" htmlFor="quality-processing-note">{issue.status === 'RESOLVED' ? '重新打开的原因' : '补充处理说明'}</label>
        <Input.TextArea id="quality-processing-note" value={note} disabled={blocked || busy} maxLength={2048} showCount
          autoSize={{ minRows: 2, maxRows: 5 }} onChange={(event) => { setNote(event.target.value); setDirty(true); }} />
        <Button disabled={blocked || busy || !note.trim()} loading={busy} onClick={() => void act(issue.status === 'RESOLVED' ? 'reopen' : 'notes', { note: note.trim() })}>
          {issue.status === 'RESOLVED' ? '说明原因并重新打开' : '保存处理说明'}</Button>
      </section>}
      <section className="quality-processing-history" aria-label="处理记录"><Typography.Title level={4}>处理记录</Typography.Title>
        <Timeline items={records.map((record, index) => ({ key: `${record.created_at}:${index}`, children: <>
          <strong>{QUALITY_ACTIONS[record.action]}</strong> <span className="quality-muted">{record.actor_name} · {record.created_at}</span>
          {record.note && <p className="quality-record-note">{record.note}</p>}
          {record.run_id && <p className="quality-muted">回归运行：{record.run_id}</p>}
        </> }))} />
        <Pagination simple hideOnSinglePage current={recordPage} total={recordTotal} pageSize={50}
          disabled={checking || busy} onChange={setRecordPage} />
      </section>
    </div>}
  </Drawer>;
}
