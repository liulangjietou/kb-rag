// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Col, DatePicker, Row, Select, Space, Statistic, Table, Tag, Typography } from 'antd';
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
  const [statsLoading, setStatsLoading] = useState(true);
  const [statsError, setStatsError] = useState(false);
  const [statsUpdatedAt, setStatsUpdatedAt] = useState<string | null>(null);
  const [items, setItems] = useState<SearchInsightEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState(false);
  const [zeroHitFilter, setZeroHitFilter] = useState<boolean | undefined>();
  const [timeRange, setTimeRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const statsSequence = useRef(0);
  const listSequence = useRef(0);

  const from = timeRange?.[0]?.format(TIME_PARAM_FORMAT);
  const to = timeRange?.[1]?.format(TIME_PARAM_FORMAT);

  const loadStats = useCallback(async (fromParam?: string, toParam?: string) => {
    const sequence = ++statsSequence.current;
    setStatsLoading(true);
    setStatsError(false);
    setStats(null);
    setStatsUpdatedAt(null);
    try {
      const result = await getSearchInsightStats(kbId, { from: fromParam, to: toParam });
      if (sequence !== statsSequence.current) return;
      setStats(result);
      setStatsUpdatedAt(new Date().toLocaleTimeString('zh-CN', { hour12: false }));
    } catch {
      if (sequence === statsSequence.current) setStatsError(true);
    } finally {
      if (sequence === statsSequence.current) setStatsLoading(false);
    }
  }, [kbId]);

  const loadList = useCallback(
    async (targetPage: number, zeroHit?: boolean, fromParam?: string, toParam?: string) => {
      const sequence = ++listSequence.current;
      setListLoading(true);
      setListError(false);
      setItems([]);
      setTotal(0);
      try {
        const result = await listSearchInsights(kbId, {
          zero_hit: zeroHit,
          from: fromParam,
          to: toParam,
          page: targetPage,
          size: PAGE_SIZE,
        });
        if (sequence !== listSequence.current) return;
        setItems(result.items);
        setTotal(result.total);
        setPage(targetPage);
      } catch {
        if (sequence === listSequence.current) setListError(true);
      } finally {
        if (sequence === listSequence.current) setListLoading(false);
      }
    },
    [kbId],
  );

  useEffect(() => {
    void loadStats(from, to);
    return () => { statsSequence.current += 1; };
  }, [loadStats, from, to]);

  useEffect(() => {
    void loadList(1, zeroHitFilter, from, to);
    return () => { listSequence.current += 1; };
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
          aria-label="刷新洞察"
          loading={statsLoading || listLoading}
          onClick={() => {
            loadStats(from, to);
            loadList(page, zeroHitFilter, from, to);
          }}
        >
          刷新
        </Button>
        <Typography.Text type="secondary">统计默认最近 7 天，可通过时间范围调整</Typography.Text>
        {statsUpdatedAt && <Typography.Text type="secondary">最近成功更新 {statsUpdatedAt}</Typography.Text>}
      </Space>

      {statsError && (
        <Alert type="error" showIcon message="统计加载失败" description="当前数值暂不可用，请刷新重试。未能获取统计不代表没有检索。"
          style={{ marginBottom: 16 }} />
      )}

      <Row className="insight-stat-grid" gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}>
          <Card size="small" loading={statsLoading}>
            <Statistic title="总检索次数" value={stats?.total ?? '—'} />
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card size="small" loading={statsLoading}>
            <Statistic
              title="零命中率"
              value={stats ? stats.zero_hit_rate * 100 : '—'}
              precision={1}
              suffix={stats ? '%' : undefined}
              valueStyle={(stats?.zero_hit_rate ?? 0) > 0.2 ? { color: 'var(--kb-color-danger)' } : undefined}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card size="small" loading={statsLoading}>
            <Statistic title="零命中次数" value={stats?.zero_hit_count ?? '—'} />
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card size="small" loading={statsLoading}>
            <Statistic title="降级次数" value={stats?.degraded_count ?? '—'} />
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
          locale={{ emptyText: statsError ? '统计暂不可用' : '时间窗口内没有零命中检索' }}
          columns={[
            { title: '问题摘要（已脱敏）', dataIndex: 'query_digest' },
            { title: '未命中次数', dataIndex: 'count', width: 120 },
            { title: '最近发生', dataIndex: 'last_at', width: 200 },
          ]}
        />
      </Card>

      {listError && (
        <Alert type="error" showIcon message="检索记录加载失败" description="筛选条件已保留，请刷新重试。"
          style={{ marginBottom: 16 }} />
      )}
      <Table<SearchInsightEntry>
        rowKey="insight_id"
        loading={listLoading}
        dataSource={items}
        locale={{ emptyText: listError ? '记录暂不可用' : '当前筛选条件下没有检索记录' }}
        pagination={listError ? false : {
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
