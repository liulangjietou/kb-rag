// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { Button, Form, Input, Modal, Popconfirm, Radio, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { createEvalDataset, listEvalDatasets } from '../../../api/evalDataset';
import {
  convertRetrievalFeedback,
  dismissRetrievalFeedback,
  listRetrievalFeedback,
} from '../../../api/retrievalFeedback';
import type { EvalDataset, FeedbackChannel, FeedbackStatus, FeedbackVerdict, RetrievalFeedbackEntry } from '../../../api/types';
import { FEEDBACK_STATUS_META, FEEDBACK_VERDICT_META, metaOf } from '../../../utils/statusMeta';

interface FeedbackTabProps {
  kbId: string;
}

type TargetMode = 'existing' | 'new';

interface ConvertFormValues {
  target_mode: TargetMode;
  dataset_id?: string;
  new_dataset_name?: string;
}

const PAGE_SIZE = 20;

// M16: feedback now arrives from two doors -- the console debug page and the open API.
const FEEDBACK_CHANNEL_META: Record<FeedbackChannel, { label: string; color: string }> = {
  CONSOLE: { label: '控制台', color: 'blue' },
  OPEN_API: { label: '开放API', color: 'purple' },
};

/**
 * 反馈管理 tab of the KB detail page (M10-CONTRACTS.md section 3): lists the persisted good/bad
 * verdicts the debug page submitted, with a convert-to-eval-case action for GOOD rows (dataset
 * picker modelled on the debug page's CollectToEvalModal) and a dismiss action. Both actions are
 * terminal server-side, so the buttons only render while a row is still NEW.
 */
export default function FeedbackTab({ kbId }: FeedbackTabProps) {
  const [items, setItems] = useState<RetrievalFeedbackEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [verdictFilter, setVerdictFilter] = useState<FeedbackVerdict | undefined>();
  const [statusFilter, setStatusFilter] = useState<FeedbackStatus | undefined>();
  const [channelFilter, setChannelFilter] = useState<FeedbackChannel | undefined>();
  // Row being converted; doubles as the modal's open flag.
  const [converting, setConverting] = useState<RetrievalFeedbackEntry | null>(null);
  const [datasets, setDatasets] = useState<EvalDataset[]>([]);
  const [loadingDatasets, setLoadingDatasets] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<ConvertFormValues>();
  const targetMode = Form.useWatch('target_mode', form) ?? 'existing';

  const load = useCallback(async (
    targetPage: number,
    verdict?: FeedbackVerdict,
    status?: FeedbackStatus,
    channel?: FeedbackChannel,
  ) => {
    setLoading(true);
    try {
      const result = await listRetrievalFeedback(kbId, {
        verdict,
        status,
        channel,
        page: targetPage,
        size: PAGE_SIZE,
      });
      setItems(result.items);
      setTotal(result.total);
      setPage(targetPage);
    } finally {
      setLoading(false);
    }
  }, [kbId]);

  useEffect(() => {
    load(1, verdictFilter, statusFilter, channelFilter);
  }, [load, verdictFilter, statusFilter, channelFilter]);

  const openConvert = (row: RetrievalFeedbackEntry) => {
    setConverting(row);
    form.setFieldsValue({ target_mode: 'existing', dataset_id: undefined, new_dataset_name: undefined });
    setLoadingDatasets(true);
    listEvalDatasets(kbId)
      .then((result) => {
        setDatasets(result);
        form.setFieldsValue({ target_mode: result.length > 0 ? 'existing' : 'new' });
      })
      .finally(() => setLoadingDatasets(false));
  };

  const handleConvert = async () => {
    if (!converting) return;
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const datasetId =
        values.target_mode === 'new'
          ? (await createEvalDataset(kbId, { name: values.new_dataset_name! })).dataset_id
          : values.dataset_id!;
      await convertRetrievalFeedback(converting.feedback_id, { dataset_id: datasetId });
      message.success('已转入评测集（case source=FEEDBACK）');
      setConverting(null);
      form.resetFields();
      load(page, verdictFilter, statusFilter, channelFilter);
    } finally {
      setSubmitting(false);
    }
  };

  const handleDismiss = async (row: RetrievalFeedbackEntry) => {
    await dismissRetrievalFeedback(row.feedback_id);
    message.success('已忽略该反馈');
    load(page, verdictFilter, statusFilter, channelFilter);
  };

  return (
    <>
      <Space style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="好/坏筛选"
          style={{ width: 140 }}
          value={verdictFilter}
          onChange={(value) => setVerdictFilter(value)}
          options={[
            { label: '好结果', value: 'GOOD' },
            { label: '坏结果', value: 'BAD' },
          ]}
        />
        <Select
          allowClear
          placeholder="状态筛选"
          style={{ width: 140 }}
          value={statusFilter}
          onChange={(value) => setStatusFilter(value)}
          options={[
            { label: '待处理', value: 'NEW' },
            { label: '已转评测集', value: 'CONVERTED' },
            { label: '已忽略', value: 'DISMISSED' },
          ]}
        />
        <Select
          allowClear
          placeholder="渠道筛选"
          style={{ width: 140 }}
          value={channelFilter}
          onChange={(value) => setChannelFilter(value)}
          options={[
            { label: '控制台', value: 'CONSOLE' },
            { label: '开放API', value: 'OPEN_API' },
          ]}
        />
        <Button onClick={() => load(page, verdictFilter, statusFilter, channelFilter)}>刷新</Button>
      </Space>

      <Table<RetrievalFeedbackEntry>
        rowKey="feedback_id"
        loading={loading}
        dataSource={items}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total,
          showSizeChanger: false,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (nextPage) => load(nextPage, verdictFilter, statusFilter, channelFilter),
        }}
        columns={[
          {
            title: '检索问题',
            dataIndex: 'query',
            ellipsis: { showTitle: false },
            render: (query: string) => (
              <Tooltip title={query} placement="topLeft">
                {query}
              </Tooltip>
            ),
          },
          {
            title: '判定',
            dataIndex: 'verdict',
            width: 100,
            render: (verdict: FeedbackVerdict) => {
              const meta = metaOf(FEEDBACK_VERDICT_META, verdict);
              return <Tag color={meta.color}>{meta.label}</Tag>;
            },
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 120,
            render: (status: FeedbackStatus, record) => {
              const meta = metaOf(FEEDBACK_STATUS_META, status);
              const tag = <Tag color={meta.color}>{meta.label}</Tag>;
              return record.converted_case_id ? (
                <Tooltip title={`case: ${record.converted_case_id}`}>{tag}</Tooltip>
              ) : (
                tag
              );
            },
          },
          {
            title: '渠道',
            dataIndex: 'channel',
            width: 110,
            render: (channel: FeedbackChannel, record) => {
              const meta = FEEDBACK_CHANNEL_META[channel] ?? { label: channel, color: 'default' };
              const tag = <Tag color={meta.color}>{meta.label}</Tag>;
              // Open-API rows carry the caller-supplied end-user id, worth surfacing when tracing
              // a complaint back to whoever raised it.
              return record.end_user_id ? (
                <Tooltip title={`终端用户：${record.end_user_id}`}>{tag}</Tooltip>
              ) : (
                tag
              );
            },
          },
          {
            title: '来源分片',
            dataIndex: 'chunk_id',
            width: 220,
            render: (chunkId: string, record) => (
              <Typography.Text type={record.doc_id ? undefined : 'secondary'} copyable={{ text: chunkId }}>
                {record.doc_id ? chunkId : `${chunkId}（原文档已删除）`}
              </Typography.Text>
            ),
          },
          { title: '提交时间', dataIndex: 'created_at', width: 180 },
          {
            title: '操作',
            width: 200,
            render: (_, record) =>
              record.status === 'NEW' ? (
                <Space>
                  {record.verdict === 'GOOD' && (
                    <Button size="small" type="link" onClick={() => openConvert(record)}>
                      转入评测集
                    </Button>
                  )}
                  <Popconfirm
                    title="忽略该反馈？"
                    description="忽略后不可恢复，该反馈将不再出现在待处理列表"
                    okText="忽略"
                    cancelText="取消"
                    onConfirm={() => handleDismiss(record)}
                  >
                    <Button size="small" type="link" danger>
                      忽略
                    </Button>
                  </Popconfirm>
                </Space>
              ) : null,
          },
        ]}
      />

      <Modal
        title="转入评测集"
        open={converting !== null}
        onOk={handleConvert}
        onCancel={() => setConverting(null)}
        confirmLoading={submitting}
        okText="转入"
        cancelText="取消"
        destroyOnClose
      >
        <Typography.Paragraph type="secondary">
          将该条好结果反馈（query + 命中分片）转成一条评测 case（source=FEEDBACK），反馈状态随之变为「已转评测集」。
        </Typography.Paragraph>
        <Form<ConvertFormValues> form={form} layout="vertical">
          <Form.Item name="target_mode" label="目标评测集">
            <Radio.Group>
              <Radio value="existing" disabled={datasets.length === 0}>
                选择已有评测集
              </Radio>
              <Radio value="new">新建评测集</Radio>
            </Radio.Group>
          </Form.Item>
          {targetMode === 'existing' ? (
            <Form.Item name="dataset_id" rules={[{ required: true, message: '请选择评测集' }]}>
              <Select
                loading={loadingDatasets}
                placeholder={datasets.length === 0 ? '该知识库暂无评测集，请先新建' : '请选择评测集'}
                options={datasets.map((dataset) => ({ label: dataset.name, value: dataset.dataset_id }))}
              />
            </Form.Item>
          ) : (
            <Form.Item name="new_dataset_name" rules={[{ required: true, message: '请输入新评测集名称' }]}>
              <Input placeholder="新评测集名称" maxLength={64} />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </>
  );
}
