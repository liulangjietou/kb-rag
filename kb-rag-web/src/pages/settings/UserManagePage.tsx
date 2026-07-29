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
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { listRoles } from '../../api/role';
import {
  assignUserRoles,
  createUser,
  deleteUser,
  getUser,
  listUsers,
  resetUserPassword,
  updateUser,
  updateUserStatus,
} from '../../api/user';
import type { ListUsersParams } from '../../api/user';
import type { PageResult, RoleSummary, UserSummary } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';

const EMPTY_PAGE: PageResult<UserSummary> = { items: [], page: 1, size: 10, total: 0 };

const SOURCE_LABEL: Record<string, string> = { LOCAL: '平台账号', LDAP: '域账号' };

interface UserFormValues {
  username: string;
  display_name?: string;
  email?: string;
  password?: string;
  role_ids?: string[];
}

/**
 * Account administration, the only way an account comes into being besides a first domain login.
 *
 * <p>Two kinds of account share the table and differ in what may be done to them: a domain account has
 * no local password, so the reset action is not offered for one -- its credentials live in the directory
 * and rotating them here would create a second, silently ignored secret. Roles and status are ours to
 * manage either way, which is the point of provisioning domain logins into a local record at all.
 */
export default function UserManagePage() {
  const { username: signedInAs } = useAuth();
  const [page, setPage] = useState<PageResult<UserSummary>>(EMPTY_PAGE);
  const [loading, setLoading] = useState(false);
  const [params, setParams] = useState<ListUsersParams>({ page: 1, size: 10 });
  const [roles, setRoles] = useState<RoleSummary[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [editing, setEditing] = useState<UserSummary | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [rolesTarget, setRolesTarget] = useState<UserSummary | null>(null);
  const [resetTarget, setResetTarget] = useState<UserSummary | null>(null);
  const [userForm] = Form.useForm<UserFormValues>();
  const [rolesForm] = Form.useForm<{ role_ids: string[] }>();
  const [resetForm] = Form.useForm<{ new_password: string }>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setPage(await listUsers(params));
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    load();
  }, [load]);

  // Granting a role means picking one from a list, so the catalogue is needed even though this screen
  // does not edit roles. GET /roles admits user:manage for exactly this reason.
  useEffect(() => {
    listRoles().then(setRoles).catch(() => undefined);
  }, []);

  const roleOptions = roles.map((role) => ({ value: role.role_id, label: `${role.name}（${role.code}）` }));

  const openCreate = () => {
    setEditing(null);
    userForm.resetFields();
    setFormOpen(true);
  };

  const openEdit = (record: UserSummary) => {
    setEditing(record);
    userForm.setFieldsValue({
      username: record.username,
      display_name: record.display_name ?? undefined,
      email: record.email ?? undefined,
    });
    setFormOpen(true);
  };

  const submitUser = async (values: UserFormValues) => {
    setSubmitting(true);
    try {
      if (editing) {
        await updateUser(editing.user_id, { display_name: values.display_name, email: values.email });
        message.success('账号信息已更新');
      } else {
        await createUser({
          username: values.username,
          display_name: values.display_name,
          email: values.email,
          password: values.password ?? '',
          role_ids: values.role_ids ?? [],
        });
        message.success('账号已创建，首次登录需修改密码');
      }
      setFormOpen(false);
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const openRoles = async (record: UserSummary) => {
    setRolesTarget(record);
    rolesForm.setFieldsValue({ role_ids: record.role_ids ?? [] });
    // The list row carries role names for display but not always their ids; the single read does.
    const detail = await getUser(record.user_id);
    rolesForm.setFieldsValue({ role_ids: detail.role_ids ?? [] });
  };

  const submitRoles = async (values: { role_ids: string[] }) => {
    if (!rolesTarget) {
      return;
    }
    setSubmitting(true);
    try {
      await assignUserRoles(rolesTarget.user_id, values.role_ids ?? []);
      message.success('角色已保存');
      setRolesTarget(null);
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const submitReset = async (values: { new_password: string }) => {
    if (!resetTarget) {
      return;
    }
    setSubmitting(true);
    try {
      await resetUserPassword(resetTarget.user_id, values.new_password);
      message.success('密码已重置，该账号下次登录须自行修改');
      setResetTarget(null);
      resetForm.resetFields();
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const toggleStatus = async (record: UserSummary) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    await updateUserStatus(record.user_id, next);
    message.success(next === 'DISABLED' ? '账号已停用，其登录会话同时失效' : '账号已启用');
    load();
  };

  const removeUser = async (record: UserSummary) => {
    await deleteUser(record.user_id);
    message.success('账号已删除');
    load();
  };

  const columns: ColumnsType<UserSummary> = [
    {
      title: '用户名',
      dataIndex: 'username',
      render: (value: string, record) => (
        <Space size={4}>
          <Typography.Text strong>{value}</Typography.Text>
          {record.username === signedInAs && <Tag color="blue">当前登录</Tag>}
        </Space>
      ),
    },
    { title: '姓名', dataIndex: 'display_name', render: (value: string | null) => value || '-' },
    { title: '邮箱', dataIndex: 'email', render: (value: string | null) => value || '-' },
    {
      title: '来源',
      dataIndex: 'source',
      render: (value: string) => SOURCE_LABEL[value] ?? value,
    },
    {
      title: '角色',
      dataIndex: 'role_names',
      render: (value: string[] | null) =>
        value && value.length > 0 ? value.map((name) => <Tag key={name}>{name}</Tag>) : '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      render: (value: string) =>
        value === 'ENABLED' ? <Tag color="green">已启用</Tag> : <Tag color="red">已停用</Tag>,
    },
    { title: '最近登录', dataIndex: 'last_login_at', render: (value: string | null) => value || '从未登录' },
    {
      title: '操作',
      key: 'actions',
      width: 260,
      render: (_, record) => {
        const isSelf = record.username === signedInAs;
        return (
          <Space size={4} wrap>
            <a onClick={() => openEdit(record)}>编辑</a>
            <a onClick={() => openRoles(record)}>角色</a>
            {record.source === 'LOCAL' && (
              <a onClick={() => setResetTarget(record)}>重置密码</a>
            )}
            {isSelf ? (
              // Nothing stops an operator from locking themselves out of the console but themselves.
              <Tooltip title="不能停用或删除自己的账号">
                <Typography.Text type="secondary">停用</Typography.Text>
              </Tooltip>
            ) : (
              <>
                <Popconfirm
                  title={record.status === 'ENABLED' ? '确认停用该账号？' : '确认启用该账号？'}
                  description={
                    record.status === 'ENABLED' ? '停用后该账号将被强制下线，无法再登录' : undefined
                  }
                  okText="确定"
                  cancelText="取消"
                  onConfirm={() => toggleStatus(record)}
                >
                  <a>{record.status === 'ENABLED' ? '停用' : '启用'}</a>
                </Popconfirm>
                <Popconfirm
                  title="确认删除该账号？"
                  description="删除后其角色授权一并移除，操作不可恢复"
                  okText="删除"
                  okType="danger"
                  cancelText="取消"
                  onConfirm={() => removeUser(record)}
                >
                  <Typography.Link type="danger">删除</Typography.Link>
                </Popconfirm>
              </>
            )}
          </Space>
        );
      },
    },
  ];

  return (
    <Card
      title="用户管理"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建账号
          </Button>
        </Space>
      }
    >
      <Space style={{ marginBottom: 16 }} wrap>
        <Input.Search
          allowClear
          placeholder="用户名 / 姓名 / 邮箱"
          style={{ width: 240 }}
          onSearch={(value) => setParams((prev) => ({ ...prev, keyword: value || undefined, page: 1 }))}
        />
        <Select
          allowClear
          placeholder="状态"
          style={{ width: 120 }}
          options={[
            { value: 'ENABLED', label: '已启用' },
            { value: 'DISABLED', label: '已停用' },
          ]}
          onChange={(value) => setParams((prev) => ({ ...prev, status: value, page: 1 }))}
        />
        <Select
          allowClear
          placeholder="来源"
          style={{ width: 140 }}
          options={[
            { value: 'LOCAL', label: '平台账号' },
            { value: 'LDAP', label: '域账号' },
          ]}
          onChange={(value) => setParams((prev) => ({ ...prev, source: value, page: 1 }))}
        />
      </Space>

      <Table<UserSummary>
        rowKey="user_id"
        loading={loading}
        columns={columns}
        dataSource={page.items}
        scroll={{ x: 1080 }}
        pagination={{
          current: page.page,
          pageSize: page.size,
          total: page.total,
          showSizeChanger: true,
          showTotal: (total) => `共 ${total} 个账号`,
          onChange: (current, size) => setParams((prev) => ({ ...prev, page: current, size })),
        }}
      />

      <Modal
        open={formOpen}
        title={editing ? `编辑账号 - ${editing.username}` : '新建账号'}
        okText="保存"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={() => userForm.submit()}
        onCancel={() => setFormOpen(false)}
        destroyOnClose
      >
        <Form<UserFormValues> form={userForm} layout="vertical" onFinish={submitUser} preserve={false}>
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
            extra={editing ? '用户名是账号的登录标识，创建后不可修改' : undefined}
          >
            <Input disabled={!!editing} placeholder="登录用的用户名" />
          </Form.Item>
          <Form.Item name="display_name" label="姓名">
            <Input placeholder="用于界面展示" />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '邮箱格式不正确' }]}>
            <Input placeholder="选填" />
          </Form.Item>
          {!editing && (
            <>
              <Form.Item
                name="password"
                label="初始密码"
                rules={[
                  { required: true, message: '请输入初始密码' },
                  { min: 8, message: '密码至少 8 位' },
                ]}
                extra="该账号首次登录时须自行修改密码"
              >
                <Input.Password placeholder="至少 8 位" />
              </Form.Item>
              <Form.Item
                name="role_ids"
                label="角色"
                rules={[{ required: true, message: '请至少选择一个角色' }]}
                extra="未授予角色的账号登录后看不到任何功能"
              >
                <Select mode="multiple" options={roleOptions} placeholder="可多选" />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>

      <Modal
        open={!!rolesTarget}
        title={`分配角色 - ${rolesTarget?.username ?? ''}`}
        okText="保存"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={() => rolesForm.submit()}
        onCancel={() => setRolesTarget(null)}
        destroyOnClose
      >
        <Form form={rolesForm} layout="vertical" onFinish={submitRoles} preserve={false}>
          <Form.Item name="role_ids" label="角色" extra="保存的是完整集合，未勾选的角色将被收回">
            <Select mode="multiple" options={roleOptions} placeholder="可多选" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={!!resetTarget}
        title={`重置密码 - ${resetTarget?.username ?? ''}`}
        okText="重置"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={() => resetForm.submit()}
        onCancel={() => setResetTarget(null)}
        destroyOnClose
      >
        <Form form={resetForm} layout="vertical" onFinish={submitReset} preserve={false}>
          <Form.Item
            name="new_password"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, message: '密码至少 8 位' },
            ]}
            extra="重置后该账号下次登录会被要求再次修改，此处填写的只是交接口令"
          >
            <Input.Password placeholder="至少 8 位" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
