// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { CopyOutlined, PlusOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  deleteMemoryKey,
  issueMemoryKey,
  listMemoryKeys,
  rotateMemoryKey,
  updateMemoryKeyStatus,
} from '../../../api/memory';
import type { MemoryAppKey, MemoryAppKeyCreateRequest } from '../../../api/types';

interface Props {
  libraryId: string;
  canWrite: boolean;
}

/**
 * Memory Key tab: keys scoped to this single library (kb-mk-*), consumed by external agents
 * against the open API. Mirrors ApiKeyTab: one-time plaintext on issue/rotate, status switch,
 * per-key QPS limit; the server stores only the SHA-256 hash.
 */
export default function MemoryKeysTab({ libraryId, canWrite }: Props) {
  const [keys, setKeys] = useState<MemoryAppKey[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [secretModal, setSecretModal] = useState<string | null>(null);
  const [form] = Form.useForm<MemoryAppKeyCreateRequest>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setKeys(await listMemoryKeys(libraryId));
    } finally {
      setLoading(false);
    }
  }, [libraryId]);

  useEffect(() => {
    load();
  }, [load]);

  const handleCreate = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const issued = await issueMemoryKey(libraryId, values);
      message.success('Memory Key 签发成功');
      form.resetFields();
      setCreateOpen(false);
      setSecretModal(issued.api_key);
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const handleToggleStatus = async (key: MemoryAppKey, enabled: boolean) => {
    await updateMemoryKeyStatus(libraryId, key.key_id, enabled ? 'ENABLED' : 'DISABLED');
    message.success(enabled ? '已启用' : '已禁用，使用该 Key 的调用将立即失效');
    load();
  };

  const handleRotate = async (keyId: string) => {
    const rotated = await rotateMemoryKey(libraryId, keyId);
    message.success('已轮换，旧密钥立即失效');
    setSecretModal(rotated.api_key);
    load();
  };

  const handleDelete = async (keyId: string) => {
    await deleteMemoryKey(libraryId, keyId);
    message.success('已删除');
    load();
  };

  const handleCopy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      message.success('已复制到剪贴板');
    } catch {
      message.error('复制失败，请手动选择复制');
    }
  };

  return (
    <div>
      <Alert
        style={{ marginBottom: 16 }}
        type="info"
        showIcon
        message="Memory Key 仅授权本记忆库的开放 API（/api/v1/memory/**），供外部智能体以 Authorization: Bearer kb-mk-… 方式调用"
      />
      {canWrite && (
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            签发 Memory Key
          </Button>
        </div>
      )}

      <Table<MemoryAppKey>
        rowKey="key_id"
        loading={loading}
        dataSource={keys}
        pagination={false}
        columns={[
          { title: '名称', dataIndex: 'name' },
          {
            title: 'Key',
            width: 200,
            render: (_, record) => <Typography.Text code>{record.key_prefix}</Typography.Text>,
          },
          {
            title: '状态',
            width: 140,
            render: (_, record) => (
              <Space>
                <Tag color={record.status === 'ENABLED' ? 'green' : 'red'}>
                  {record.status === 'ENABLED' ? '已启用' : '已禁用'}
                </Tag>
                {canWrite && (
                  <Switch
                    size="small"
                    checked={record.status === 'ENABLED'}
                    onChange={(checked) => handleToggleStatus(record, checked)}
                  />
                )}
              </Space>
            ),
          },
          { title: 'QPS 限额', dataIndex: 'qps_limit', width: 90 },
          {
            title: '最近使用',
            dataIndex: 'last_used_at',
            width: 170,
            render: (v: string | null) => v ?? '从未使用',
          },
          ...(canWrite
            ? [
                {
                  title: '操作',
                  width: 160,
                  render: (_: unknown, record: MemoryAppKey) => (
                    <Space>
                      <Popconfirm
                        title="确认轮换？旧密钥将立即失效"
                        okText="轮换"
                        cancelText="取消"
                        onConfirm={() => handleRotate(record.key_id)}
                      >
                        <Button size="small">轮换</Button>
                      </Popconfirm>
                      <Popconfirm
                        title="确认删除该 Key？"
                        okText="删除"
                        okType="danger"
                        cancelText="取消"
                        onConfirm={() => handleDelete(record.key_id)}
                      >
                        <Button size="small" danger>
                          删除
                        </Button>
                      </Popconfirm>
                    </Space>
                  ),
                },
              ]
            : []),
        ]}
      />

      <Modal
        rootClassName="catalog-eval-modal"
        title="签发 Memory Key"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => setCreateOpen(false)}
        confirmLoading={submitting}
        okText="签发"
        cancelText="取消"
        destroyOnClose
      >
        <Form<MemoryAppKeyCreateRequest> form={form} layout="vertical" initialValues={{ qps_limit: 10 }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：客服机器人 Agent 侧调用" maxLength={64} />
          </Form.Item>
          <Form.Item name="qps_limit" label="QPS 限额" rules={[{ required: true, message: '请输入 QPS 限额' }]}>
            <InputNumber min={1} max={1000} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        rootClassName="catalog-eval-modal catalog-secret-modal"
        title="密钥明文（仅展示一次）"
        open={secretModal !== null}
        onCancel={() => setSecretModal(null)}
        footer={
          <Button type="primary" onClick={() => setSecretModal(null)}>
            我已保存，关闭
          </Button>
        }
        destroyOnClose
      >
        {secretModal && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Alert type="warning" showIcon message="请立即复制并妥善保存，关闭后将无法再次查看完整密钥" />
            <Space.Compact style={{ width: '100%' }}>
              <Input readOnly value={secretModal} />
              <Button icon={<CopyOutlined />} onClick={() => handleCopy(secretModal)}>
                复制
              </Button>
            </Space.Compact>
          </Space>
        )}
      </Modal>
    </div>
  );
}
