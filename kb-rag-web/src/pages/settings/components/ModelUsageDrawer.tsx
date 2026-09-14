// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Drawer, Empty, Input, Modal, Progress, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { getModelUsageSummary, listModelUsageRecords } from '../../../api/modelUsage';
import { useAuth } from '../../../auth/AuthContext';
import { PERMISSIONS } from '../../../auth/permissions';
import ModelUsageCallDetails from './ModelUsageCallDetails';
import './model-usage.css';
import type { ModelUsageRecord, ModelUsageSummary, PageResult, TenantSummary } from '../../../api/types';

interface Props {
  tenant: TenantSummary | null;
  onClose: () => void;
}

const PAGE_SIZE = 20;
const USAGE_STATUS: Record<ModelUsageRecord['status'], { label: string; color: string }> = {
  RESERVED: { label: '已预占', color: 'processing' },
  SUCCEEDED: { label: '已完成', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
  CANCELLED: { label: '已停止', color: 'default' },
};

function shanghaiMonth(): string {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
  }).formatToParts(new Date());
  const year = parts.find((part) => part.type === 'year')?.value ?? '';
  const month = parts.find((part) => part.type === 'month')?.value ?? '';
  return `${year}-${month}`;
}

function formatTokens(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value);
}

function formatCost(currency: string, micros: number): string {
  return `${currency} ${(micros / 1_000_000).toFixed(6)}`;
}

/** 按身份与租户重建视图，避免旧租户数据在新标题下出现。 */
export default function ModelUsageDrawer({ tenant, onClose }: Props) {
  const { token, can } = useAuth();
  if (!tenant || !can(PERMISSIONS.TENANT_MANAGE)) return null;
  return <TenantUsage key={`${token}:${tenant.tenant_id}`} tenant={tenant} onClose={onClose} />;
}

function TenantUsage({ tenant, onClose }: { tenant: TenantSummary; onClose: () => void }) {
  const [month, setMonth] = useState(shanghaiMonth());
  const validMonth = /^\d{4}-(0[1-9]|1[0-2])$/.test(month);
  return <Drawer open title={`模型用量 - ${tenant.name}`} width="min(1100px, 100vw)" rootClassName="model-usage-drawer" onClose={onClose} destroyOnHidden>
    <div className="model-usage-toolbar"><label className="model-usage-month" htmlFor="usage-month">月份（UTC+8）
      <Input id="usage-month" aria-label="用量月份" type="month" value={month} onChange={event => setMonth(event.target.value)} />
    </label></div>
    {validMonth ? <MonthUsage key={month} tenantId={tenant.tenant_id} month={month} />
      : <Alert type="info" showIcon message="请选择有效的用量月份。" />}
  </Drawer>;
}

/** 月份切换销毁旧状态；同一月份内的翻页、刷新也按请求顺序接受结果。 */
function MonthUsage({ tenantId, month }: { tenantId: string; month: string }) {
  const [data, setData] = useState<{ summary: ModelUsageSummary; records: PageResult<ModelUsageRecord> }>();
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [selected, setSelected] = useState<ModelUsageRecord | null>(null);
  const sequence = useRef(0);
  const load = useCallback(async () => {
    const request = ++sequence.current;
    setData(undefined); setSelected(null); setError(false); setLoading(true);
    try {
      const [summary, records] = await Promise.all([
        getModelUsageSummary(tenantId, month), listModelUsageRecords(tenantId, month, page, PAGE_SIZE),
      ]);
      if (request !== sequence.current) return;
      const last = Math.max(1, Math.ceil(records.total / PAGE_SIZE));
      if (page > last) { setPage(last); return; }
      setData({ summary, records });
    } catch {
      if (request === sequence.current) setError(true);
    } finally { if (request === sequence.current) setLoading(false); }
  }, [tenantId, month, page]);
  useEffect(() => { void load(); return () => { sequence.current += 1; }; }, [load]);
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') void load(); };
    window.addEventListener('focus', refresh); document.addEventListener('visibilitychange', refresh);
    return () => { window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh); };
  }, [load]);
  const columns: ColumnsType<ModelUsageRecord> = [
    { title: '时间', dataIndex: 'created_at', width: 160, render: (value: string) => value.replace('T', ' ').replace(/\.\d+$/, '') },
    { title: '模型调用', key: 'model', width: 220, render: (_, row) => <Space direction="vertical" size={0}>
      <Typography.Text>{row.model}</Typography.Text><Typography.Text type="secondary">{row.provider} / {row.capability}</Typography.Text>
    </Space> },
    { title: 'Token', key: 'tokens', width: 150, render: (_, row) => <Space wrap size={4}>
      {row.status === 'RESERVED' ? `预占 ${formatTokens(row.reserved_tokens)}` : formatTokens(row.total_tokens)}
      {row.estimated && <Tag color="orange">估算</Tag>}
    </Space> },
    { title: '成本', key: 'cost', width: 150, render: (_, row) => row.status === 'RESERVED' ? '待结算'
      : row.priced && row.currency ? formatCost(row.currency, row.cost_micros) : row.total_tokens > 0 ? <Tag>未定价</Tag> : '无已结算用量' },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: ModelUsageRecord['status']) => <Tag color={USAGE_STATUS[value]?.color}>{USAGE_STATUS[value]?.label ?? value}</Tag> },
    { title: '诊断', key: 'diagnostic', width: 110, render: (_, row) => <Button size="small" aria-haspopup="dialog" onClick={() => setSelected(row)}>调用详情</Button> },
  ];
  const summary = data?.summary;
  const quotaPercent = summary && summary.quota_tokens > 0
    ? Math.min(100, Math.round(((summary.used_tokens + summary.reserved_tokens) / summary.quota_tokens) * 100)) : 0;
  return <section aria-label="模型用量与调用记录">
    <div className="model-usage-toolbar"><Button loading={loading} onClick={() => void load()}>刷新用量</Button>
      <span className="model-usage-note">预占会计入配额占用；完成后按实际或保守估算用量结算。</span></div>
    {error && <Alert type="error" showIcon message="用量读取失败，旧数字已清除。请刷新重试。" />}
    {summary && <div className="model-usage-summary"><Card size="small">
      <Descriptions column={{ xs: 1, sm: 2, xl: 4 }} size="small" items={[
        { key: 'used', label: '已结算', children: formatTokens(summary.used_tokens) },
        { key: 'reserved', label: '待结算预占', children: formatTokens(summary.reserved_tokens) },
        { key: 'remaining', label: '剩余可用', children: summary.quota_tokens === 0 ? '不限额'
          : summary.remaining_tokens == null ? '暂不可用' : formatTokens(summary.remaining_tokens) },
        { key: 'quota', label: '本月配额', children: summary.quota_tokens === 0 ? '不限额' : formatTokens(summary.quota_tokens) },
        { key: 'cost', label: '已知成本', children: summary.costs.length === 0 ? '暂无已知成本' : summary.costs.map(cost => formatCost(cost.currency, cost.cost_micros)).join(' / ') },
      ]} />
      {summary.quota_tokens > 0 && <Progress percent={quotaPercent} status={quotaPercent >= 100 ? 'exception' : 'normal'} />}
    </Card>
    {(summary.estimated_calls > 0 || summary.unpriced_calls > 0) && <Alert type="warning" showIcon
      message={`本月有 ${summary.estimated_calls} 次用量为保守估算，${summary.unpriced_calls} 次未命中价格配置`}
      description="估算调用按预占上界结算；有用量但未定价的调用仍占用 Token 配额，不能据此判断成本为零。" />}
    </div>}
    {!error && <div className="model-usage-table-scroll" role="region" aria-label="模型调用明细表，可横向滚动" tabIndex={0}>
      <Table<ModelUsageRecord> rowKey="usage_id" loading={loading || (!data && !error)} columns={columns} dataSource={data?.records.items ?? []} scroll={{ x: 1000 }}
        locale={{ emptyText: loading || !data ? '正在读取用量…' : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本月暂无模型调用记录" /> }}
        pagination={{ current: page, pageSize: PAGE_SIZE, total: data?.records.total ?? 0, showSizeChanger: false, onChange: setPage }} />
    </div>}
    <Modal open={selected !== null} title={selected ? `${selected.model} 调用详情` : '调用详情'} width={760}
      onCancel={() => setSelected(null)} footer={null} destroyOnHidden>
      {selected && <ModelUsageCallDetails record={selected} />}
    </Modal>
  </section>;
}
