// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CalculatorOutlined, PlayCircleOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Checkbox,
  Collapse,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Pagination,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { createEvalRuns, estimateEvalRun, listEvalRuns } from '../../../api/evalRun';
import type { CreateEvalRunRequest, EvalDataset, EvalMode, EvalRun, EvalRunConfig, EvalRunEstimate } from '../../../api/types';
import { EVAL_MODE_META, RUN_STATUS_META, metaOf } from '../../../utils/statusMeta';
import EvalReportDrawer from './EvalReportDrawer';

interface EvalRunTabProps {
  dataset: EvalDataset | null;
}

interface RunFormValues {
  k: number;
  modes: EvalMode[];
  recall_top_k: number;
  top_n: number;
  score_threshold_enabled: boolean;
  score_threshold: number;
  rewrite_enabled: boolean;
  judge_enabled: boolean;
  judge_model?: string;
}

const ALL_MODES: EvalMode[] = ['BM25_ONLY', 'VECTOR_ONLY', 'HYBRID', 'HYBRID_RERANK'];
const MODE_OPTIONS = ALL_MODES.map((mode) => ({ label: metaOf(EVAL_MODE_META, mode).label, value: mode }));

// Run list is polled at the same 3s cadence KbDetailPage/VersionDrawer already use, only while a
// submitted run is still PENDING/RUNNING.
const POLL_INTERVAL_MS = 3000;

/** Builds the shared CreateEvalRunRequest payload from the form's current values (one config per checked mode). */
function buildPayload(values: RunFormValues): CreateEvalRunRequest {
  const configs: EvalRunConfig[] = (values.modes ?? []).map((mode) => ({
    label: metaOf(EVAL_MODE_META, mode).label,
    mode,
    recall_top_k: values.recall_top_k,
    top_n: values.top_n,
    score_threshold: values.score_threshold_enabled ? values.score_threshold : null,
    rewrite_enabled: values.rewrite_enabled,
    fusion: mode === 'HYBRID' || mode === 'HYBRID_RERANK' ? 'rrf' : undefined,
  }));
  return {
    k: values.k,
    configs,
    judge: values.judge_enabled ? { enabled: true, model: values.judge_model || undefined } : undefined,
  };
}

/** Run submission + run history + report entry tab (M4b-CONTRACTS.md section 5 item 4). */
export default function EvalRunTab({ dataset }: EvalRunTabProps) {
  const datasetId = dataset?.dataset_id ?? null;
  const [form] = Form.useForm<RunFormValues>();
  const [estimate, setEstimate] = useState<EvalRunEstimate | null>(null);
  const [estimatedSnapshot, setEstimatedSnapshot] = useState<string | null>(null);
  const [estimating, setEstimating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [runs, setRuns] = useState<EvalRun[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [selectedRunIds, setSelectedRunIds] = useState<string[]>([]);
  const [reportRunIds, setReportRunIds] = useState<string[] | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadRuns = useCallback(async () => {
    if (!datasetId) {
      setRuns([]);
      setTotal(0);
      return;
    }
    setLoading(true);
    try {
      const result = await listEvalRuns(datasetId, page);
      setRuns(result.items);
      setTotal(result.total);
    } finally {
      setLoading(false);
    }
  }, [datasetId, page]);

  useEffect(() => {
    setPage(1);
    setSelectedRunIds([]);
    setEstimate(null);
    setEstimatedSnapshot(null);
  }, [datasetId]);

  useEffect(() => {
    loadRuns();
  }, [loadRuns]);

  const hasActiveRun = useMemo(() => runs.some((run) => run.status === 'PENDING' || run.status === 'RUNNING'), [runs]);

  useEffect(() => {
    if (!hasActiveRun) {
      return;
    }
    pollTimerRef.current = setInterval(() => loadRuns(), POLL_INTERVAL_MS);
    return () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
      }
    };
  }, [hasActiveRun, loadRuns]);

  const handleEstimate = async () => {
    if (!datasetId) {
      return;
    }
    const values = await form.validateFields();
    if (!values.modes || values.modes.length === 0) {
      message.warning('请至少勾选一种检索配置');
      return;
    }
    setEstimating(true);
    try {
      const payload = buildPayload(values);
      const result = await estimateEvalRun(datasetId, payload);
      setEstimate(result);
      setEstimatedSnapshot(JSON.stringify(payload));
    } finally {
      setEstimating(false);
    }
  };

  const handleSubmit = async () => {
    if (!datasetId) {
      return;
    }
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const payload = buildPayload(values);
      await createEvalRuns(datasetId, payload);
      message.success(`已提交 ${payload.configs.length} 个评测运行`);
      setEstimate(null);
      setEstimatedSnapshot(null);
      loadRuns();
    } finally {
      setSubmitting(false);
    }
  };

  if (!dataset) {
    return <Empty description="请先在「评测集管理」选择一个评测集" />;
  }

  return (
    <div>
      <Card title="新建评测运行" style={{ marginBottom: 16 }}>
        <Form<RunFormValues>
          form={form}
          layout="vertical"
          initialValues={{
            k: 5,
            modes: ['BM25_ONLY'],
            recall_top_k: 50,
            top_n: 5,
            score_threshold_enabled: false,
            score_threshold: 0.5,
            rewrite_enabled: false,
            judge_enabled: false,
          }}
          onValuesChange={() => {
            // Any change invalidates the previous estimate; submit re-locks until re-estimated.
            setEstimate(null);
            setEstimatedSnapshot(null);
          }}
        >
          <Form.Item name="k" label="K 值" rules={[{ required: true, message: '请输入 K 值' }]}>
            <InputNumber min={1} max={50} style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="modes" label="配置矩阵（可多选，一次提交每个勾选项各产生一个 run）" rules={[{ required: true, message: '请至少勾选一种配置' }]}>
            <Checkbox.Group options={MODE_OPTIONS} />
          </Form.Item>

          <Collapse
            style={{ marginBottom: 16 }}
            items={[
              {
                key: 'advanced',
                label: '高级参数（应用于所选全部配置）',
                children: (
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Form.Item name="recall_top_k" label="recall_top_k" style={{ marginBottom: 8 }}>
                      <InputNumber min={1} max={200} style={{ width: 160 }} />
                    </Form.Item>
                    <Form.Item name="top_n" label="top_n" style={{ marginBottom: 8 }}>
                      <InputNumber min={1} max={50} style={{ width: 160 }} />
                    </Form.Item>
                    <Form.Item name="rewrite_enabled" label="启用查询改写" valuePropName="checked" style={{ marginBottom: 8 }}>
                      <Switch />
                    </Form.Item>
                    <Form.Item name="score_threshold_enabled" label="启用阈值过滤" valuePropName="checked" style={{ marginBottom: 8 }}>
                      <Switch />
                    </Form.Item>
                    <Form.Item name="score_threshold" label="score_threshold" style={{ marginBottom: 0 }}>
                      <InputNumber min={0.01} max={1} step={0.01} style={{ width: 160 }} />
                    </Form.Item>
                  </Space>
                ),
              },
              {
                key: 'judge',
                label: 'LLM-as-judge（可选）',
                children: (
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Form.Item name="judge_enabled" label="启用 judge 打分" valuePropName="checked" style={{ marginBottom: 8 }}>
                      <Switch />
                    </Form.Item>
                    <Form.Item name="judge_model" label="judge 模型（可选，留空用系统默认）" style={{ marginBottom: 0 }}>
                      <Input placeholder="留空使用 EVAL_JUDGE_MODEL 默认配置" style={{ width: 280 }} />
                    </Form.Item>
                  </Space>
                ),
              },
            ]}
          />

          <Space>
            <Button icon={<CalculatorOutlined />} loading={estimating} onClick={handleEstimate}>
              预估调用次数
            </Button>
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              loading={submitting}
              disabled={estimatedSnapshot === null}
              onClick={handleSubmit}
            >
              提交创建 Run
            </Button>
            {estimatedSnapshot === null && <Typography.Text type="secondary">请先点击"预估调用次数"确认费用</Typography.Text>}
          </Space>

          {estimate && (
            <Descriptions size="small" bordered column={2} style={{ marginTop: 16 }}>
              <Descriptions.Item label="嵌入调用次数">{estimate.embedding_calls}</Descriptions.Item>
              <Descriptions.Item label="重排调用次数">{estimate.rerank_calls}</Descriptions.Item>
              <Descriptions.Item label="改写调用次数">{estimate.rewrite_calls}</Descriptions.Item>
              <Descriptions.Item label="judge 调用次数">{estimate.judge_calls}</Descriptions.Item>
            </Descriptions>
          )}
        </Form>
      </Card>

      <Card
        title="运行历史"
        extra={
          <Button type="primary" disabled={selectedRunIds.length < 2} onClick={() => setReportRunIds(selectedRunIds)}>
            对比所选（{selectedRunIds.length}）
          </Button>
        }
      >
        <Table<EvalRun>
          rowKey="run_id"
          loading={loading}
          dataSource={runs}
          pagination={false}
          rowSelection={{ selectedRowKeys: selectedRunIds, onChange: (keys) => setSelectedRunIds(keys as string[]) }}
          columns={[
            { title: '配置', dataIndex: 'retrieval_config', width: 140, render: (config: EvalRunConfig) => config.label },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: (status: EvalRun['status'], record: EvalRun) => {
                const meta = metaOf(RUN_STATUS_META, status);
                const tag = <Tag color={meta.color}>{meta.label}</Tag>;
                return status === 'FAILED' && record.fail_reason ? (
                  <span title={record.fail_reason}>{tag}</span>
                ) : (
                  tag
                );
              },
            },
            { title: 'revision', dataIndex: 'dataset_revision', width: 90 },
            { title: 'case 总数', dataIndex: 'case_total', width: 90 },
            { title: '有效', dataIndex: 'case_effective', width: 80 },
            {
              title: '待复核',
              dataIndex: 'case_stale',
              width: 80,
              render: (staleCount: number) => (staleCount > 0 ? <Tag color="warning">{staleCount}</Tag> : staleCount),
            },
            {
              title: '降级',
              dataIndex: 'case_degraded',
              width: 80,
              render: (degradedCount: number) => (degradedCount > 0 ? <Tag color="error">{degradedCount}</Tag> : degradedCount),
            },
            { title: '创建时间', dataIndex: 'created_at', width: 170 },
            {
              title: '操作',
              width: 100,
              render: (_, record: EvalRun) => (
                <Button
                  size="small"
                  disabled={record.status === 'PENDING' || record.status === 'RUNNING'}
                  onClick={() => setReportRunIds([record.run_id])}
                >
                  查看报告
                </Button>
              ),
            },
          ]}
        />
        {total > 0 && (
          <Pagination style={{ marginTop: 16, textAlign: 'right' }} current={page} total={total} onChange={setPage} showSizeChanger={false} />
        )}
      </Card>

      <EvalReportDrawer datasetId={datasetId} runIds={reportRunIds} onClose={() => setReportRunIds(null)} />
    </div>
  );
}
