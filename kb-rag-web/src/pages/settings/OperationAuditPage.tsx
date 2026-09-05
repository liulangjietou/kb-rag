// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { ReloadOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  DatePicker,
  Descriptions,
  Drawer,
  Input,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { listOperationAudits } from '../../api/operationAudit';
import type { OperationAuditEntry } from '../../api/types';
import PageHeader from '../../components/PageHeader';

const PAGE_SIZE = 20;

// The backend binds from/to with LocalDateTime.parse, which rejects a trailing zone designator,
// so the pickers are serialized as zone-less ISO literals instead of Dayjs#toISOString's ...Z form.
const TIME_PARAM_FORMAT = 'YYYY-MM-DDTHH:mm:ss';

/**
 * Operation audit listing (M16-CONTRACTS.md section 7): who did what to which object, one row per
 * successful write endpoint call.
 *
 * <p>Read-only on purpose: audit rows are evidence, and a page that could edit its own evidence
 * would be worthless. Rows land asynchronously after the operation's response, so an action taken
 * a second ago may need a refresh to appear.
 */
export default function OperationAuditPage() {
  const [items, setItems] = useState<OperationAuditEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [moduleFilter, setModuleFilter] = useState('');
  const [usernameFilter, setUsernameFilter] = useState('');
  const [timeRange, setTimeRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [detail, setDetail] = useState<OperationAuditEntry | null>(null);

  const from = timeRange?.[0]?.format(TIME_PARAM_FORMAT);
  const to = timeRange?.[1]?.format(TIME_PARAM_FORMAT);

  const load = useCallback(
    async (targetPage: number, module?: string, username?: string, fromParam?: string, toParam?: string) => {
      setLoading(true);
      try {
        const result = await listOperationAudits({
          module: module || undefined,
          username: username || undefined,
          from: fromParam,
          to: toParam,
          page: targetPage,
          size: PAGE_SIZE,
        });
        setItems(result.items);
        setTotal(result.total);
        setPage(targetPage);
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  useEffect(() => {
    load(1, moduleFilter, usernameFilter, from, to);
    // Text inputs are applied via the 查询 button, not on every keystroke; only the picker re-queries.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load, from, to]);

  const columns: ColumnsType<OperationAuditEntry> = [
    { title: '时间', dataIndex: 'created_at', width: 180 },
    {
      title: '操作人',
      dataIndex: 'username',
      width: 140,
      render: (value: string | null) => value ?? '-',
    },
    {
      title: '模块',
      dataIndex: 'module',
      width: 140,
      render: (value: string) => <Tag>{value}</Tag>,
    },
    { title: '动作', dataIndex: 'action', width: 200 },
    {
      title: '对象',
      key: 'target',
      render: (_, record) =>
        record.target_id ? (
          <Typography.Text code>{`${record.target_type ?? ''} ${record.target_id}`.trim()}</Typography.Text>
        ) : (
          '-'
        ),
    },
    {
      title: '来源 IP',
      dataIndex: 'client_ip',
      width: 140,
      render: (value: string | null) => value ?? '-',
    },
    {
      title: '操作',
      key: 'actions',
      width: 80,
      render: (_, record) => <a onClick={() => setDetail(record)}>详情</a>,
    },
  ];

  return (
    <div className="management-page audit-page">
      <PageHeader
        eyebrow="AUDIT EVIDENCE"
        title="操作审计"
        description="按操作者、模块和时间追溯平台变更，查看操作结果与请求详情。"
      />
      <Card className="management-panel">
      <Space className="management-filter audit-filter" wrap>
        <Input
          allowClear
          placeholder="模块，如 KB / USER"
          style={{ width: 180 }}
          value={moduleFilter}
          onChange={(e) => setModuleFilter(e.target.value)}
          onPressEnter={() => load(1, moduleFilter, usernameFilter, from, to)}
        />
        <Input
          allowClear
          placeholder="操作人用户名"
          style={{ width: 180 }}
          value={usernameFilter}
          onChange={(e) => setUsernameFilter(e.target.value)}
          onPressEnter={() => load(1, moduleFilter, usernameFilter, from, to)}
        />
        <DatePicker.RangePicker
          showTime
          value={timeRange}
          onChange={(range) => setTimeRange(range)}
          placeholder={['开始时间', '结束时间']}
        />
        <Button type="primary" onClick={() => load(1, moduleFilter, usernameFilter, from, to)}>
          查询
        </Button>
        <Button
          icon={<ReloadOutlined />}
          onClick={() => load(page, moduleFilter, usernameFilter, from, to)}
        >
          刷新
        </Button>
        <Typography.Text type="secondary">审计行异步落库，刚执行的操作可能需要稍等刷新</Typography.Text>
      </Space>

      <Table<OperationAuditEntry>
        className="management-table"
        rowKey="audit_id"
        loading={loading}
        columns={columns}
        dataSource={items}
        scroll={{ x: 1080 }}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total,
          showSizeChanger: false,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (nextPage) => load(nextPage, moduleFilter, usernameFilter, from, to),
        }}
      />

      <Drawer
        open={detail !== null}
        width={560}
        title="审计详情"
        onClose={() => setDetail(null)}
      >
        {detail && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="审计 ID">{detail.audit_id}</Descriptions.Item>
            <Descriptions.Item label="时间">{detail.created_at}</Descriptions.Item>
            <Descriptions.Item label="操作人">{detail.username ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="账号 ID">{detail.user_id ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="模块">{detail.module}</Descriptions.Item>
            <Descriptions.Item label="动作">{detail.action}</Descriptions.Item>
            <Descriptions.Item label="对象类型">{detail.target_type ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="对象 ID">{detail.target_id ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="来源 IP">{detail.client_ip ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="请求 ID">{detail.request_id ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="请求详情">
              <Typography.Text code className="technical-text audit-detail-text">
                {detail.detail ?? '-'}
              </Typography.Text>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
      </Card>
    </div>
  );
}
