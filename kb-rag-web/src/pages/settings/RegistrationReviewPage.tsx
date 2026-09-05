// Author: owlzhangfq@gmail.com
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  approveRegistration,
  listRegistrationReviews,
  rejectRegistration,
} from '../../api/registration';
import type {
  ListRegistrationReviewsParams,
  RegistrationReviewSummary,
  RegistrationStatus,
} from '../../api/registrationTypes';
import { listRoles } from '../../api/role';
import { listTenants } from '../../api/tenant';
import type { PageResult, RoleSummary, TenantSummary } from '../../api/types';
import AccessNavigation from './AccessNavigation';
import PageHeader from '../../components/PageHeader';
import '../../styles/registration-home.css';

interface ApprovalFormValues {
  tenant_id: string;
  role_ids: string[];
}

interface RejectFormValues {
  reason: string;
}

const EMPTY_PAGE: PageResult<RegistrationReviewSummary> = { items: [], page: 1, size: 10, total: 0 };

const STATUS_META: Record<RegistrationStatus, { label: string; color: string }> = {
  PENDING: { label: '待审核', color: 'warning' },
  APPROVED: { label: '已通过', color: 'success' },
  REJECTED: { label: '已驳回', color: 'error' },
};

function formatDate(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
      }).format(date);
}

/** 邮箱身份审核入口；通过操作将租户、角色和状态作为一次后端事务提交。 */
export default function RegistrationReviewPage() {
  const [page, setPage] = useState<PageResult<RegistrationReviewSummary>>(EMPTY_PAGE);
  const [params, setParams] = useState<ListRegistrationReviewsParams>({ status: 'PENDING', page: 1, size: 10 });
  const [loading, setLoading] = useState(false);
  const [listError, setListError] = useState(false);
  const [catalogueLoading, setCatalogueLoading] = useState(true);
  const [catalogueError, setCatalogueError] = useState(false);
  const [catalogueGeneration, setCatalogueGeneration] = useState(0);
  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [roles, setRoles] = useState<RoleSummary[]>([]);
  const [selected, setSelected] = useState<RegistrationReviewSummary | null>(null);
  const [rejectTarget, setRejectTarget] = useState<RegistrationReviewSummary | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [approvalError, setApprovalError] = useState<string | null>(null);
  const [rejectError, setRejectError] = useState<string | null>(null);
  const [approvalForm] = Form.useForm<ApprovalFormValues>();
  const [rejectForm] = Form.useForm<RejectFormValues>();
  const requestSequenceRef = useRef(0);
  const submitInFlightRef = useRef(false);

  const load = useCallback(async () => {
    const sequence = ++requestSequenceRef.current;
    setLoading(true);
    setListError(false);
    try {
      const response = await listRegistrationReviews(params);
      if (requestSequenceRef.current === sequence) {
        setPage(response);
      }
    } catch {
      if (requestSequenceRef.current === sequence) {
        setListError(true);
      }
    } finally {
      if (requestSequenceRef.current === sequence) setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
    return () => {
      requestSequenceRef.current += 1;
    };
  }, [load]);

  useEffect(() => {
    let active = true;
    setCatalogueLoading(true);
    Promise.all([listTenants(), listRoles()])
      .then(([tenantItems, roleItems]) => {
        if (!active) return;
        setTenants(tenantItems);
        setRoles(roleItems);
        setCatalogueError(false);
      })
      .catch(() => {
        if (active) setCatalogueError(true);
      })
      .finally(() => {
        if (active) setCatalogueLoading(false);
      });
    return () => {
      active = false;
    };
  }, [catalogueGeneration]);

  const selectedTenantId = Form.useWatch('tenant_id', approvalForm);
  const selectedRoleIds = Form.useWatch('role_ids', approvalForm) ?? [];
  const enabledTenants = tenants.filter((tenant) => tenant.status === 'ENABLED');
  const availableRoles = useMemo(
    () => roles.filter((role) => role.tenant_id === selectedTenantId),
    [roles, selectedTenantId],
  );
  const selectedRoles = roles.filter((role) => selectedRoleIds.includes(role.role_id));
  const permissionPreview = [...new Set(selectedRoles.flatMap((role) => role.permission_codes))].sort();
  const tenantSnapshotLabel = (tenantId: string | null | undefined) => {
    if (!tenantId) return '—';
    const tenant = tenants.find((item) => item.tenant_id === tenantId);
    return tenant ? `${tenant.name}（${tenant.code}）` : tenantId;
  };
  const roleSnapshotLabel = (roleId: string) => {
    const role = roles.find((item) => item.role_id === roleId);
    return role ? `${role.name}（${role.code}）` : roleId;
  };

  const openReview = (record: RegistrationReviewSummary) => {
    setApprovalError(null);
    setSelected(record);
    // setFieldsValue 会让 rc-field-form 的空 errors/warnings 共用引用，rc-util 会误报循环引用。
    approvalForm.setFields([
      { name: 'tenant_id', value: record.tenant_id ?? undefined, errors: [], warnings: [] },
      { name: 'role_ids', value: record.role_ids ?? [], errors: [], warnings: [] },
    ]);
  };

  const applyMutation = (updated: RegistrationReviewSummary) => {
    setPage((current) => {
      const visibleUnderCurrentFilter = !params.status || params.status === updated.status;
      const found = current.items.some((item) => item.application_id === updated.application_id);
      if (!found) return current;
      return {
        ...current,
        items: visibleUnderCurrentFilter
          ? current.items.map((item) => item.application_id === updated.application_id ? updated : item)
          : current.items.filter((item) => item.application_id !== updated.application_id),
        total: visibleUnderCurrentFilter ? current.total : Math.max(0, current.total - 1),
      };
    });
  };

  const submitApproval = async (values: ApprovalFormValues) => {
    if (!selected || submitInFlightRef.current) return;
    submitInFlightRef.current = true;
    setSubmitting(true);
    setApprovalError(null);
    try {
      const updated = await approveRegistration(selected.application_id, {
        tenant_id: values.tenant_id,
        role_ids: values.role_ids,
      });
      applyMutation(updated);
      message.success('账号已开通，审核结果邮件已进入发送流程');
      setSelected(null);
      approvalForm.resetFields();
      void load();
    } catch {
      setApprovalError('账号开通失败，请核对租户与角色后重试。申请信息和当前选择已保留。');
    } finally {
      submitInFlightRef.current = false;
      setSubmitting(false);
    }
  };

  const submitReject = async (values: RejectFormValues) => {
    if (!rejectTarget || submitInFlightRef.current) return;
    submitInFlightRef.current = true;
    setSubmitting(true);
    setRejectError(null);
    try {
      const updated = await rejectRegistration(
        rejectTarget.application_id, { reason: values.reason.trim() },
      );
      applyMutation(updated);
      message.success('申请已驳回，审核结果邮件已进入发送流程');
      if (selected?.application_id === rejectTarget.application_id) setSelected(null);
      setRejectTarget(null);
      rejectForm.resetFields();
      void load();
    } catch {
      setRejectError('驳回提交失败，请重试。已填写的原因不会被清空。');
    } finally {
      submitInFlightRef.current = false;
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<RegistrationReviewSummary> = [
    {
      title: '申请人',
      key: 'applicant',
      render: (_, record) => (
        <div className="registration-applicant">
          <span aria-hidden="true">{record.display_name.trim().slice(0, 1) || '@'}</span>
          <div><strong>{record.display_name}</strong><small>{record.email}</small></div>
        </div>
      ),
    },
    { title: '团队 / 部门', dataIndex: 'team_name', render: (value: string | null) => value || '—' },
    {
      title: '申请用途',
      dataIndex: 'application_note',
      ellipsis: true,
      render: (value: string | null) => <Typography.Text title={value ?? undefined}>{value || '—'}</Typography.Text>,
    },
    {
      title: '邮箱状态',
      dataIndex: 'email_verified_at',
      render: (value: string) => value
        ? <Typography.Text type="success"><CheckCircleOutlined /> 已验证</Typography.Text>
        : <Typography.Text type="danger"><CloseCircleOutlined /> 未验证</Typography.Text>,
    },
    { title: '提交时间', dataIndex: 'created_at', render: formatDate },
    {
      title: '状态',
      dataIndex: 'status',
      render: (status: RegistrationStatus) => <Tag color={STATUS_META[status]?.color}>{STATUS_META[status]?.label ?? status}</Tag>,
    },
    {
      title: '操作',
      key: 'action',
      fixed: 'right',
      width: 96,
      render: (_, record) => (
        <Button type="link" size="small" onClick={() => openReview(record)}>
          {record.status === 'PENDING' ? '审核' : '查看'}
        </Button>
      ),
    },
  ];

  return (
    <div className="management-page registration-review-page">
      <PageHeader
        eyebrow="ACCESS GOVERNANCE"
        title="用户与审核"
        description="邮箱只证明身份，租户和角色由管理员按最小权限原则确认。"
        actions={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button>}
      />
      <AccessNavigation canReview={true} />

      {catalogueError && (
        <Alert
          className="registration-review-page__alert"
          type="error"
          showIcon
          message="租户或角色目录加载失败"
          description="列表仍可查看，但在目录恢复前不能通过申请。"
          action={<Button size="small" onClick={() => setCatalogueGeneration((current) => current + 1)}>重试目录</Button>}
        />
      )}

      {listError && (
        <Alert
          className="registration-review-page__alert"
          type="error"
          showIcon
          message="注册申请列表加载失败"
          description="保留上一次成功返回的数据，请重试。"
          action={<Button size="small" onClick={() => void load()}>重试列表</Button>}
        />
      )}

      <section className="atlas-panel registration-review-panel">
        <div className="registration-review-toolbar">
          <Input.Search
            allowClear
            placeholder="搜索姓名、邮箱或团队"
            aria-label="搜索注册申请"
            onSearch={(keyword) => setParams((current) => ({ ...current, keyword: keyword.trim() || undefined, page: 1 }))}
          />
          <Select<RegistrationStatus>
            allowClear
            value={params.status}
            placeholder="全部状态"
            aria-label="按审核状态筛选"
            options={Object.entries(STATUS_META).map(([value, meta]) => ({ value: value as RegistrationStatus, label: meta.label }))}
            onChange={(status) => setParams((current) => ({ ...current, status, page: 1 }))}
          />
        </div>
        <Table<RegistrationReviewSummary>
          rowKey="application_id"
          loading={loading}
          columns={columns}
          dataSource={page.items}
          scroll={{ x: 1120 }}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有符合条件的注册申请" /> }}
          pagination={{
            current: page.page,
            pageSize: page.size,
            total: page.total,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 个申请`,
            onChange: (current, size) => setParams((value) => ({ ...value, page: current, size })),
          }}
        />
      </section>

      <Drawer
        open={Boolean(selected)}
        width={520}
        title="审核与角色开通"
        destroyOnHidden
        onClose={() => {
          if (!submitting) setSelected(null);
        }}
        extra={<Typography.Text code>{selected?.application_id}</Typography.Text>}
        footer={selected?.status === 'PENDING' ? (
          <Space className="registration-review-drawer__actions">
            <Button
              danger
              disabled={submitting}
              onClick={() => {
                setRejectError(null);
                setRejectTarget(selected);
              }}
            >
              驳回申请
            </Button>
            <Button
              type="primary"
              icon={<SafetyCertificateOutlined />}
              loading={submitting}
              disabled={catalogueLoading || catalogueError}
              onClick={() => approvalForm.submit()}
            >
              通过并开通账号
            </Button>
          </Space>
        ) : undefined}
      >
        {selected && (
          <>
            {approvalError && (
              <Alert className="registration-review-action-error" type="error" showIcon message={approvalError} />
            )}
            <div className="registration-review-identity">
              <span aria-hidden="true">{selected.display_name.trim().slice(0, 1) || '@'}</span>
              <div>
                <strong>{selected.display_name} · 邮箱已验证</strong>
                <p>{selected.email}</p>
                <p>{selected.team_name || '团队未填写'}</p>
              </div>
            </div>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="申请说明">{selected.application_note || '—'}</Descriptions.Item>
              <Descriptions.Item label="提交时间">{formatDate(selected.created_at)}</Descriptions.Item>
              {selected.status === 'APPROVED' && (
                <Descriptions.Item label="审核租户">
                  {tenantSnapshotLabel(selected.tenant_id)}
                </Descriptions.Item>
              )}
              {selected.status === 'APPROVED' && (
                <Descriptions.Item label="授予角色">
                  {selected.role_ids.length > 0
                    ? selected.role_ids.map(roleSnapshotLabel).join('、')
                    : '—'}
                </Descriptions.Item>
              )}
              {selected.rejection_reason && <Descriptions.Item label="驳回原因">{selected.rejection_reason}</Descriptions.Item>}
            </Descriptions>

            {selected.status === 'PENDING' ? (
              <Form<ApprovalFormValues>
                className="registration-approval-form"
                form={approvalForm}
                layout="vertical"
                preserve={false}
                onFinish={submitApproval}
              >
                <Form.Item
                  name="tenant_id"
                  label="所属租户"
                  rules={[{ required: true, message: '请选择启用中的租户' }]}
                  extra="切换租户会清空已选角色，防止跨租户授权。"
                >
                  <Select
                    loading={catalogueLoading}
                    placeholder="选择启用中的租户"
                    options={enabledTenants.map((tenant) => ({
                      value: tenant.tenant_id,
                      label: `${tenant.name}（${tenant.code}）`,
                    }))}
                  />
                </Form.Item>
                <Form.Item
                  key={selectedTenantId ?? 'no-tenant'}
                  name="role_ids"
                  label="分配角色"
                  preserve={false}
                  rules={[
                    { required: true, message: '请至少选择一个角色' },
                    { type: 'array', min: 1, message: '请至少选择一个角色' },
                  ]}
                >
                  <Select
                    mode="multiple"
                    loading={catalogueLoading}
                    disabled={!selectedTenantId}
                    placeholder={selectedTenantId ? '至少选择一个该租户角色' : '请先选择租户'}
                    options={availableRoles.map((role) => ({
                      value: role.role_id,
                      label: `${role.name}（${role.code}）`,
                    }))}
                  />
                </Form.Item>
                <div className="registration-permission-preview">
                  <strong>权限预览</strong>
                  {permissionPreview.length > 0
                    ? <div>{permissionPreview.map((permission) => <code key={permission}>{permission}</code>)}</div>
                    : <span>选择角色后显示权限集合。</span>}
                </div>
                <Alert
                  className="registration-note"
                  type="info"
                  showIcon
                  message="租户、角色与审核状态由后端一次提交；通知邮件失败不会撤销已完成的审核。"
                />
              </Form>
            ) : (
              <Alert
                className="registration-note"
                type={selected.status === 'APPROVED' ? 'success' : 'error'}
                showIcon
                message={selected.status === 'APPROVED' ? '该申请已通过' : '该申请已驳回'}
                description={selected.reviewed_at ? `审核时间：${formatDate(selected.reviewed_at)}` : undefined}
              />
            )}
          </>
        )}
      </Drawer>

      <Modal
        open={Boolean(rejectTarget)}
        title="驳回注册申请"
        okText="确认驳回"
        okButtonProps={{ danger: true }}
        cancelText="取消"
        confirmLoading={submitting}
        onOk={() => rejectForm.submit()}
        onCancel={() => {
          if (!submitting) setRejectTarget(null);
        }}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          请填写申请人能理解的原因；驳回不会绑定租户或角色。
        </Typography.Paragraph>
        {rejectError && (
          <Alert className="registration-review-action-error" type="error" showIcon message={rejectError} />
        )}
        <Form<RejectFormValues> form={rejectForm} layout="vertical" preserve={false} onFinish={submitReject}>
          <Form.Item
            name="reason"
            label="驳回原因"
            rules={[
              { required: true, whitespace: true, message: '请填写驳回原因' },
              { min: 5, message: '驳回原因至少 5 个字符' },
              { max: 500, message: '驳回原因不能超过 500 个字符' },
            ]}
          >
            <Input.TextArea rows={4} placeholder="例如：请使用企业域邮箱重新提交申请" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
