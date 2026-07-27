// Author: owlzhangfq@gmail.com
import { useCallback, useEffect, useRef, useState } from 'react';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Empty, Input, Progress, Row, Space, Statistic, Switch, Table, Tag, Typography, message } from 'antd';
import { getGraphSummary, listGraphEntities, triggerGraphExtract, updateGraphConfig } from '../../../api/graph';
import type { GraphEntity, GraphSummary, KnowledgeBase } from '../../../api/types';
import { GRAPH_FUSION_MUTEX_HINT, GRAPH_TASK_STATUS_META, metaOf } from '../../../utils/statusMeta';
import GraphEntityChunksDrawer from './GraphEntityChunksDrawer';
import GraphVisualization from './GraphVisualization';

// Extraction progress is polled at the same 3s cadence used everywhere else in this codebase for
// async server-side jobs (KbDetailPage's rebuild alert, VersionDrawer's REBUILD polling).
const TASK_POLL_INTERVAL_MS = 3000;
const ENTITY_LIST_PAGE_SIZE = 10;
const VISUALIZATION_ENTITY_LIMIT = 50;
const TASK_IN_PROGRESS_STATUSES = ['PENDING', 'RUNNING'];

interface GraphTabProps {
  kbId: string;
  kb: KnowledgeBase | null;
  /** Lets the parent page refresh its own KnowledgeBase copy after the switch changes. */
  onKbChanged: () => void;
}

/**
 * 知识库详情 "知识图谱" tab (M7-CONTRACTS.md section 2): graph_enabled switch with the RRF-mutex
 * advisory, extraction progress (polls /graph/summary while a task is in flight), summary stat
 * cards, a searchable/paginated entity list with source-chunk drill-down, a manual re-extract
 * button, and the top-50 entity relationship visualization.
 */
export default function GraphTab({ kbId, kb, onKbChanged }: GraphTabProps) {
  const [summary, setSummary] = useState<GraphSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [toggling, setToggling] = useState(false);
  const [extracting, setExtracting] = useState(false);

  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [entities, setEntities] = useState<GraphEntity[]>([]);
  const [entityTotal, setEntityTotal] = useState(0);
  const [entitiesLoading, setEntitiesLoading] = useState(false);

  const [vizEntities, setVizEntities] = useState<GraphEntity[]>([]);
  const [vizLoading, setVizLoading] = useState(false);

  const [drillEntityName, setDrillEntityName] = useState<string | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadSummary = useCallback(async () => {
    const result = await getGraphSummary(kbId);
    setSummary(result);
    return result;
  }, [kbId]);

  const loadEntities = useCallback(async () => {
    setEntitiesLoading(true);
    try {
      const result = await listGraphEntities(kbId, { query: query || undefined, page, size: ENTITY_LIST_PAGE_SIZE });
      setEntities(result.items);
      setEntityTotal(result.total);
    } finally {
      setEntitiesLoading(false);
    }
  }, [kbId, query, page]);

  const loadVisualization = useCallback(async () => {
    setVizLoading(true);
    try {
      const result = await listGraphEntities(kbId, { page: 1, size: VISUALIZATION_ENTITY_LIMIT });
      setVizEntities(result.items);
    } finally {
      setVizLoading(false);
    }
  }, [kbId]);

  useEffect(() => {
    setSummaryLoading(true);
    loadSummary().finally(() => setSummaryLoading(false));
    loadVisualization();
  }, [loadSummary, loadVisualization]);

  useEffect(() => {
    loadEntities();
  }, [loadEntities]);

  // Poll /graph/summary while a GRAPH_EXTRACT/GRAPH_CLEANUP task is in flight; refresh the entity
  // list + visualization once it settles so the newly extracted entities show up without a manual
  // page reload.
  const taskInProgress = summary?.latest_task ? TASK_IN_PROGRESS_STATUSES.includes(summary.latest_task.status) : false;
  useEffect(() => {
    if (!taskInProgress) {
      return;
    }
    pollTimerRef.current = setInterval(async () => {
      const latest = await loadSummary();
      if (latest.latest_task && !TASK_IN_PROGRESS_STATUSES.includes(latest.latest_task.status)) {
        message.success(latest.latest_task.status === 'SUCCESS' ? '抽取完成' : '抽取任务失败');
        loadEntities();
        loadVisualization();
      }
    }, TASK_POLL_INTERVAL_MS);
    return () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
      }
    };
  }, [taskInProgress, loadSummary, loadEntities, loadVisualization]);

  const graphEnabled = summary?.graph_enabled ?? kb?.graph_enabled ?? false;

  const handleToggle = async (checked: boolean) => {
    setToggling(true);
    try {
      await updateGraphConfig(kbId, { enabled: checked });
      message.success(checked ? '已开启知识图谱' : '已关闭知识图谱（历史图数据保留，重开无需重抽）');
      await loadSummary();
      onKbChanged();
    } finally {
      setToggling(false);
    }
  };

  const handleExtract = async () => {
    setExtracting(true);
    try {
      await triggerGraphExtract(kbId);
      message.success('已提交全量重抽任务');
      await loadSummary();
    } finally {
      setExtracting(false);
    }
  };

  const handleSearch = (value: string) => {
    setQuery(value.trim());
    setPage(1);
  };

  const latestTask = summary?.latest_task ?? null;
  const taskMeta = latestTask ? metaOf(GRAPH_TASK_STATUS_META, latestTask.status) : null;

  return (
    <div>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space align="center">
            <Switch checked={graphEnabled} loading={toggling || summaryLoading} onChange={handleToggle} />
            <Typography.Text strong>启用知识图谱（GraphRAG）</Typography.Text>
          </Space>
          {graphEnabled && (
            <Alert
              type="info"
              showIcon
              message={GRAPH_FUSION_MUTEX_HINT}
              description="若检索调试页或某个应用版本已为该库选择「加权归一化融合」，保存/调用会被服务端拒绝（INVALID_PARAM），需先改回 RRF。"
            />
          )}
          <Space>
            <Button icon={<ReloadOutlined />} disabled={!graphEnabled || taskInProgress} loading={extracting} onClick={handleExtract}>
              重新抽取
            </Button>
            {!graphEnabled && <Typography.Text type="secondary">开启开关后才能触发抽取</Typography.Text>}
          </Space>
          {latestTask && (
            <Space direction="vertical" style={{ width: '100%' }} size={4}>
              <Space wrap>
                <Typography.Text type="secondary">最近任务：</Typography.Text>
                <Tag color={taskMeta?.color}>{taskMeta?.label}</Tag>
                {latestTask.skipped_chunk_count ? (
                  <Tag color="warning">跳过 {latestTask.skipped_chunk_count} 个分片（输出校验未通过）</Tag>
                ) : null}
              </Space>
              {taskInProgress && (
                <Progress
                  percent={latestTask.progress ?? 99}
                  status="active"
                  showInfo={latestTask.progress != null}
                  size="small"
                />
              )}
              {latestTask.status === 'FAILED' && latestTask.error_message && (
                <Alert type="error" showIcon message={latestTask.error_message} />
              )}
            </Space>
          )}
        </Space>
      </Card>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card size="small">
            <Statistic title="实体数" value={summary?.entity_count ?? 0} loading={summaryLoading} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="关系数" value={summary?.relation_count ?? 0} loading={summaryLoading} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="覆盖分片数" value={summary?.covered_chunk_count ?? 0} loading={summaryLoading} />
          </Card>
        </Col>
      </Row>

      <Card size="small" title={`实体关系图（前 ${VISUALIZATION_ENTITY_LIMIT} 个实体）`} style={{ marginBottom: 16 }} loading={vizLoading}>
        <GraphVisualization entities={vizEntities} onEntityClick={setDrillEntityName} />
      </Card>

      <Card size="small" title="实体列表">
        <Input.Search
          placeholder="按实体名称搜索"
          allowClear
          onSearch={handleSearch}
          style={{ marginBottom: 12, maxWidth: 320 }}
          prefix={<SearchOutlined />}
        />
        {entities.length === 0 && !entitiesLoading ? (
          <Empty description="暂无实体，请先开启知识图谱并抽取" />
        ) : (
          <Table<GraphEntity>
            rowKey="name"
            size="small"
            loading={entitiesLoading}
            dataSource={entities}
            pagination={{
              current: page,
              pageSize: ENTITY_LIST_PAGE_SIZE,
              total: entityTotal,
              onChange: setPage,
              showSizeChanger: false,
            }}
            columns={[
              { title: '实体名称', dataIndex: 'name' },
              {
                title: '类型',
                dataIndex: 'type',
                width: 140,
                // Freeform LLM-extracted label, not a closed enum -- plain Tag, no metaOf lookup.
                render: (type: string) => (type ? <Tag>{type}</Tag> : <Tag color="default">未分类</Tag>),
              },
              { title: '来源分片数', dataIndex: 'source_chunk_count', width: 120 },
              {
                title: '操作',
                width: 140,
                render: (_, record: GraphEntity) => (
                  <Button size="small" onClick={() => setDrillEntityName(record.name)}>
                    查看来源分片
                  </Button>
                ),
              },
            ]}
          />
        )}
      </Card>

      <GraphEntityChunksDrawer kbId={kbId} entityName={drillEntityName} onClose={() => setDrillEntityName(null)} />
    </div>
  );
}
