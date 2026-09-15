import { describe, expect, it } from 'vitest';
import type { EmployeeRun } from '../../api/employeeWorkspace';
import { mergeEmployeeRun } from './useEmployeeConversation';

const run = { run_id: 'run', conversation_id: 'conv', revision: 3, checkpoint_seq: 2, answer: '新版正文',
  references: [], restricted: false, status: 'RUNNING' } as unknown as EmployeeRun;

describe('持久回答合并边界', () => {
  it('迟到的检查点不能倒退正文', () => {
    expect(mergeEmployeeRun(run, { ...run, revision: 2, answer: '旧正文' }).answer).toBe('新版正文');
  });
  it('已终止运行不能被迟到活动状态恢复', () => {
    expect(mergeEmployeeRun({ ...run, status: 'CANCELLED' }, { ...run, revision: 4 }).status).toBe('CANCELLED');
  });
  it('同序号或迟到的撤权结果也必须立即清除正文', () => {
    expect(mergeEmployeeRun(run, { ...run, revision: 2, restricted: true })).toMatchObject({ answer: '', restricted: true, references: [] });
  });
  it('同一视图中旧授权响应不能重新显示已撤权内容', () => {
    expect(mergeEmployeeRun({ ...run, answer: '', restricted: true }, run).answer).toBe('');
  });
  it('撤权投影也清除评价说明，迟到响应不能恢复反馈中的原文', () => {
    const graded = { ...run, feedback: { verdict: 'BAD' as const, note: '从原答案引用的说明', updated_at: '2026-09-11T10:00:00' } };
    expect(mergeEmployeeRun(undefined, { ...graded, restricted: true }).feedback).toBeNull();
    const revoked = mergeEmployeeRun(graded, { ...graded, revision: 2, restricted: true });
    expect(revoked.feedback).toBeNull();
    expect(mergeEmployeeRun(revoked, { ...graded, revision: 4 }).feedback).toBeNull();
  });
});
