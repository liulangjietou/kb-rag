// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { PlusOutlined } from '@ant-design/icons';
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Switch, Table, Tag, Typography, message } from 'antd';
import {
  createWebCredential,
  listWebCredentials,
  removeWebCredential,
  updateWebCredential,
} from '../../../api/webCredential';
import type { WebAuthType, WebCredentialEntry } from '../../../api/types';

const AUTH_TYPE_OPTIONS: { label: string; value: WebAuthType }[] = [
  { label: 'Basic（用户名 + 密码）', value: 'BASIC' },
  { label: 'Header（自定义请求头）', value: 'HEADER' },
];

interface CredentialFormValues {
  host: string;
  auth_type: WebAuthType;
  username?: string;
  secret?: string;
  header_name?: string;
}

/**
 * 系统设置「站点凭据」Tab（M18）：网页导入抓取需要登录的站点时，按 host 配一份凭据，抓取只对
 * 该 host 的请求注入认证头。secret 只进不出——列表看不到、编辑时留空即保持原值，所以停启用
 * 不需要重新输入密码。host 与认证类型建后不可改：换站点或换方式就是另一份凭据，删了重建。
 */
export default function WebCredentialTab() {
  const [credentials, setCredentials] = useState<WebCredentialEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<WebCredentialEntry | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<CredentialFormValues>();
  // 受控监听认证类型，驱动表单在用户名/头名之间切换
  const authType = Form.useWatch('auth_type', form) ?? 'BASIC';

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setCredentials(await listWebCredentials());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openCreate = () => {
    setEditTarget(null);
    form.resetFields();
    form.setFieldsValue({ auth_type: 'BASIC' });
    setFormOpen(true);
  };

  const openEdit = (entry: WebCredentialEntry) => {
    setEditTarget(entry);
    form.resetFields();
    form.setFieldsValue({
      host: entry.host,
      auth_type: entry.auth_type,
      username: entry.username ?? undefined,
      header_name: entry.header_name ?? undefined,
      // secret 故意不回填：接口不回传，留空即保持原值
    });
    setFormOpen(true);
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      if (editTarget) {
        await updateWebCredential(editTarget.credential_id, {
          username: values.username,
          secret: values.secret,
          header_name: values.header_name,
        });
        message.success(`已更新 ${editTarget.host} 的凭据`);
      } else {
        await createWebCredential({
          host: values.host.trim(),
          auth_type: values.auth_type,
          username: values.username,
          secret: values.secret ?? '',
          header_name: values.header_name,
        });
        message.success(`已为 ${values.host.trim()} 配置凭据`);
      }
      setFormOpen(false);
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const handleToggle = async (entry: WebCredentialEntry, enabled: boolean) => {
    await updateWebCredential(entry.credential_id, { enabled });
    message.success(enabled ? `已启用 ${entry.host} 的凭据` : `已停用 ${entry.host} 的凭据`);
    load();
  };

  const handleRemove = async (entry: WebCredentialEntry) => {
    await removeWebCredential(entry.credential_id);
    message.success(`已删除 ${entry.host} 的凭据`);
    load();
  };

  return (
    <div>
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <Typography.Text type="secondary">
          网页导入抓取需要登录的站点时，按 host 配置凭据；抓取只对该 host 的请求注入认证头，跨站请求不会携带。
          建议使用站点侧专用的只读账号。
        </Typography.Text>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建凭据
        </Button>
      </Space>

      <Table<WebCredentialEntry>
        rowKey="credential_id"
        loading={loading}
        dataSource={credentials}
        pagination={false}
        columns={[
          { title: 'Host', dataIndex: 'host' },
          {
            title: '认证类型',
            dataIndex: 'auth_type',
            width: 110,
            render: (type: WebAuthType) => <Tag color={type === 'BASIC' ? 'blue' : 'purple'}>{type}</Tag>,
          },
          {
            title: '用户名 / 头名',
            width: 200,
            render: (_, entry) =>
              entry.auth_type === 'BASIC' ? (entry.username ?? '-') : (entry.header_name ?? '-'),
          },
          {
            title: '凭据内容',
            width: 110,
            // 永远打星号：接口本来就不回传 secret，这一列只是明示"已存在"
            render: () => <Typography.Text type="secondary">••••••••</Typography.Text>,
          },
          {
            title: '启用',
            dataIndex: 'enabled',
            width: 80,
            render: (enabled: boolean, entry) => (
              <Switch size="small" checked={enabled} onChange={(checked) => handleToggle(entry, checked)} />
            ),
          },
          { title: '创建时间', dataIndex: 'created_at', width: 180 },
          {
            title: '操作',
            width: 160,
            render: (_, entry) => (
              <Space>
                <Button size="small" type="link" onClick={() => openEdit(entry)}>
                  编辑
                </Button>
                <Popconfirm
                  title={`删除 ${entry.host} 的凭据？`}
                  description="删除后该站点的抓取回到匿名方式；已入库的文档不受影响"
                  okText="删除"
                  okButtonProps={{ danger: true }}
                  cancelText="取消"
                  onConfirm={() => handleRemove(entry)}
                >
                  <Button size="small" type="link" danger>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={editTarget ? `编辑凭据：${editTarget.host}` : '新建站点凭据'}
        open={formOpen}
        onOk={handleSubmit}
        confirmLoading={submitting}
        onCancel={() => setFormOpen(false)}
        okText={editTarget ? '保存' : '创建'}
        cancelText="取消"
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="host"
            label="Host"
            tooltip="仅主机名（可带端口），如 wiki.example.com；不含协议与路径。精确匹配，子域不共享凭据"
            rules={[
              { required: true, message: '请输入 host' },
              {
                pattern: /^[a-zA-Z0-9.-]+(:\d+)?$/,
                message: 'host 只能是主机名（可带端口），不能包含协议或路径',
              },
            ]}
          >
            <Input placeholder="wiki.example.com" disabled={!!editTarget} />
          </Form.Item>
          <Form.Item name="auth_type" label="认证类型" rules={[{ required: true }]}>
            <Select options={AUTH_TYPE_OPTIONS} disabled={!!editTarget} />
          </Form.Item>
          {authType === 'BASIC' ? (
            <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input autoComplete="off" />
            </Form.Item>
          ) : (
            <Form.Item
              name="header_name"
              label="请求头名称"
              tooltip="如 Authorization（配 Bearer token）或 Cookie（配会话 Cookie）"
              rules={[{ required: true, message: '请输入请求头名称' }]}
            >
              <Input placeholder="Authorization" />
            </Form.Item>
          )}
          <Form.Item
            name="secret"
            label={authType === 'BASIC' ? '密码' : '请求头完整值'}
            tooltip={editTarget ? '留空表示不修改' : undefined}
            rules={editTarget ? [] : [{ required: true, message: '请输入凭据内容' }]}
          >
            <Input.Password
              autoComplete="new-password"
              placeholder={editTarget ? '留空保持原值' : authType === 'BASIC' ? '' : 'Bearer eyJhbGci...'}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
