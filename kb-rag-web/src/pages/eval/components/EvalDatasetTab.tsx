// Author: owlzhangfq@gmail.com
import { useState } from 'react';
import { DownloadOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Form, Input, Modal, Popconfirm, Space, Table, Tag, Typography, message } from 'antd';
import { createEvalDataset, deleteEvalDataset, importDemoEvalDataset } from '../../../api/evalDataset';
import type { CreateEvalDatasetRequest, EvalDataset } from '../../../api/types';
import { RUN_STATUS_META, metaOf } from '../../../utils/statusMeta';

interface EvalDatasetTabProps {
  kbId: string;
  datasets: EvalDataset[];
  loading: boolean;
  currentDatasetId: string | null;
  onSelectDataset: (datasetId: string) => void;
  onChanged: () => void;
}

/**
 * Dataset management tab (M4b-CONTRACTS.md section 5 item 1): list (name/case_count/revision/last
 * run) + create + delete + one-click Demo import. Selecting a row here becomes the "current
 * dataset" the other three eval-center tabs read from.
 */
export default function EvalDatasetTab({
  kbId,
  datasets,
  loading,
  currentDatasetId,
  onSelectDataset,
  onChanged,
}: EvalDatasetTabProps) {
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [form] = Form.useForm<CreateEvalDatasetRequest>();

  const handleCreate = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const created = await createEvalDataset(kbId, values);
      message.success('评测集创建成功');
      form.resetFields();
      setCreateOpen(false);
      onChanged();
      onSelectDataset(created.dataset_id);
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (datasetId: string) => {
    await deleteEvalDataset(datasetId);
    message.success('评测集已删除');
    onChanged();
  };

  const handleImportDemo = async () => {
    setImporting(true);
    try {
      const result = await importDemoEvalDataset(kbId);
      if (result.already_existed) {
        message.info('Demo 评测集已存在，未重复导入');
      } else {
        const skippedHint = result.skipped.length > 0 ? `，跳过 ${result.skipped.length} 条（未匹配到文档）` : '';
        message.success(`Demo 评测集导入成功，共 ${result.imported_case_count} 条${skippedHint}`);
      }
      onChanged();
      onSelectDataset(result.dataset_id);
    } finally {
      setImporting(false);
    }
  };

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建评测集
        </Button>
        <Button icon={<DownloadOutlined />} loading={importing} onClick={handleImportDemo}>
          导入 Demo 评测集
        </Button>
      </Space>

      <Table<EvalDataset>
        rowKey="dataset_id"
        loading={loading}
        dataSource={datasets}
        pagination={false}
        rowClassName={(record) => (record.dataset_id === currentDatasetId ? 'ant-table-row-selected' : '')}
        columns={[
          { title: '名称', dataIndex: 'name' },
          { title: 'case 数', dataIndex: 'case_count', width: 90 },
          { title: 'revision', dataIndex: 'dataset_revision', width: 100 },
          {
            title: '最近一次 run',
            dataIndex: 'last_run',
            width: 220,
            render: (lastRun: EvalDataset['last_run']) => {
              if (!lastRun) {
                return <Typography.Text type="secondary">暂无</Typography.Text>;
              }
              const meta = metaOf(RUN_STATUS_META, lastRun.status);
              return (
                <Space>
                  <Tag color={meta.color}>{meta.label}</Tag>
                  <Typography.Text type="secondary">{lastRun.finished_at ?? '-'}</Typography.Text>
                </Space>
              );
            },
          },
          { title: '创建时间', dataIndex: 'created_at', width: 170 },
          {
            title: '操作',
            width: 200,
            render: (_, record: EvalDataset) => (
              <Space>
                <Button
                  size="small"
                  type={record.dataset_id === currentDatasetId ? 'primary' : 'default'}
                  onClick={() => onSelectDataset(record.dataset_id)}
                >
                  {record.dataset_id === currentDatasetId ? '当前评测集' : '设为当前'}
                </Button>
                <Popconfirm
                  title="确认删除该评测集？"
                  description="将级联删除其下全部 case 与运行记录，此操作不可恢复"
                  okText="删除"
                  okType="danger"
                  cancelText="取消"
                  onConfirm={() => handleDelete(record.dataset_id)}
                >
                  <Button size="small" danger>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title="新建评测集"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => setCreateOpen(false)}
        confirmLoading={submitting}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form<CreateEvalDatasetRequest> form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入评测集名称' }]}>
            <Input placeholder="例如：核心问答评测集" maxLength={64} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea placeholder="选填" maxLength={256} rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
