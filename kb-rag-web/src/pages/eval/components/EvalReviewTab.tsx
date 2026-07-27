// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { Button, Card, Empty, Popconfirm, Radio, Space, Spin, Tag, Typography, message } from 'antd';
import { listStaleCases, recheckEvalCase } from '../../../api/evalCase';
import type { EvalCaseEvidence, EvalCaseEvidenceInput, EvalDataset, StaleCaseItem, StaleEvidenceCandidate } from '../../../api/types';
import { CASE_STATUS_META, metaOf } from '../../../utils/statusMeta';

interface EvalReviewTabProps {
  dataset: EvalDataset | null;
  onDatasetChanged: () => void;
}

/** Matches an evidence by (doc_id, span) rather than array index/identity -- the two arrays come from separate API shapes. */
function isSameEvidence(a: EvalCaseEvidence, b: EvalCaseEvidence): boolean {
  return a.doc_id === b.doc_id && (a.span ?? '') === (b.span ?? '');
}

/**
 * Evidence review workbench tab (M4b-CONTRACTS.md section 5 item 3): lists cases stuck in
 * EVIDENCE_STALE, shows each stale evidence's original (no-longer-matching) span next to its
 * Top-3 current-version candidates, and offers two case-level actions -- REANCHOR (rebuild the
 * case's full evidence list, swapping in the chosen candidate for every stale entry) or DEPRECATE.
 */
export default function EvalReviewTab({ dataset, onDatasetChanged }: EvalReviewTabProps) {
  const datasetId = dataset?.dataset_id ?? null;
  const [items, setItems] = useState<StaleCaseItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [processingCaseId, setProcessingCaseId] = useState<string | null>(null);
  // case_id -> (stale-evidence index -> chosen candidate); defaults to the top-overlap candidate.
  const [selections, setSelections] = useState<Record<string, Record<number, StaleEvidenceCandidate>>>({});

  const load = useCallback(async () => {
    if (!datasetId) {
      setItems([]);
      return;
    }
    setLoading(true);
    try {
      const result = await listStaleCases(datasetId);
      setItems(result);
      const initialSelections: Record<string, Record<number, StaleEvidenceCandidate>> = {};
      result.forEach((item) => {
        const perEvidence: Record<number, StaleEvidenceCandidate> = {};
        item.stale_evidences.forEach((review, index) => {
          if (review.candidates.length > 0) {
            perEvidence[index] = review.candidates[0];
          }
        });
        initialSelections[item.case.case_id] = perEvidence;
      });
      setSelections(initialSelections);
    } finally {
      setLoading(false);
    }
  }, [datasetId]);

  useEffect(() => {
    load();
  }, [load]);

  const handleReanchor = async (item: StaleCaseItem) => {
    const caseSelections = selections[item.case.case_id] ?? {};
    const rebuilt: EvalCaseEvidenceInput[] = item.case.evidences.map((evidence) => {
      const staleIndex = item.stale_evidences.findIndex((review) => isSameEvidence(review.evidence, evidence));
      if (staleIndex === -1) {
        return { doc_id: evidence.doc_id, span: evidence.span ?? undefined };
      }
      const chosen = caseSelections[staleIndex];
      return chosen
        ? { doc_id: chosen.doc_id, span: chosen.span }
        : { doc_id: evidence.doc_id, span: evidence.span ?? undefined };
    });
    setProcessingCaseId(item.case.case_id);
    try {
      await recheckEvalCase(item.case.case_id, { action: 'REANCHOR', evidences: rebuilt });
      message.success('已用候选证据替换，case 恢复为生效状态');
      load();
      onDatasetChanged();
    } finally {
      setProcessingCaseId(null);
    }
  };

  const handleDeprecate = async (item: StaleCaseItem) => {
    setProcessingCaseId(item.case.case_id);
    try {
      await recheckEvalCase(item.case.case_id, { action: 'DEPRECATE' });
      message.success('case 已废弃');
      load();
      onDatasetChanged();
    } finally {
      setProcessingCaseId(null);
    }
  };

  if (!dataset) {
    return <Empty description="请先在「评测集管理」选择一个评测集" />;
  }

  return (
    <Spin spinning={loading}>
      {items.length === 0 && !loading ? (
        <Empty description="暂无待复核 case" />
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          {items.map((item) => {
            const statusMeta = metaOf(CASE_STATUS_META, item.case.status);
            const processing = processingCaseId === item.case.case_id;
            return (
              <Card
                key={item.case.case_id}
                title={
                  <Space wrap>
                    <Tag color={statusMeta.color}>{statusMeta.label}</Tag>
                    <Typography.Text>{item.case.query}</Typography.Text>
                  </Space>
                }
                extra={
                  <Space>
                    <Button size="small" type="primary" loading={processing} onClick={() => handleReanchor(item)}>
                      用候选替换
                    </Button>
                    <Popconfirm
                      title="确认废弃该 case？"
                      okText="废弃"
                      okType="danger"
                      cancelText="取消"
                      onConfirm={() => handleDeprecate(item)}
                    >
                      <Button size="small" danger loading={processing}>
                        废弃 case
                      </Button>
                    </Popconfirm>
                  </Space>
                }
              >
                <Space direction="vertical" style={{ width: '100%' }} size={12}>
                  {item.stale_evidences.map((review, index) => (
                    <div key={index}>
                      <Typography.Text type="secondary">失配证据原文（doc_id: {review.evidence.doc_id}）：</Typography.Text>
                      <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }} type="danger">
                        {review.evidence.span || '(文档级锚定，无 span)'}
                      </Typography.Paragraph>
                      {review.candidates.length === 0 ? (
                        <Typography.Text type="secondary">当前激活版本中未找到候选原文，请手动编辑该 case</Typography.Text>
                      ) : (
                        <Radio.Group
                          value={selections[item.case.case_id]?.[index]?.chunk_id}
                          onChange={(e) => {
                            const candidate = review.candidates.find((c) => c.chunk_id === e.target.value);
                            if (!candidate) {
                              return;
                            }
                            setSelections((prev) => ({
                              ...prev,
                              [item.case.case_id]: { ...prev[item.case.case_id], [index]: candidate },
                            }));
                          }}
                        >
                          <Space direction="vertical">
                            {review.candidates.map((candidate) => (
                              <Radio key={candidate.chunk_id} value={candidate.chunk_id}>
                                <Space direction="vertical" size={0}>
                                  <Tag color="blue">重叠率 {(candidate.overlap_ratio * 100).toFixed(1)}%</Tag>
                                  <Typography.Text style={{ whiteSpace: 'pre-wrap' }}>{candidate.span}</Typography.Text>
                                </Space>
                              </Radio>
                            ))}
                          </Space>
                        </Radio.Group>
                      )}
                    </div>
                  ))}
                </Space>
              </Card>
            );
          })}
        </Space>
      )}
    </Spin>
  );
}
