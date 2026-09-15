import { Alert, Button, Descriptions, Input, Select, Space, Spin, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { listEvalRuns } from '../../../api/evalRun';
import { getQualityRegression, qualityFailure, type QualityIssue, type QualityRegression } from '../../../api/qualityIssue';
import type { EvalRun } from '../../../api/types';

/** 先查看当前用例的真实答案，再提交人工确认；每次返回页面都重新检查所选运行。 */
export default function QualityRegressionPanel({ issue, blocked, busy, canRun, onResolve, onRestricted, onDirty }: {
  issue: QualityIssue; blocked: boolean; busy: boolean; canRun: boolean;
  onResolve: (runId: string, note: string) => Promise<void>; onRestricted: () => void; onDirty: () => void;
}) {
  const [runs, setRuns] = useState<EvalRun[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [runId, setRunId] = useState<string>();
  const [preview, setPreview] = useState<QualityRegression>();
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string>();
  const [note, setNote] = useState('');
  const sequence = useRef(0);
  const listSequence = useRef(0);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; sequence.current += 1; listSequence.current += 1; }; }, []);

  const loadRuns = useCallback(async (targetPage: number) => {
    if (!issue.dataset_id) return;
    const request = ++listSequence.current;
    setLoading(true);
    try {
      const result = await listEvalRuns(issue.dataset_id, targetPage);
      if (!alive.current || request !== listSequence.current) return;
      setRuns(result.items); setPage(result.page); setTotal(result.total); setPageSize(result.size);
    } catch (failure) {
      if (!alive.current || request !== listSequence.current) return;
      const problem = qualityFailure(failure); setError(problem.message);
      if (problem.restricted) onRestricted();
    } finally { if (alive.current && request === listSequence.current) setLoading(false); }
  }, [issue.dataset_id, onRestricted]);
  useEffect(() => { void loadRuns(1); }, [loadRuns]);

  const check = useCallback(async () => {
    const request = ++sequence.current;
    setPreview(undefined); setError(undefined);
    if (!runId) { setChecking(false); return; }
    setChecking(true);
    try {
      const result = await getQualityRegression(issue.kb_id, issue.issue_id, runId);
      if (request === sequence.current && alive.current) setPreview(result);
    } catch (failure) {
      if (request !== sequence.current || !alive.current) return;
      const problem = qualityFailure(failure); setError(problem.message);
      if (problem.restricted) onRestricted();
    } finally { if (request === sequence.current && alive.current) setChecking(false); }
  }, [issue.kb_id, issue.issue_id, runId, onRestricted]);
  useEffect(() => { void check(); }, [check]);
  useEffect(() => {
    const recheck = () => { if (document.visibilityState === 'visible') void check(); };
    window.addEventListener('focus', recheck); document.addEventListener('visibilitychange', recheck);
    return () => { window.removeEventListener('focus', recheck); document.removeEventListener('visibilitychange', recheck); };
  }, [check]);

  const eligible = runs.filter((run) => run.status === 'SUCCESS' && run.answer_evaluation);
  const score = preview?.result;
  return <section className="quality-regression" aria-label="回归验证">
    <Typography.Title level={4}>核对实际回归结果</Typography.Title>
    <p className="quality-muted">选择包含当前纠正用例的答案评测。五项评分分别至少 4/5、证据和配置仍有效，才能确认解决。</p>
    {issue.correction && <div className="quality-regression-standard">
      <Typography.Title level={5}>本次纠正的验收标准</Typography.Title>
      <p className="quality-answer">{issue.correction.query}</p>
      <p><strong>正确行为：</strong>{issue.correction.expected_refusal ? '资料不足时拒答' : '回答问题并引用正确依据'}</p>
      {issue.correction.expected_answer && <div className="quality-answer">{issue.correction.expected_answer}</div>}
      {issue.correction.evidences.length > 0 && <ul aria-label="纠正用例的证据">
        {issue.correction.evidences.map((evidence, index) => <li key={`${evidence.doc_id}:${index}`}>
          <span className="quality-muted">证据 {index + 1} · 文档 {evidence.doc_id}</span>
          <div className="quality-answer">{evidence.span || '按整篇文档核验'}</div>
        </li>)}
      </ul>}
    </div>}
    <Space wrap className="quality-regression-links">
      {canRun && <Link target="_blank" rel="noreferrer" to={`/eval?kb_id=${encodeURIComponent(issue.kb_id)}&dataset_id=${encodeURIComponent(issue.dataset_id ?? '')}&tab=run`}>打开评测任务</Link>}
      <Button disabled={busy || blocked} loading={loading} onClick={() => void loadRuns(page)}>刷新运行列表</Button>
    </Space>
    <label className="quality-field-label" htmlFor="quality-regression-run">已完成的答案评测</label>
    <Select id="quality-regression-run" aria-label="已完成的答案评测" className="quality-full-width" value={runId}
      loading={loading} disabled={busy || blocked} allowClear showSearch optionFilterProp="label" placeholder="选择真实评测运行"
      options={eligible.map((run) => ({ value: run.run_id, label: `${run.retrieval_config.label || '答案评测'} · ${run.finished_at ?? run.started_at ?? ''} · ${run.run_id}` }))}
      notFoundContent="本页暂无成功的答案评测" onChange={(value) => { setRunId(value); onDirty(); }} />
    <Space wrap className="quality-run-pagination">
      <Button size="small" disabled={loading || busy || page <= 1 || blocked} onClick={() => void loadRuns(page - 1)}>上一页运行</Button>
      <span className="quality-muted">第 {page} 页 · 共 {total} 次运行</span>
      <Button size="small" disabled={loading || busy || page * pageSize >= total || blocked} onClick={() => void loadRuns(page + 1)}>下一页运行</Button>
    </Space>
    {checking && <div role="status"><Spin size="small" /> 正在核验当前用例、证据和配置…</div>}
    {error && <Alert type="warning" showIcon message={error} action={<Button disabled={busy || blocked} onClick={() => void check()}>重新核验</Button>} />}
    {preview && !blocked && <div className="quality-regression-proof">
      <Alert type="success" showIcon message="当前用例通过回归检查，请继续核对答案并填写确认说明" />
      <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: 'correct', label: '正确性', children: `${score?.answer_correctness}/5` },
        { key: 'faithful', label: '忠实性', children: `${score?.answer_faithfulness}/5` },
        { key: 'complete', label: '完整性', children: `${score?.answer_completeness}/5` },
        { key: 'citations', label: '引用正确性', children: `${score?.citation_correctness}/5` },
        { key: 'coverage', label: '引用完整性', children: `${score?.citation_completeness}/5` },
      ]} />
      <Typography.Title level={5}>本次生成的答案</Typography.Title>
      <div className="quality-answer">{score?.generated_answer}</div>
      {score?.answer_judge_reason && <p className="quality-muted">评判说明：{score.answer_judge_reason}</p>}
    </div>}
    <label className="quality-field-label" htmlFor="quality-resolution-note">人工核验说明</label>
    <Input.TextArea id="quality-resolution-note" aria-label="人工核验说明" disabled={busy || blocked} value={note} maxLength={2048}
      showCount autoSize={{ minRows: 2, maxRows: 5 }} onChange={(event) => { setNote(event.target.value); onDirty(); }}
      placeholder="说明已核对哪些内容，以及这次结果为何满足处理要求" />
    <Button type="primary" loading={busy} className="quality-resolve-button" disabled={blocked || checking || !preview || !runId || !note.trim()}
      onClick={() => runId && void onResolve(runId, note.trim())}>确认回归并解决</Button>
  </section>;
}
