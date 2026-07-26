// Author: owlzhangfq@gmail.com
import { useEffect, useMemo, useState } from 'react';
import { DownloadOutlined } from '@ant-design/icons';
import { Alert, Drawer, Empty, Radio, Space, Spin, Table, Tag, Typography, message } from 'antd';
import { listAllEvalCases } from '../../../api/evalCase';
import { compareEvalRuns, getEvalRun, listAllEvalResults } from '../../../api/evalRun';
import type { EvalCase, EvalResult, EvalRun, KMetricSet, MetricGroupKey, MetricPoint } from '../../../api/types';
import { downloadCsv } from '../../../utils/csv';
import { METRIC_GROUP_META, RUN_STATUS_META, metaOf } from '../../../utils/statusMeta';

interface EvalReportDrawerProps {
  datasetId: string | null;
  /** null = drawer closed; 1 entry = single-run report; 2+ = comparison view via the compare endpoint. */
  runIds: string[] | null;
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

interface DrilldownRow {
  evalCase: EvalCase;
  perRun: Map<string, EvalResult | undefined>;
}

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

/** Groups one case into the report's 全体/span级/文档级/单轮/多轮 buckets (a case belongs to 3 of the 5). */
function caseGroups(evalCase: EvalCase): MetricGroupKey[] {
  return [
    'all',
    evalCase.anchor_type === 'DOCUMENT' ? 'document' : 'span',
    evalCase.messages && evalCase.messages.length > 0 ? 'multi_turn' : 'single_turn',
  ];
}

/**
 * Run report / comparison drawer (M4b-CONTRACTS.md section 5 item 4): a metrics comparison table
 * with one column per run/config, a 全体/span级/文档级/单轮/多轮 group switch, a per-case hit
 * drill-down table merged across the compared runs, a prominent degraded/待复核 counter, and CSV
 * export. Passing a single runId renders the same layout with one column.
 */
export default function EvalReportDrawer({ datasetId, runIds, onClose }: EvalReportDrawerProps) {
  const [loading, setLoading] = useState(false);
  const [comparable, setComparable] = useState(true);
  const [incomparableReason, setIncomparableReason] = useState<string | null>(null);
  const [runs, setRuns] = useState<EvalRun[]>([]);
  const [staleCaseCount, setStaleCaseCount] = useState(0);
  const [cases, setCases] = useState<Map<string, EvalCase>>(new Map());
  const [resultsByRun, setResultsByRun] = useState<Map<string, EvalResult[]>>(new Map());
  const [group, setGroup] = useState<MetricGroupKey>('all');

  useEffect(() => {
    if (!datasetId || !runIds || runIds.length === 0) {
      return;
    }
    let cancelled = false;
    setLoading(true);
    setGroup('all');
    (async () => {
      try {
        let loadedRuns: EvalRun[];
        if (runIds.length === 1) {
          loadedRuns = [await getEvalRun(runIds[0])];
          if (cancelled) return;
          setComparable(true);
          setIncomparableReason(null);
        } else {
          const compareResult = await compareEvalRuns(runIds);
          if (cancelled) return;
          if (!compareResult.comparable) {
            setComparable(false);
            setIncomparableReason(compareResult.reason);
            setRuns([]);
            setLoading(false);
            return;
          }
          loadedRuns = compareResult.runs;
          setComparable(true);
          setIncomparableReason(null);
        }
        const [allCases, staleCases, ...perRunResults] = await Promise.all([
          listAllEvalCases(datasetId),
          listAllEvalCases(datasetId, 'EVIDENCE_STALE'),
          ...loadedRuns.map((run) => listAllEvalResults(run.run_id)),
        ]);
        if (cancelled) return;
        setRuns(loadedRuns);
        setStaleCaseCount(staleCases.length);
        setCases(new Map(allCases.map((c) => [c.case_id, c])));
        setResultsByRun(new Map(loadedRuns.map((run, index) => [run.run_id, perRunResults[index]])));
      } catch {
        message.error('评测报告加载失败');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [datasetId, runIds]);

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

  const drilldownRows = useMemo<DrilldownRow[]>(() => {
    const caseIds = new Set<string>();
    resultsByRun.forEach((results) => results.forEach((r) => caseIds.add(r.case_id)));
    return Array.from(caseIds)
      .map((caseId) => cases.get(caseId))
      .filter((c): c is EvalCase => !!c)
      .filter((c) => caseGroups(c).includes(group))
      .map((c) => ({
        evalCase: c,
        perRun: new Map(runs.map((run) => [run.run_id, resultsByRun.get(run.run_id)?.find((r) => r.case_id === c.case_id)])),
      }));
  }, [resultsByRun, cases, runs, group]);

  const totalDegraded = useMemo(() => runs.reduce((sum, run) => sum + run.case_degraded, 0), [runs]);

  const handleExportMetrics = () => {
    const header = ['指标', ...runs.map((run) => run.retrieval_config.label)];
    const rows = metricRows.map((row) => [
      row.label,
      ...runs.map((run) => formatMetricPoint(run.metrics?.[group]?.[row.k]?.[row.metricKey])),
    ]);
    downloadCsv(`eval-metrics-${group}.csv`, [header, ...rows]);
  };

  const handleExportDrilldown = () => {
    const header = [
      'case_id',
      'query',
      '锚定类型',
      ...runs.map((run) => `${run.retrieval_config.label}-hit`),
      ...runs.map((run) => `${run.retrieval_config.label}-hit_rank`),
    ];
    const rows = drilldownRows.map((row) => [
      row.evalCase.case_id,
      row.evalCase.query,
      row.evalCase.anchor_type,
      ...runs.map((run) => (row.perRun.get(run.run_id)?.hit ? '1' : '0')),
      ...runs.map((run) => String(row.perRun.get(run.run_id)?.hit_rank ?? '')),
    ]);
    downloadCsv('eval-drilldown.csv', [header, ...rows]);
  };

  return (
    <Drawer title="评测报告" open={runIds !== null} onClose={onClose} width={960} destroyOnClose>
      <Spin spinning={loading}>
        {!comparable ? (
          <Alert
            type="warning"
            showIcon
            message="所选 run 不可比"
            description={incomparableReason ?? '语料版本（dataset_revision / corpus_fingerprint）不一致，请分别查看各 run 的报告'}
          />
        ) : runs.length === 0 ? (
          <Empty description="暂无数据" />
        ) : (
          <Space direction="vertical" style={{ width: '100%' }} size={16}>
            {(totalDegraded > 0 || staleCaseCount > 0) && (
              <Alert
                type="warning"
                showIcon
                message="存在需要关注的降级或待复核 case"
                description={
                  <Space direction="vertical">
                    {totalDegraded > 0 && (
                      <Typography.Text>本次展示的 run 累计 {totalDegraded} 条 case 命中判定发生过降级重试</Typography.Text>
                    )}
                    {staleCaseCount > 0 && (
                      <Typography.Text>该评测集当前有 {staleCaseCount} 条 case 待复核（evidence_stale）</Typography.Text>
                    )}
                  </Space>
                }
              />
            )}

            <Space wrap>
              {runs.map((run) => {
                const meta = metaOf(RUN_STATUS_META, run.status);
                return (
                  <Tag key={run.run_id} color={meta.color}>
                    {run.retrieval_config.label}：{meta.label}
                    {run.status === 'FAILED' && run.fail_reason ? `（${run.fail_reason}）` : ''}
                  </Tag>
                );
              })}
            </Space>

            <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
              <Radio.Group value={group} onChange={(e) => setGroup(e.target.value)} optionType="button">
                {GROUP_OPTIONS.map((g) => (
                  <Radio.Button key={g} value={g}>
                    {metaOf(METRIC_GROUP_META, g).label}
                  </Radio.Button>
                ))}
              </Radio.Group>
              <Space>
                <a onClick={handleExportMetrics}>
                  <DownloadOutlined /> 导出指标 CSV
                </a>
                <a onClick={handleExportDrilldown}>
                  <DownloadOutlined /> 导出命中明细 CSV
                </a>
              </Space>
            </Space>

            <Typography.Title level={5} style={{ marginBottom: 0 }}>
              指标对比
            </Typography.Title>
            <Table
              size="small"
              rowKey="key"
              pagination={false}
              dataSource={metricRows}
              scroll={{ x: true }}
              columns={[
                { title: '指标', dataIndex: 'label', fixed: 'left', width: 160 },
                ...runs.map((run) => ({
                  title: run.retrieval_config.label,
                  key: run.run_id,
                  width: 200,
                  render: (_: unknown, row: (typeof metricRows)[number]) =>
                    formatMetricPoint(run.metrics?.[group]?.[row.k]?.[row.metricKey]),
                })),
              ]}
            />

            <Typography.Title level={5} style={{ marginBottom: 0 }}>
              命中明细下钻
            </Typography.Title>
            <Table<DrilldownRow>
              size="small"
              rowKey={(row) => row.evalCase.case_id}
              dataSource={drilldownRows}
              scroll={{ x: true }}
              columns={[
                { title: 'query', width: 240, ellipsis: true, render: (_, row) => row.evalCase.query },
                { title: '锚定类型', width: 90, render: (_, row) => row.evalCase.anchor_type },
                ...runs.map((run) => ({
                  title: run.retrieval_config.label,
                  key: run.run_id,
                  width: 160,
                  render: (_: unknown, row: DrilldownRow) => {
                    const result = row.perRun.get(run.run_id);
                    if (!result) {
                      return <Typography.Text type="secondary">未跑</Typography.Text>;
                    }
                    return (
                      <Space direction="vertical" size={0}>
                        <Tag color={result.hit ? 'success' : 'default'}>
                          {result.hit ? `命中 #${result.hit_rank ?? '-'}` : '未命中'}
                        </Tag>
                        {result.degraded.length > 0 && <Tag color="warning">降级</Tag>}
                      </Space>
                    );
                  },
                })),
              ]}
            />
          </Space>
        )}
      </Spin>
    </Drawer>
  );
}
