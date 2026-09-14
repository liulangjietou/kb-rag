import { Alert, Button, Drawer, Spin, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { getEmployeeFeedback, type EmployeeFeedbackDetail } from '../../../api/employeeFeedback';
import { qualityFailure } from '../../../api/qualityIssue';
import { useAuth } from '../../../auth/AuthContext';
import { PERMISSIONS } from '../../../auth/permissions';
import QualityIssueCreateButton from './QualityIssueCreateButton';

/** 身份或来源变化会销毁旧内容；原答案只能作为待核对记录，不能自动成为标准答案。 */
export default function EmployeeFeedbackDrawer(props: { kbId: string; runId: string; onClose: () => void; onOpenIssue?: (id: string) => void }) {
  const { token, can } = useAuth();
  if (!can(PERMISSIONS.FEEDBACK_MANAGE) || !can(PERMISSIONS.APP_READ)) return null;
  return <Content key={`${token}:${props.kbId}:${props.runId}`} {...props} />;
}

function Content({ kbId, runId, onClose, onOpenIssue }: { kbId: string; runId: string; onClose: () => void; onOpenIssue?: (id: string) => void }) {
  const [source, setSource] = useState<EmployeeFeedbackDetail>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const sequence = useRef(0);
  const load = useCallback(async () => {
    const request = ++sequence.current;
    setSource(undefined); setLoading(true); setError(undefined);
    try {
      const detail = await getEmployeeFeedback(kbId, runId);
      if (request === sequence.current) setSource(detail);
    } catch (failure) {
      if (request === sequence.current) setError(qualityFailure(failure).restricted
        ? '该反馈或依赖资料已不可访问，原回答已隐藏。' : '反馈读取失败，请重试。');
    } finally { if (request === sequence.current) setLoading(false); }
  }, [kbId, runId]);
  useEffect(() => { void load(); return () => { sequence.current += 1; }; }, [load]);
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') void load(); };
    window.addEventListener('focus', refresh); document.addEventListener('visibilitychange', refresh);
    return () => { window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh); };
  }, [load]);
  let citationNumber = 0;
  return <Drawer open title="员工问答反馈" rootClassName="quality-issue-drawer" width="min(760px, 100vw)" onClose={onClose} destroyOnHidden>
    <div className="quality-detail-toolbar"><Button loading={loading} onClick={() => void load()}>刷新原回答</Button></div>
    {loading && <Spin tip="正在确认反馈与资料权限"><div style={{ minHeight: 80 }} /></Spin>}
    {error && <Alert type="warning" showIcon message={error} />}
    {source && <>
      <p className="quality-muted">版本 {source.app_version} · {source.feedback_verdict === 'BAD' ? '员工认为需改进' : '员工已改为有帮助'}</p>
      {source.feedback_verdict === 'GOOD' && <Alert type="info" showIcon message="评价已变化，请核对当前反馈后决定如何处理已有问题。" />}
      <Typography.Title level={4}>原问题</Typography.Title><p className="quality-record-note">{source.question}</p>
      <Typography.Title level={4}>员工问题说明</Typography.Title><p className="quality-record-note">{source.feedback_note || '未填写问题说明'}</p>
      <Typography.Title level={4}>待核对的原回答</Typography.Title><p className="quality-answer">{source.answer}</p>
      <Typography.Title level={4}>回答使用的依据</Typography.Title>
      {source.citations.length === 0 && <p className="quality-muted">本轮未引用资料，请核对是否应当拒答。</p>}
      {source.citations.map((citation, index) => <section className="quality-evidence-row" key={index}>
        <strong>{citation.inherited ? '历史依赖' : `[${++citationNumber}]`} · {citation.file_name}</strong>
        <p className="quality-record-note">{citation.content}</p>
      </section>)}
      <p className="quality-muted">先核对错误原因，再人工补充正确答案和证据。原回答及原引用不会自动写入标准用例。</p>
      {onOpenIssue && source.feedback_verdict === 'BAD' && <QualityIssueCreateButton kbId={kbId} sourceType="EMPLOYEE_ANSWER" sourceId={runId} onOpen={onOpenIssue} />}
    </>}
  </Drawer>;
}
