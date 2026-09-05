// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useMemo, useState } from 'react';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Checkbox,
  Divider,
  Drawer,
  Form,
  Input,
  Popconfirm,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { listKnowledgeBases } from '../../api/kb';
import { createRole, deleteRole, listPermissionCatalogue, listRoles, updateRole } from '../../api/role';
import { listTenants } from '../../api/tenant';
import type { KnowledgeBase, PermissionCatalogueItem, RoleSummary, TenantSummary } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { PERMISSIONS } from '../../auth/permissions';
import PageHeader from '../../components/PageHeader';

interface RoleFormValues {
  code: string;
  name: string;
  description?: string;
  kb_scope_all: boolean;
  kb_ids?: string[];
  permission_codes?: string[];
}

/**
 * Role editing: what a role may do, and which knowledge bases it may do it to.
 *
 * <p>The two halves are not interchangeable. Permission codes decide which screens and endpoints answer
 * at all; the data scope decides which knowledge bases those endpoints will admit, and it also trims what
 * retrieval returns. A role granted kb:read over two bases out of twenty is not a role that can read
 * twenty bases carefully -- the other eighteen do not exist as far as it is concerned.
 *
 * <p>Built-in roles are editable in their grants but keep their code and cannot be deleted: seeds,
 * upgrade scripts and the first-login provisioning path all name them by code.
 */
export default function RoleManagePage() {
  const { can } = useAuth();
  // 租户列跟着 tenant:manage 走：租户行过滤只对持有该权限的平台运维放行 t_kb_role，
  // 也只有这类账号会在列表里同时看到多个租户的内置角色，其他人看到的本就只有自己租户那一份。
  const canTenantManage = can(PERMISSIONS.TENANT_MANAGE);
  const [roles, setRoles] = useState<RoleSummary[]>([]);
  const [catalogue, setCatalogue] = useState<PermissionCatalogueItem[]>([]);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<RoleSummary | null>(null);
  const [scopeAll, setScopeAll] = useState(true);
  // 每次打开抽屉自增，作为表单的 key，强制重建一份干净的表单实例，见 formInitialValues 的说明。
  const [formSeq, setFormSeq] = useState(0);
  const [form] = Form.useForm<RoleFormValues>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRoles(await listRoles());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    listPermissionCatalogue().then(setCatalogue).catch(() => undefined);
    // Scoped roles pick from the bases that exist; an operator who cannot read any of them cannot
    // meaningfully scope a role either, so an empty list here simply leaves only "全部知识库".
    listKnowledgeBases().then(setKbs).catch(() => undefined);
  }, []);

  useEffect(() => {
    if (canTenantManage) {
      listTenants().then(setTenants).catch(() => undefined);
    }
  }, [canTenantManage]);

  // The catalogue arrives flat and already ordered; grouping keeps that order inside each module.
  const modules = useMemo(() => {
    const grouped = new Map<string, { moduleName: string; items: PermissionCatalogueItem[] }>();
    for (const item of catalogue) {
      const bucket = grouped.get(item.module);
      if (bucket) {
        bucket.items.push(item);
      } else {
        grouped.set(item.module, { moduleName: item.module_name, items: [item] });
      }
    }
    return [...grouped.values()];
  }, [catalogue]);

  const kbOptions = kbs.map((kb) => ({ value: kb.kb_id, label: kb.name }));

  // 角色行只带租户 id，租户名在租户列表里；租户还没加载完（或角色属于新建的租户）时退回显示原始 id。
  const tenantNameOf = (tenantId: string) =>
    tenants.find((tenant) => tenant.tenant_id === tenantId)?.name ?? tenantId;

  /**
   * 抽屉里表单的初值。回填必须走 initialValues 而不是打开前调 form.setFieldsValue：
   * openEdit 执行的那一刻抽屉还没渲染，useForm 实例没有连上任何 Form 元素，那次赋值会被 antd
   * 直接丢弃，编辑态于是变成一张空表单——名称、说明、数据范围和已授权限全都不回显。
   */
  const formInitialValues = useMemo<RoleFormValues>(
    () =>
      editing
        ? {
            code: editing.code,
            name: editing.name,
            description: editing.description ?? undefined,
            kb_scope_all: editing.kb_scope_all,
            kb_ids: editing.kb_ids ?? [],
            permission_codes: editing.permission_codes ?? [],
          }
        : { code: '', name: '', kb_scope_all: true, kb_ids: [], permission_codes: [] },
    [editing],
  );

  const openCreate = () => {
    setEditing(null);
    setScopeAll(true);
    setFormSeq((seq) => seq + 1);
    setDrawerOpen(true);
  };

  const openEdit = (record: RoleSummary) => {
    setEditing(record);
    setScopeAll(record.kb_scope_all);
    setFormSeq((seq) => seq + 1);
    setDrawerOpen(true);
  };

  const submit = async (values: RoleFormValues) => {
    setSubmitting(true);
    try {
      const payload = {
        name: values.name,
        description: values.description,
        kb_scope_all: values.kb_scope_all,
        // A scope of "all" carries no id list: keeping one would quietly resurrect the old selection
        // the next time somebody narrowed the role back down.
        kb_ids: values.kb_scope_all ? [] : values.kb_ids ?? [],
        permission_codes: values.permission_codes ?? [],
      };
      if (editing) {
        await updateRole(editing.role_id, payload);
        message.success('角色已更新，持有该角色的账号下次请求即生效');
      } else {
        await createRole({ ...payload, code: values.code });
        message.success('角色已创建');
      }
      setDrawerOpen(false);
      load();
    } finally {
      setSubmitting(false);
    }
  };

  const removeRole = async (record: RoleSummary) => {
    await deleteRole(record.role_id);
    message.success('角色已删除');
    load();
  };

  const columns: ColumnsType<RoleSummary> = [
    {
      title: '角色',
      dataIndex: 'name',
      render: (value: string, record) => (
        <Space size={4}>
          <Typography.Text strong>{value}</Typography.Text>
          {record.builtin && <Tag color="blue">内置</Tag>}
        </Space>
      ),
    },
    { title: '编码', dataIndex: 'code' },
    // 内置角色每个租户各有一份，编码与名称都相同：没有这一列，平台运维看到的就是一张"重复"的表。
    ...(canTenantManage
      ? ([
          {
            title: '所属租户',
            dataIndex: 'tenant_id',
            render: (value: string) => <Tag color="geekblue">{tenantNameOf(value)}</Tag>,
          },
        ] as ColumnsType<RoleSummary>)
      : []),
    { title: '说明', dataIndex: 'description', render: (value: string | null) => value || '-' },
    {
      title: '数据范围',
      key: 'scope',
      render: (_, record) =>
        record.kb_scope_all ? (
          <Tag color="gold">全部知识库</Tag>
        ) : (
          <Typography.Text>{`指定 ${record.kb_ids?.length ?? 0} 个知识库`}</Typography.Text>
        ),
    },
    {
      title: '权限数',
      key: 'permissions',
      render: (_, record) => `${record.permission_codes?.length ?? 0} 项`,
    },
    {
      title: '操作',
      key: 'actions',
      width: 140,
      render: (_, record) => (
        <Space size={8}>
          <Button type="link" size="small" onClick={() => openEdit(record)}>编辑权限</Button>
          {record.builtin ? (
            <Tooltip title="内置角色不可删除">
              <Typography.Text type="secondary">删除</Typography.Text>
            </Tooltip>
          ) : (
            <Popconfirm
              title="确认删除该角色？"
              description="仍有账号持有该角色时将被拒绝，请先调整账号授权"
              okText="删除"
              okType="danger"
              cancelText="取消"
              onConfirm={() => removeRole(record)}
            >
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div className="management-page roles-page">
      <PageHeader
        eyebrow="ROLE GOVERNANCE"
        title="角色与权限"
        description="将功能权限与知识库数据范围分别建模，明确每个角色能做什么、能看到哪些知识。"
        actions={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建角色
          </Button>
        </Space>
      }
      />
      <Card className="management-panel">
      <Table<RoleSummary>
        className="management-table"
        rowKey="role_id"
        loading={loading}
        columns={columns}
        dataSource={roles}
        pagination={false}
        scroll={{ x: 960 }}
      />

      <Drawer
        open={drawerOpen}
        width={720}
        title={editing ? `编辑角色 - ${editing.name}` : '新建角色'}
        onClose={() => setDrawerOpen(false)}
        destroyOnHidden
        extra={
          <Space>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" loading={submitting} onClick={() => form.submit()}>
              保存
            </Button>
          </Space>
        }
      >
        {/* key 随每次打开自增，保证 Form 组件重新挂载——rc-field-form 只在挂载那一次把 initialValues
            写进 store（`setInitialValues(values, !mountRef.current)`），沿用同一个组件实例时它只更新
            引用。destroyOnHidden 通常也能触发重挂载，但要等关闭动画跑完，抽屉被快速关掉再打开就赶不上。
            下面的 preserve={false} 是同一件事的另一半，不能单独删：卸载时它把这些字段记进
            prevWithoutPreserves，重挂载时才会被强制取 initialValues，否则 merge 里残留的旧值会赢。 */}
        <Form<RoleFormValues>
          key={formSeq}
          form={form}
          layout="vertical"
          onFinish={submit}
          initialValues={formInitialValues}
          preserve={false}
        >
          <Form.Item
            name="code"
            label="角色编码"
            rules={[
              { required: true, message: '请输入角色编码' },
              { pattern: /^[A-Z][A-Z0-9_]*$/, message: '仅支持大写字母、数字与下划线，且以字母开头' },
            ]}
            extra={editing ? '角色编码是脚本与初始化数据引用它的标识，创建后不可修改' : undefined}
          >
            <Input disabled={!!editing} placeholder="如 KB_EDITOR" />
          </Form.Item>
          <Form.Item name="name" label="角色名称" rules={[{ required: true, message: '请输入角色名称' }]}>
            <Input placeholder="如 知识库编辑" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={2} placeholder="选填，用于说明该角色的适用场景" />
          </Form.Item>

          <Divider orientation="left" plain>
            数据范围
          </Divider>
          <Form.Item name="kb_scope_all" label="可访问的知识库">
            <Radio.Group onChange={(e) => setScopeAll(e.target.value as boolean)}>
              <Radio value={true}>全部知识库（含此后新建的）</Radio>
              <Radio value={false}>指定知识库</Radio>
            </Radio.Group>
          </Form.Item>
          {!scopeAll && (
            <Form.Item
              name="kb_ids"
              label="指定知识库"
              rules={[{ required: true, message: '请至少选择一个知识库' }]}
              extra="未选中的知识库对该角色不存在：既打不开，检索结果里也不会出现"
            >
              <Select mode="multiple" options={kbOptions} placeholder="可多选" />
            </Form.Item>
          )}

          <Divider orientation="left" plain>
            功能权限
          </Divider>
          <Form.Item name="permission_codes" noStyle>
            <Checkbox.Group className="permission-groups">
              {modules.map((group) => (
                <div key={group.moduleName} className="permission-group">
                  <Typography.Text type="secondary" className="permission-group__title">
                    {group.moduleName}
                  </Typography.Text>
                  <Space size={[16, 8]} wrap>
                    {group.items.map((item) => (
                      <Checkbox key={item.code} value={item.code}>
                        <Tooltip title={item.code}>{item.name}</Tooltip>
                      </Checkbox>
                    ))}
                  </Space>
                </div>
              ))}
            </Checkbox.Group>
          </Form.Item>
        </Form>
      </Drawer>
      </Card>
    </div>
  );
}
