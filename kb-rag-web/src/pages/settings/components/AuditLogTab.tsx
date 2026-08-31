// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { Button, Card, Col, DatePicker, Form, Row, Select, Space, Statistic, Table, Tag, Typography } from 'antd';
import type { Dayjs } from 'dayjs';
import { getAuditLogStats, listAuditLogs } from '../../../api/auditLog';
import { listApiKeys } from '../../../api/apiKey';
import type { ApiAuditLogEntry, ApiKey, AuditLogStats, AuditTargetStage } from '../../../api/types';
import { describeDegradedReason } from '../../../utils/statusMeta';

interface FilterFormValues {
  key_id?: string;
  target_stage?: AuditTargetStage;
  time_range?: [Dayjs, Dayjs];
}

const TARGET_STAGE_OPTIONS: { label: string; value: AuditTargetStage }[] = [
  { label: '正式版（release）', value: 'release' },
  { label: '测试版灰度（beta）', value: 'beta' },
];

/**
 * 审计日志查询 tab (M4c-CONTRACTS.md sections 1/3/4): filter by Key / time range / target_stage,
 * a simple call-volume statistics header, and the filtered log table (latency/degraded columns).
 */
export default function AuditLogTab() {
  const [form] = Form.useForm<FilterFormValues>();
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [logs, setLogs] = useState<ApiAuditLogEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [stats, setStats] = useState<AuditLogStats | null>(null);

  useEffect(() => {
    listApiKeys().then(setKeys);
  }, []);

  const buildFilterParams = useCallback(() => {
    const values = form.getFieldsValue();
    return {
      key_id: values.key_id,
      target_stage: values.target_stage,
      from: values.time_range?.[0]?.toISOString(),
      to: values.time_range?.[1]?.toISOString(),
    };
  }, [form]);

  const load = useCallback(
    async (targetPage: number) => {
      setLoading(true);
      try {
        const filters = buildFilterParams();
        const [logResult, statsResult] = await Promise.all([
          listAuditLogs({ ...filters, page: targetPage }),
          getAuditLogStats(filters),
        ]);
        setLogs(logResult.items);
        setTotal(logResult.total);
        setStats(statsResult);
      } finally {
        setLoading(false);
      }
    },
    [buildFilterParams],
  );

  useEffect(() => {
    load(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleQuery = () => {
    setPage(1);
    load(1);
  };

  const handleReset = () => {
    form.resetFields();
    setPage(1);
    load(1);
  };

  return (
    <div className="audit-log-workbench">
      <Form<FilterFormValues> className="management-filter" form={form} layout="inline">
        <Form.Item name="key_id" label="API Key">
          <Select
            style={{ width: 220 }}
            allowClear
            placeholder="全部 Key"
            options={keys.map((k) => ({ label: `${k.name}（${k.prefix}）`, value: k.key_id }))}
          />
        </Form.Item>
        <Form.Item name="target_stage" label="target_stage">
          <Select style={{ width: 180 }} allowClear placeholder="全部" options={TARGET_STAGE_OPTIONS} />
        </Form.Item>
        <Form.Item name="time_range" label="时间范围">
          <DatePicker.RangePicker showTime />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" onClick={handleQuery}>
              查询
            </Button>
            <Button onClick={handleReset}>重置</Button>
          </Space>
        </Form.Item>
      </Form>

      {stats && (
        <Row className="metric-grid" gutter={[16, 16]}>
          <Col xs={24} sm={12} xl={6}>
            <Card size="small">
              <Statistic title="调用量" value={stats.total_calls} />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card size="small">
              <Statistic title="平均耗时 (ms)" value={stats.avg_latency_ms} precision={0} />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card size="small">
              <Statistic title="降级次数" value={stats.degraded_calls} valueStyle={{ color: stats.degraded_calls > 0 ? 'var(--kb-color-warning)' : undefined }} />
            </Card>
          </Col>
          <Col xs={24} sm={12} xl={6}>
            <Card size="small">
              <Statistic title="错误次数" value={stats.error_calls} valueStyle={{ color: stats.error_calls > 0 ? 'var(--kb-color-danger)' : undefined }} />
            </Card>
          </Col>
        </Row>
      )}

      <Table<ApiAuditLogEntry>
        className="management-table"
        rowKey="audit_log_id"
        loading={loading}
        dataSource={logs}
        pagination={{ current: page, total, onChange: (p) => { setPage(p); load(p); }, showSizeChanger: false }}
        scroll={{ x: true }}
        columns={[
          { title: '时间', dataIndex: 'created_at', width: 170 },
          { title: 'key_id', dataIndex: 'key_id', width: 140, ellipsis: true },
          { title: 'app_version_id', dataIndex: 'app_version_id', width: 140, ellipsis: true },
          {
            title: 'target_stage',
            dataIndex: 'target_stage',
            width: 100,
            render: (stage: AuditTargetStage) => <Tag color={stage === 'beta' ? 'gold' : 'success'}>{stage}</Tag>,
          },
          { title: 'query 摘要（已脱敏）', dataIndex: 'query_digest', ellipsis: true },
          { title: '命中文档数', width: 90, render: (_, record) => record.hit_doc_ids.length },
          { title: 'latency (ms)', dataIndex: 'latency_ms', width: 110 },
          {
            title: 'degraded',
            dataIndex: 'degraded',
            width: 200,
            render: (degraded: string[]) =>
              degraded.length === 0 ? (
                <Typography.Text type="secondary">无</Typography.Text>
              ) : (
                <Space wrap>
                  {degraded.map((d) => (
                    <Tag key={d} color="warning" title={describeDegradedReason(d)}>
                      {d}
                    </Tag>
                  ))}
                </Space>
              ),
          },
          { title: 'request_id', dataIndex: 'request_id', width: 160, ellipsis: true },
        ]}
      />
    </div>
  );
}
