import { DislikeOutlined, LikeOutlined } from '@ant-design/icons';
import { Alert, Button, Input, Modal } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { EmployeeApiError, type AnswerFeedbackVerdict, type EmployeeRun } from '../../api/employeeWorkspace';
import './answer-feedback.css';

type SaveFeedback = (run: EmployeeRun, verdict: AnswerFeedbackVerdict, note?: string) => Promise<boolean>;

function feedbackError(failure: unknown): string {
  if (failure instanceof EmployeeApiError && failure.code === 'NETWORK_ERROR') return '反馈保存结果尚未确认，请重试保存';
  return failure instanceof Error ? failure.message : '反馈保存失败，请重试';
}

/** 快速记录有帮助的评价；问题说明由会话层的稳定对话框编辑。 */
export default function AnswerFeedbackControls({ run, onSave, onEdit }: {
  run: EmployeeRun; onSave: SaveFeedback; onEdit: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const pending = useRef(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const helpful = async () => {
    if (pending.current || run.feedback?.verdict === 'GOOD') return;
    pending.current = true;
    setBusy(true); setError(undefined);
    try { await onSave(run, 'GOOD'); }
    catch (failure) { if (alive.current) setError(feedbackError(failure)); }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  };
  return <div className="answer-feedback" aria-label="回答评价">
    <button type="button" className="workspace-text-action" aria-label="回答有帮助" aria-pressed={run.feedback?.verdict === 'GOOD'}
      disabled={busy} onClick={() => void helpful()}><LikeOutlined />有帮助</button>
    <button type="button" id={`answer-feedback-${run.run_id}`} className="workspace-text-action" aria-label="回答需改进"
      aria-pressed={run.feedback?.verdict === 'BAD'} disabled={busy} onClick={onEdit}><DislikeOutlined />需改进</button>
    {busy && <span role="status">正在保存反馈…</span>}
    {error && <Alert type="warning" showIcon message={error} />}
  </div>;
}

/** 隐藏或刷新回答列表时保留草稿；授权失效由会话层销毁表单。 */
export function AnswerFeedbackDialog({ initialRun, currentRun, visible, onSave, onClose }: {
  initialRun: EmployeeRun; currentRun: EmployeeRun; visible: boolean; onSave: SaveFeedback; onClose: () => void;
}) {
  const [editing, setEditing] = useState(initialRun);
  const [note, setNote] = useState(initialRun.feedback?.note ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [conflict, setConflict] = useState(false);
  const pending = useRef(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const save = async () => {
    if (pending.current || conflict) return;
    pending.current = true;
    setBusy(true); setError(undefined);
    try { if (await onSave(editing, 'BAD', note.trim() || undefined) && alive.current) onClose(); }
    catch (failure) {
      if (alive.current) {
        setConflict(failure instanceof EmployeeApiError && failure.code === 'FEEDBACK_VERSION_CONFLICT');
        setError(feedbackError(failure));
      }
    } finally { pending.current = false; if (alive.current) setBusy(false); }
  };
  return <Modal title="回答反馈" open={visible} onCancel={onClose} okText="保存反馈" cancelText="取消"
    onOk={() => void save()} confirmLoading={busy} closable={!busy} maskClosable={!busy} keyboard={!busy}
    cancelButtonProps={{ disabled: busy }} okButtonProps={{ disabled: conflict }} destroyOnHidden>
    <p>评价对象：第 {initialRun.turn_no} 轮回答 · 版本 {initialRun.app_version}</p>
    <label className="answer-feedback__label" htmlFor={`feedback-note-${initialRun.run_id}`}>问题说明（可选）</label>
    <Input.TextArea id={`feedback-note-${initialRun.run_id}`} aria-label="问题说明" value={note} onChange={(event) => setNote(event.target.value)}
      maxLength={512} showCount autoSize={{ minRows: 3, maxRows: 7 }} disabled={busy}
      placeholder="哪些内容不准确、信息不完整，或缺少依据？" />
    <div className="answer-feedback__problem">{error && <Alert type="warning" showIcon message={error} action={conflict ? <Button onClick={() => {
      setEditing(currentRun); setNote(currentRun.feedback?.note ?? ''); setError(undefined); setConflict(false);
    }}>载入最新反馈</Button> : undefined} />}</div>
  </Modal>;
}
