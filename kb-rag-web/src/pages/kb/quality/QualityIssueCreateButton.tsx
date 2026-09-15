import { Button } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { createQualityIssue, type QualityIssueSource } from '../../../api/qualityIssue';

/** 来源按钮只提交已有信号标识，成功后把用户带到同一份问题记录。 */
export default function QualityIssueCreateButton({ kbId, sourceType, sourceId, onOpen }: {
  kbId: string; sourceType: QualityIssueSource; sourceId: string; onOpen: (issueId: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const generation = useRef(0);
  useEffect(() => {
    generation.current += 1;
    pending.current = false;
    setBusy(false);
    return () => { generation.current += 1; };
  }, [kbId, sourceType, sourceId]);
  const create = async () => {
    if (pending.current) return;
    const requestGeneration = generation.current;
    pending.current = true; setBusy(true);
    try {
      const issue = await createQualityIssue(kbId, sourceType, sourceId);
      if (requestGeneration === generation.current) onOpen(issue.issue_id);
    }
    catch { /* 请求层已展示失败原因，保留原信号供用户重试。 */ }
    finally {
      if (requestGeneration === generation.current) { pending.current = false; setBusy(false); }
    }
  };
  return <Button type="link" size="small" loading={busy} onClick={() => void create()}>处理问题</Button>;
}
