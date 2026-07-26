// Author: owlzhangfq@gmail.com
import { useEffect, useMemo, useState } from 'react';
import { Alert, Descriptions, Drawer, Empty, Radio, Space, Spin, Table, Tag, Typography, message } from 'antd';
import { compareEvalRuns } from '../../../api/evalRun';
import type { EvalRun, KMetricSet, MetricGroupKey, MetricPoint } from '../../../api/types';
import { APP_VERSION_STATUS_META, METRIC_GROUP_META, RUN_STATUS_META, metaOf } from '../../../utils/statusMeta';
import type { AppVersion } from '../../../api/types';

interface GateCompareDrawerProps {
  /** null = drawer closed. */
  version: AppVersion | null;
  onClose: () => void;
}

const METRIC_LABELS: Record<keyof KMetricSet, string> = {
  recall: 'Recall',
  precision: 'Precision',
  hit_rate: 'Hit Rate',
  mrr: 'MRR',
  ndcg: 'NDCG',
};

const GROUP_OPTIONS: MetricGroupKey[] = ['all', 'span', 'document', 'single_turn', 'multi_turn'];

function formatMetricPoint(point: MetricPoint | undefined): string {
  if (!point) {
    return '-';
  }
  const value = `${(point.value * 100).toFixed(1)}%`;
  if (point.ci_low !== undefined && point.ci_high !== undefined) {
    return `${value} (95%CI ${(point.ci_low * 100).toFixed(1)}%~${(point.ci_high * 100).toFixed(1)}%)`;
  }
  return value;
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
    const metricKeys = Object.keys(METRIC_LABELS) as (keyof KMetricSet)[];
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
                        formatMetricPoint(run.metrics?.[group]?.[row.k]?.[row.metricKey]),
                    })),
                  ]}
                />

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
