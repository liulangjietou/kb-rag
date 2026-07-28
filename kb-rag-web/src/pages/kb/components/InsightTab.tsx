// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useState } from 'react';
import { Button, Card, Col, DatePicker, Row, Select, Space, Statistic, Table, Tag, Typography } from 'antd';
import type { Dayjs } from 'dayjs';
import { getSearchInsightStats, listSearchInsights } from '../../../api/searchInsight';
import type { SearchInsightEntry, SearchInsightSource, SearchInsightStats, TopZeroHitQuery } from '../../../api/types';
import { SEARCH_INSIGHT_SOURCE_META, metaOf } from '../../../utils/statusMeta';

interface InsightTabProps {
  kbId: string;
}

const PAGE_SIZE = 20;

// The backend binds from/to with LocalDateTime.parse, which rejects a trailing zone designator,
// so the pickers are serialized as zone-less ISO literals instead of Dayjs#toISOString's ...Z form.
const TIME_PARAM_FORMAT = 'YYYY-MM-DDTHH:mm:ss';

/**
 * 检索洞察 tab of the KB detail page (M10-CONTRACTS.md section 3): the content-gap report
 * (totals / zero-hit rate / degraded count / Top zero-hit query groups) over a picked time
 * window, plus the paged insight detail listing with a zero-hit filter. Only masked digests are
 * shown -- the raw queries are never stored server-side for this table.
 */
export default function InsightTab({ kbId }: InsightTabProps) {
  const [stats, setStats] = useState<SearchInsightStats | null>(null);
  const [statsLoading, setStatsLoading] = useState(false);
  const [items, setItems] = useState<SearchInsightEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [listLoading, setListLoading] = useState(false);
  const [zeroHitFilter, setZeroHitFilter] = useState<boolean | undefined>();
  const [timeRange, setTimeRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);

  const from = timeRange?.[0]?.format(TIME_PARAM_FORMAT);
  const to = timeRange?.[1]?.format(TIME_PARAM_FORMAT);

  const loadStats = useCallback(async (fromParam?: string, toParam?: string) => {
    setStatsLoading(true);
    try {
      setStats(await getSearchInsightStats(kbId, { from: fromParam, to: toParam }));
    } finally {
      setStatsLoading(false);
    }
  }, [kbId]);

  const loadList = useCallback(
    async (targetPage: number, zeroHit?: boolean, fromParam?: string, toParam?: string) => {
      setListLoading(true);
      try {
        const result = await listSearchInsights(kbId, {
          zero_hit: zeroHit,
          from: fromParam,
          to: toParam,
          page: targetPage,
          size: PAGE_SIZE,
        });
        setItems(result.items);
        setTotal(result.total);
        setPage(targetPage);
      } finally {
        setListLoading(false);
      }
    },
    [kbId],
  );

  useEffect(() => {
    loadStats(from, to);
  }, [loadStats, from, to]);

  useEffect(() => {
    loadList(1, zeroHitFilter, from, to);
  }, [loadList, zeroHitFilter, from, to]);

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <DatePicker.RangePicker
          showTime
          value={timeRange}
          onChange={(range) => setTimeRange(range)}
          placeholder={['开始时间', '结束时间']}
        />
        <Select
          allowClear
          placeholder="命中筛选"
          style={{ width: 140 }}
          value={zeroHitFilter}
          onChange={(value) => setZeroHitFilter(value)}
          options={[
            { label: '仅零命中', value: true },
            { label: '仅有命中', value: false },
          ]}
        />
        <Button
          onClick={() => {
            loadStats(from, to);
            loadList(page, zeroHitFilter, from, to);
          }}
        >
          刷新
        </Button>
        <Typography.Text type="secondary">统计默认最近 7 天，可通过时间范围调整</Typography.Text>
      </Space>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small" loading={statsLoading}>
            <Statistic title="总检索次数" value={stats?.total ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" loading={statsLoading}>
            <Statistic
              title="零命中率"
              value={(stats?.zero_hit_rate ?? 0) * 100}
              precision={1}
              suffix="%"
              valueStyle={(stats?.zero_hit_rate ?? 0) > 0.2 ? { color: '#cf1322' } : undefined}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" loading={statsLoading}>
            <Statistic title="零命中次数" value={stats?.zero_hit_count ?? 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" loading={statsLoading}>
            <Statistic title="降级次数" value={stats?.degraded_count ?? 0} />
          </Card>
        </Col>
      </Row>

      <Card size="small" title="Top 未命中问题（按归一化后的相同问题分组）" style={{ marginBottom: 16 }}>
        <Table<TopZeroHitQuery>
          rowKey="query_digest"
          size="small"
          loading={statsLoading}
          dataSource={stats?.top_zero_hit_queries ?? []}
          pagination={false}
          locale={{ emptyText: '时间窗口内没有零命中检索' }}
          columns={[
            { title: '问题摘要（已脱敏）', dataIndex: 'query_digest' },
            { title: '未命中次数', dataIndex: 'count', width: 120 },
            { title: '最近发生', dataIndex: 'last_at', width: 200 },
          ]}
        />
      </Card>

      <Table<SearchInsightEntry>
        rowKey="insight_id"
        loading={listLoading}
        dataSource={items}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total,
          showSizeChanger: false,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (nextPage) => loadList(nextPage, zeroHitFilter, from, to),
        }}
        columns={[
          { title: '问题摘要（已脱敏）', dataIndex: 'query_digest', ellipsis: true },
          {
            title: '来源',
            dataIndex: 'source',
            width: 120,
            render: (source: SearchInsightSource) => {
              const meta = metaOf(SEARCH_INSIGHT_SOURCE_META, source);
              return <Tag color={meta.color}>{meta.label}</Tag>;
            },
          },
          {
            title: '命中数',
            dataIndex: 'result_count',
            width: 100,
            render: (count: number, record) =>
              record.zero_hit ? <Tag color="error">零命中</Tag> : count,
          },
          {
            title: '最高分',
            dataIndex: 'top_score',
            width: 100,
            render: (score: number | null) => (score == null ? '-' : score.toFixed(4)),
          },
          {
            title: '降级',
            dataIndex: 'degraded',
            width: 180,
            render: (degraded: string[]) =>
              degraded.length === 0 ? '-' : degraded.map((item) => <Tag key={item}>{item}</Tag>),
          },
          { title: '时间', dataIndex: 'created_at', width: 180 },
        ]}
      />
    </>
  );
}
