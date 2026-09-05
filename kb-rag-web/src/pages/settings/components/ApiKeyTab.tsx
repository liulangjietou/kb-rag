// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { CopyOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import { createApiKey, deleteApiKey, listApiKeys, rotateApiKey, updateApiKeyScope, updateApiKeyStatus } from '../../../api/apiKey';
import { listApps } from '../../../api/app';
import type { ApiKey, CreateApiKeyRequest, KbApp } from '../../../api/types';
import { API_KEY_STATUS_META, metaOf } from '../../../utils/statusMeta';

interface CreateApiKeyFormValues {
  name: string;
  qps_limit: number;
  scope_all: boolean;
  app_scope: string[];
}

/**
 * API Key 管理 tab (M4c-CONTRACTS.md sections 1/3/4): create (one-time plaintext + copy), list
 * (the server's pre-elided prefix), disable/enable, rotate, delete, and app_scope multi-select.
 */
export default function ApiKeyTab() {
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [apps, setApps] = useState<KbApp[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [secretModal, setSecretModal] = useState<string | null>(null);
  const [scopeEditKey, setScopeEditKey] = useState<ApiKey | null>(null);
  const [scopeForm] = Form.useForm<{ scope_all: boolean; app_scope: string[] }>();
  const [form] = Form.useForm<CreateApiKeyFormValues>();
  const scopeAll = Form.useWatch('scope_all', form) ?? true;
  const scopeAllEdit = Form.useWatch('scope_all', scopeForm) ?? true;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listApiKeys();
      setKeys(result);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    listApps().then(setApps);
  }, [load]);

  const appOptions = apps.map((app) => ({ label: app.name, value: app.app_id }));

  const handleCreate = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      const payload: CreateApiKeyRequest = {
        name: values.name,
        qps_limit: values.qps_limit,
        app_scope: values.scope_all ? null : values.app_scope,
      };
      const created = await createApiKey(payload);
      message.success('API Key 创建成功');
      form.resetFields();
      setCreateOpen(false);
      setSecretModal(created.api_key);
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const handleToggleStatus = async (key: ApiKey, enabled: boolean) => {
    await updateApiKeyStatus(key.key_id, enabled ? 'ENABLED' : 'DISABLED');
    message.success(enabled ? '已启用' : '已禁用，旧调用将立即失效');
    load();
  };

  const handleRotate = async (keyId: string) => {
    const rotated = await rotateApiKey(keyId);
    message.success('已轮换，旧密钥立即失效');
    setSecretModal(rotated.api_key);
    load();
  };

  const handleDelete = async (keyId: string) => {
    await deleteApiKey(keyId);
    message.success('已删除');
    load();
  };

  const openScopeEdit = (key: ApiKey) => {
    setScopeEditKey(key);
    scopeForm.setFieldsValue({ scope_all: !key.app_scope || key.app_scope.length === 0, app_scope: key.app_scope ?? [] });
  };

  const handleSaveScope = async () => {
    if (!scopeEditKey) return;
    const values = await scopeForm.validateFields();
    await updateApiKeyScope(scopeEditKey.key_id, { app_scope: values.scope_all ? null : values.app_scope });
    message.success('授权范围已更新');
    setScopeEditKey(null);
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
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建 API Key
        </Button>
      </div>

      <Table<ApiKey>
        rowKey="key_id"
        loading={loading}
        dataSource={keys}
        pagination={false}
        columns={[
          { title: '名称', dataIndex: 'name' },
          {
            // `prefix` already arrives pre-elided as "kb-sk-58e086…5a4a"; the server keeps only the
            // hash, so there is no separate last-4 field to append.
            title: 'Key',
            width: 180,
            render: (_, record) => <Typography.Text code>{record.prefix}</Typography.Text>,
          },
          {
            title: '状态',
            width: 140,
            render: (_, record) => {
              const meta = metaOf(API_KEY_STATUS_META, record.status);
              return (
                <Space>
                  <Tag color={meta.color}>{meta.label}</Tag>
                  <Switch
                    size="small"
                    checked={record.status === 'ENABLED'}
                    onChange={(checked) => handleToggleStatus(record, checked)}
                  />
                </Space>
              );
            },
          },
          { title: 'QPS 限额', dataIndex: 'qps_limit', width: 90 },
          {
            title: '授权范围',
            width: 200,
            render: (_, record) =>
              // Server semantics (ApiKeyService/ApiKeyResponse javadoc): empty OR null scope
              // authorises every application; the server serialises the all-apps state as [].
              // There is no "authorises nothing" state to render.
              !record.app_scope || record.app_scope.length === 0 ? (
                <Tag>全部应用</Tag>
              ) : (
                <Space wrap>
                  {record.app_scope.map((appId) => (
                    <Tag key={appId}>{apps.find((a) => a.app_id === appId)?.name ?? appId}</Tag>
                  ))}
                </Space>
              ),
          },
          { title: '最近使用', dataIndex: 'last_used_at', width: 170, render: (v: string | null) => v ?? '从未使用' },
          {
            title: '操作',
            width: 240,
            render: (_, record) => (
              <Space>
                <Button size="small" onClick={() => openScopeEdit(record)}>
                  编辑范围
                </Button>
                <Popconfirm title="确认轮换？旧密钥将立即失效" okText="轮换" cancelText="取消" onConfirm={() => handleRotate(record.key_id)}>
                  <Button size="small">轮换</Button>
                </Popconfirm>
                <Popconfirm title="确认删除该 Key？" okText="删除" okType="danger" cancelText="取消" onConfirm={() => handleDelete(record.key_id)}>
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
        title="新建 API Key"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => setCreateOpen(false)}
        confirmLoading={submitting}
        okText="创建"
        cancelText="取消"
        destroyOnHidden
      >
        <Form<CreateApiKeyFormValues> form={form} layout="vertical" initialValues={{ qps_limit: 10, scope_all: true, app_scope: [] }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：客服机器人 Agent 侧调用" maxLength={64} />
          </Form.Item>
          <Form.Item name="qps_limit" label="QPS 限额" rules={[{ required: true, message: '请输入 QPS 限额' }]}>
            <InputNumber min={1} max={1000} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="scope_all" label="授权全部应用" valuePropName="checked">
            <Switch />
          </Form.Item>
          {!scopeAll && (
            <Form.Item name="app_scope" label="授权范围（指定应用）" rules={[{ required: true, message: '请至少选择一个应用' }]}>
              <Select mode="multiple" placeholder="请选择授权应用" options={appOptions} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      <Modal
        title="密钥明文（仅展示一次）"
        open={secretModal !== null}
        onCancel={() => setSecretModal(null)}
        footer={
          <Button type="primary" onClick={() => setSecretModal(null)}>
            我已保存，关闭
          </Button>
        }
        destroyOnHidden
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

      <Modal
        title="编辑授权范围"
        open={scopeEditKey !== null}
        onCancel={() => setScopeEditKey(null)}
        onOk={handleSaveScope}
        okText="保存"
        cancelText="取消"
        destroyOnHidden
      >
        <Form form={scopeForm} layout="vertical">
          <Form.Item name="scope_all" label="授权全部应用" valuePropName="checked">
            <Switch />
          </Form.Item>
          {!scopeAllEdit && (
            <Form.Item name="app_scope" label="授权范围（指定应用）">
              <Select mode="multiple" placeholder="请选择授权应用" options={appOptions} />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
}
