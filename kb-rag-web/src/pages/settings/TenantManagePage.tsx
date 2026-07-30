// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { createTenant, listTenants, renameTenant, updateTenantStatus } from '../../api/tenant';
import type { TenantSummary } from '../../api/types';

interface TenantFormValues {
  code: string;
  name: string;
}

/**
 * Tenant administration (M16-CONTRACTS.md section 3): the isolation boundaries of the deployment.
 *
 * <p>A tenant is a wall, not a folder: accounts, knowledge bases and their physical indexes all
 * live inside exactly one, and nothing inside is visible from another. That is why the page offers
 * no delete -- a tenant that has ever held data is disabled, never removed, so its rows stay
 * attributable. The built-in default tenant hosts everything that predates M16 and can be neither
 * renamed away nor disabled.
 */
export default function TenantManagePage() {
  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<TenantSummary | null>(null);
  const [form] = Form.useForm<TenantFormValues>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setTenants(await listTenants());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openRename = (record: TenantSummary) => {
    setEditing(record);
    form.setFieldsValue({ code: record.code, name: record.name });
    setModalOpen(true);
  };

  const submit = async (values: TenantFormValues) => {
    setSubmitting(true);
    try {
      if (editing) {
        // The server only reads `name` on a rename; code is sent back untouched because the
        // request body validates both fields on either call.
        await renameTenant(editing.tenant_id, { code: editing.code, name: values.name });
        message.success('租户名称已更新');
      } else {
        await createTenant({ code: values.code, name: values.name });
        message.success('租户已创建');
      }
      setModalOpen(false);
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const toggleStatus = async (record: TenantSummary) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    await updateTenantStatus(record.tenant_id, next);
    message.success(next === 'DISABLED' ? '租户已停用，该租户账号将无法登录' : '租户已启用');
    load();
  };

  const columns: ColumnsType<TenantSummary> = [
    {
      title: '租户',
      dataIndex: 'name',
      render: (value: string, record) => (
        <Space size={4}>
          <Typography.Text strong>{value}</Typography.Text>
          {record.builtin && <Tag color="blue">内置</Tag>}
        </Space>
      ),
    },
    {
      title: '编码',
      dataIndex: 'code',
      render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: string) =>
        value === 'ENABLED' ? <Tag color="success">启用</Tag> : <Tag color="default">停用</Tag>,
    },
    { title: '创建时间', dataIndex: 'created_at', width: 200 },
    {
      title: '操作',
      key: 'actions',
      width: 140,
      render: (_, record) => (
        <Space size={8}>
          <a onClick={() => openRename(record)}>改名</a>
          {record.builtin ? (
            <Tooltip title="内置默认租户不可停用">
              <Typography.Text type="secondary">停用</Typography.Text>
            </Tooltip>
          ) : record.status === 'ENABLED' ? (
            <Popconfirm
              title="确认停用该租户？"
              description="停用后该租户下所有账号无法登录，已有数据保留不动"
              okText="停用"
              okType="danger"
              cancelText="取消"
              onConfirm={() => toggleStatus(record)}
            >
              <Typography.Link type="danger">停用</Typography.Link>
            </Popconfirm>
          ) : (
            <a onClick={() => toggleStatus(record)}>启用</a>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="租户管理"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建租户
          </Button>
        </Space>
      }
    >
      <Table<TenantSummary>
        rowKey="tenant_id"
        loading={loading}
        columns={columns}
        dataSource={tenants}
        pagination={false}
        scroll={{ x: 720 }}
      />

      <Modal
        open={modalOpen}
        title={editing ? `租户改名 - ${editing.name}` : '新建租户'}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form<TenantFormValues> form={form} layout="vertical" onFinish={submit} preserve={false}>
          <Form.Item
            name="code"
            label="租户编码"
            rules={[
              { required: true, message: '请输入租户编码' },
              { pattern: /^[a-z][a-z0-9_-]*$/, message: '仅支持小写字母、数字、下划线与中划线，且以字母开头' },
            ]}
            extra={
              editing
                ? '租户编码参与物理索引命名，创建后不可修改'
                : '编码将成为该租户物理索引名的一部分，创建后不可修改'
            }
          >
            <Input disabled={!!editing} placeholder="如 acme" />
          </Form.Item>
          <Form.Item name="name" label="租户名称" rules={[{ required: true, message: '请输入租户名称' }]}>
            <Input placeholder="如 Acme 事业部" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
