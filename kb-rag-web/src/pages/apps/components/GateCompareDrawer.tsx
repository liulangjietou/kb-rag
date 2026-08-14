// Author: owlzhangfq@gmail.com
import { useEffect, useMemo, useState } from 'react';
import { Alert, Descriptions, Drawer, Empty, Radio, Space, Spin, Table, Tag, Typography, message } from 'antd';
import { compareEvalRuns } from '../../../api/evalRun';
import type { EvalRun, KMetricSet, MetricGroupKey, MetricNumberKey } from '../../../api/types';
import { APP_VERSION_STATUS_META, METRIC_GROUP_META, RUN_STATUS_META, metaOf } from '../../../utils/statusMeta';
import type { AppVersion } from '../../../api/types';

interface GateCompareDrawerProps {
  /** null = drawer closed. */
  version: AppVersion | null;
  onClose: () => void;
}

const METRIC_LABELS: Record<MetricNumberKey, string> = {
  recall: 'Recall',
  precision: 'Precision',
  hit_rate: 'Hit Rate',
  mrr: 'MRR',
  ndcg: 'NDCG',
};

const GROUP_OPTIONS: MetricGroupKey[] = ['all', 'span', 'document', 'single_turn', 'multi_turn'];

const ANSWER_ROWS = [
  ['score', '综合分'],
  ['correctness', '正确性'],
  ['faithfulness', '忠实度'],
  ['completeness', '完整性'],
  ['citation_correctness', '引用正确性'],
  ['citation_completeness', '引用完整性'],
  ['refusal_accuracy', '答/拒决策准确率'],
] as const;

function formatMetricValue(set: KMetricSet | undefined, key: MetricNumberKey): string {
  const value = set?.[key];
  if (value === undefined || value === null) {
    return '-';
  }
  const pct = `${(value * 100).toFixed(1)}%`;
  const ci = key === 'recall' ? set?.recall_ci : key === 'hit_rate' ? set?.hit_rate_ci : undefined;
  if (ci) {
    return `${pct} (95%CI ${(ci.low * 100).toFixed(1)}%~${(ci.high * 100).toFixed(1)}%)`;
  }
  return pct;
}

/**
 * 发布门禁双跑对比结果 (M4c-CONTRACTS.md sections 2/4): reuses M4b's GET /eval-runs/compare on the
 * version's gate_run_ids so the candidate/baseline metrics render as one side-by-side table --
 * the same comparability/metrics machinery the eval center's report drawer already established,
 * applied to exactly the two runs the release gate itself created.
 */
export default function GateCompareDrawer({ version, onClose }: GateCompareDrawerProps) {
  const [loading, setLoading] = useState(false);
  const [comparable, setComparable] = useState(true);
  const [incomparableReason, setIncomparableReason] = useState<string | null>(null);
  const [runs, setRuns] = useState<EvalRun[]>([]);
  const [group, setGroup] = useState<MetricGroupKey>('all');

  const runIds = version?.gate_run_ids ?? null;

  useEffect(() => {
    if (!runIds || runIds.length === 0) {
      setRuns([]);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setGroup('all');
    compareEvalRuns(runIds)
      .then((result) => {
        if (cancelled) return;
        setComparable(result.comparable);
        setIncomparableReason(result.reason);
        setRuns(result.comparable ? result.runs : []);
      })
      .catch(() => {
        if (!cancelled) message.error('门禁双跑结果加载失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runIds?.join(',')]);

  const kKeys = useMemo(() => {
    const keys = new Set<string>();
    runs.forEach((run) => {
      const groupMetrics = run.metrics?.[group];
      if (groupMetrics) {
        Object.keys(groupMetrics).forEach((k) => keys.add(k));
      }
    });
    return Array.from(keys).sort((a, b) => Number(a) - Number(b));
  }, [runs, group]);

  const metricRows = useMemo(() => {
    const metricKeys = Object.keys(METRIC_LABELS) as MetricNumberKey[];
    return kKeys.flatMap((k) =>
      metricKeys.map((metricKey) => ({
        key: `${metricKey}@${k}`,
        label: `${METRIC_LABELS[metricKey]}@${k}`,
        k,
        metricKey,
      })),
    );
  }, [kKeys]);

  // ASSUMPTION: gate_run_ids[0] = candidate (this version's config), [1] = baseline (current
  // RELEASED config at gate time) -- see AppVersion.gate_run_ids's doc comment in types.ts.
  const runLabels = ['本次候选', '对照（当前正式版）'];

  return (
    <Drawer title="发布门禁双跑对比" open={version !== null} onClose={onClose} width={880} destroyOnClose>
      <Spin spinning={loading}>
        {version && (
          <Space direction="vertical" style={{ width: '100%' }} size={16}>
            <Descriptions size="small" bordered column={2}>
              <Descriptions.Item label="版本">{version.version}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={metaOf(APP_VERSION_STATUS_META, version.status).color}>
                  {metaOf(APP_VERSION_STATUS_META, version.status).label}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="门禁结论" span={2}>
                {version.gate_verdict || '暂无'}
              </Descriptions.Item>
              {version.gate_report?.answer_decision && (
                <Descriptions.Item label="最终答案门禁" span={2}>
                  {version.gate_report.answer_decision.verdict} / {version.gate_report.answer_decision.reason}
                </Descriptions.Item>
              )}
            </Descriptions>

            {!runIds || runIds.length === 0 ? (
              <Empty description="该版本未绑定门禁评测集，或尚未执行过双跑" />
            ) : !comparable ? (
              <Alert
                type="warning"
                showIcon
                message="双跑结果不可比"
                description={incomparableReason ?? '语料版本（dataset_revision / corpus_fingerprint）不一致'}
              />
            ) : runs.length === 0 ? (
              <Empty description="暂无数据" />
            ) : (
              <>
                <Space wrap>
                  {runs.map((run, index) => {
                    const meta = metaOf(RUN_STATUS_META, run.status);
                    return (
                      <Tag key={run.run_id} color={meta.color}>
                        {runLabels[index] ?? run.retrieval_config.label}：{meta.label}
                      </Tag>
                    );
                  })}
                </Space>

                <Radio.Group value={group} onChange={(e) => setGroup(e.target.value)} optionType="button">
                  {GROUP_OPTIONS.map((g) => (
                    <Radio.Button key={g} value={g}>
                      {metaOf(METRIC_GROUP_META, g).label}
                    </Radio.Button>
                  ))}
                </Radio.Group>

                <Table
                  size="small"
                  rowKey="key"
                  pagination={false}
                  dataSource={metricRows}
                  scroll={{ x: true }}
                  columns={[
                    { title: '指标', dataIndex: 'label', fixed: 'left', width: 160 },
                    ...runs.map((run, index) => ({
                      title: runLabels[index] ?? run.retrieval_config.label,
                      key: run.run_id,
                      width: 220,
                      render: (_: unknown, row: (typeof metricRows)[number]) =>
                        formatMetricValue(run.metrics?.[group]?.[row.k], row.metricKey),
                    })),
                  ]}
                />

                {version.gate_report?.answer_comparison && (
                  <>
                    <Typography.Title level={5} style={{ marginBottom: 0 }}>
                      最终答案质量对比
                    </Typography.Title>
                    <Table
                      size="small"
                      rowKey="key"
                      pagination={false}
                      dataSource={ANSWER_ROWS.map(([key, label]) => ({ key, label }))}
                      columns={[
                        { title: '指标', dataIndex: 'label', width: 180 },
                        {
                          title: '本次候选',
                          render: (_: unknown, row: { key: (typeof ANSWER_ROWS)[number][0] }) => {
                            const value = version.gate_report?.answer_comparison?.candidate[row.key];
                            return row.key === 'refusal_accuracy'
                              ? `${((value ?? 0) * 100).toFixed(1)}%`
                              : (value ?? 0).toFixed(2);
                          },
                        },
                        {
                          title: '对照（当前正式版）',
                          render: (_: unknown, row: { key: (typeof ANSWER_ROWS)[number][0] }) => {
                            const value = version.gate_report?.answer_comparison?.baseline?.[row.key];
                            if (value === undefined || value === null) return '-';
                            return row.key === 'refusal_accuracy'
                              ? `${(value * 100).toFixed(1)}%`
                              : value.toFixed(2);
                          },
                        },
                      ]}
                    />
                  </>
                )}

                <Typography.Text type="secondary">
                  比较仅在双方有效 case 交集上重新计算；容差 ε = max(0.02, 1/N)，候选指标低于对照 − ε 才判定拦截。
                </Typography.Text>
              </>
            )}
          </Space>
        )}
      </Spin>
    </Drawer>
  );
}
