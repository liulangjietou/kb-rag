// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { Alert, Card, Descriptions, Drawer, Input, Progress, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table';
import { getModelUsageSummary, listModelUsageRecords } from '../../../api/modelUsage';
import type { ModelUsageRecord, ModelUsageSummary, PageResult, TenantSummary } from '../../../api/types';

interface Props {
  tenant: TenantSummary | null;
  onClose: () => void;
}

const PAGE_SIZE = 20;

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

/** Tenant month summary plus the prompt-free call ledger. */
export default function ModelUsageDrawer({ tenant, onClose }: Props) {
  const [month, setMonth] = useState(shanghaiMonth());
  const [summary, setSummary] = useState<ModelUsageSummary | null>(null);
  const [records, setRecords] = useState<PageResult<ModelUsageRecord>>({ items: [], page: 1, size: PAGE_SIZE, total: 0 });
  const [loading, setLoading] = useState(false);

  const load = useCallback(async (page = 1) => {
    if (!tenant || !/^\d{4}-\d{2}$/.test(month)) return;
    setLoading(true);
    try {
      const [nextSummary, nextRecords] = await Promise.all([
        getModelUsageSummary(tenant.tenant_id, month),
        listModelUsageRecords(tenant.tenant_id, month, page, PAGE_SIZE),
      ]);
      setSummary(nextSummary);
      setRecords(nextRecords);
    } finally {
      setLoading(false);
    }
  }, [month, tenant]);

  useEffect(() => {
    if (tenant) load();
  }, [tenant, load]);

  const columns: ColumnsType<ModelUsageRecord> = [
    {
      title: '时间',
      dataIndex: 'created_at',
      width: 170,
    },
    {
      title: '模型调用',
      key: 'model',
      width: 260,
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <Typography.Text>{row.model}</Typography.Text>
          <Typography.Text type="secondary">{row.provider} / {row.capability}</Typography.Text>
        </Space>
      ),
    },
    {
      title: 'Token',
      dataIndex: 'total_tokens',
      width: 130,
      render: (value: number, row) => (
        <Space size={4}>
          {formatTokens(value)}
          {row.estimated && <Tag color="orange">估算</Tag>}
        </Space>
      ),
    },
    {
      title: '成本',
      key: 'cost',
      width: 150,
      render: (_, row) => row.priced && row.currency
        ? formatCost(row.currency, row.cost_micros)
        : <Tag>未定价</Tag>,
    },
    {
      title: '来源',
      dataIndex: 'source',
      width: 150,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: ModelUsageRecord['status']) => (
        <Tag color={value === 'SUCCEEDED' ? 'success' : value === 'FAILED' ? 'error' : 'processing'}>{value}</Tag>
      ),
    },
    {
      title: 'request_id',
      dataIndex: 'request_id',
      width: 210,
      render: (value: string | null) => value ? <Typography.Text code>{value}</Typography.Text> : '-',
    },
  ];

  const quotaPercent = summary && summary.quota_tokens > 0
    ? Math.min(100, Math.round(((summary.used_tokens + summary.reserved_tokens) / summary.quota_tokens) * 100))
    : 0;

  return (
    <Drawer
      open={!!tenant}
      title={tenant ? `模型用量 - ${tenant.name}` : '模型用量'}
      width={1100}
      onClose={onClose}
      destroyOnHidden
      extra={(
        <Space>
          <Typography.Text type="secondary">月份（UTC+8）</Typography.Text>
          <Input type="month" value={month} onChange={(event) => setMonth(event.target.value)} style={{ width: 150 }} />
        </Space>
      )}
    >
      {summary && (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Card size="small">
            <Descriptions column={4} size="small">
              <Descriptions.Item label="已结算">{formatTokens(summary.used_tokens)}</Descriptions.Item>
              <Descriptions.Item label="调用中预占">{formatTokens(summary.reserved_tokens)}</Descriptions.Item>
              <Descriptions.Item label="剩余">
                {summary.remaining_tokens === null ? '不限额' : formatTokens(summary.remaining_tokens)}
              </Descriptions.Item>
              <Descriptions.Item label="成本">
                {summary.costs.length === 0 ? '-' : summary.costs.map((cost) => formatCost(cost.currency, cost.cost_micros)).join(' / ')}
              </Descriptions.Item>
            </Descriptions>
            {summary.quota_tokens > 0 && (
              <Progress percent={quotaPercent} status={quotaPercent >= 100 ? 'exception' : 'active'} />
            )}
          </Card>
          {(summary.estimated_calls > 0 || summary.unpriced_calls > 0) && (
            <Alert
              type="warning"
              showIcon
              message={`本月有 ${summary.estimated_calls} 次用量为保守估算，${summary.unpriced_calls} 次未命中价格配置`}
              description="估算调用按预占上界结算，未定价调用仍严格占用 Token 配额，但成本不伪造为已知价格。"
            />
          )}
        </Space>
      )}
      <Table<ModelUsageRecord>
        style={{ marginTop: 16 }}
        rowKey="usage_id"
        loading={loading}
        columns={columns}
        dataSource={records.items}
        scroll={{ x: 1200 }}
        pagination={{ current: records.page, pageSize: records.size, total: records.total, showSizeChanger: false }}
        onChange={(pagination: TablePaginationConfig) => load(pagination.current ?? 1)}
      />
    </Drawer>
  );
}
