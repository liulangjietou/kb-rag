import { useEffect, useState } from 'react';
import { SearchOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Collapse,
  DatePicker,
  Empty,
  Form,
  Input,
  InputNumber,
  Radio,
  Select,
  Slider,
  Space,
  Spin,
  Switch,
  Typography,
} from 'antd';
import type { Dayjs } from 'dayjs';
import { search } from '../../api/search';
import { listKnowledgeBases } from '../../api/kb';
import type { FusionMode, KnowledgeBase, MetadataFilter, SearchRequest, SearchResponse } from '../../api/types';
import { useModelStatus } from '../../context/ModelStatusContext';
import { GRAPH_FUSION_MUTEX_HINT, describeDegradedReason, describeThresholdApplied } from '../../utils/statusMeta';
import AppliedInfoBar from './components/AppliedInfoBar';
import CollectToEvalModal from './components/CollectToEvalModal';
import RetrievalNodeCard from './components/RetrievalNodeCard';

const DEFAULT_RECALL_TOP_K = 50;
const DEFAULT_TOP_N = 5;
const DEFAULT_RRF_K = 60;
const DEFAULT_W_VEC = 0.5;
const DEFAULT_SCORE_THRESHOLD = 0.5;

interface SearchFormValues {
  kb_id: string;
  query: string;
  // 改写
  rewrite_enabled: boolean;
  // 召回
  recall_top_k: number;
  // 融合
  fusion_mode: FusionMode;
  w_vec: number;
  rrf_k: number;
  // 重排
  rerank_enabled: boolean;
  // 过滤
  threshold_enabled: boolean;
  score_threshold: number;
  tag_ids: string[];
  session_id?: string;
  sender?: string;
  msg_time_range?: [Dayjs, Dayjs];
  // 返回
  top_n: number;
}

/** Builds the metadata_filter sub-object, omitting it entirely when nothing was filled in. */
function buildMetadataFilter(values: SearchFormValues): MetadataFilter | undefined {
  const filter: MetadataFilter = {};
  if (values.tag_ids && values.tag_ids.length > 0) {
    filter.tag_ids = values.tag_ids;
  }
  if (values.session_id) {
    filter.session_id = values.session_id;
  }
  if (values.sender) {
    filter.sender = values.sender;
  }
  if (values.msg_time_range && values.msg_time_range.length === 2) {
    filter.msg_time_from = values.msg_time_range[0].valueOf();
    filter.msg_time_to = values.msg_time_range[1].valueOf();
  }
  return Object.keys(filter).length > 0 ? filter : undefined;
}

export default function SearchPage() {
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [result, setResult] = useState<SearchResponse | null>(null);
  const [searchedQuery, setSearchedQuery] = useState('');
  const [searchedKbId, setSearchedKbId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  // "收进评测集" selection state (M4b-CONTRACTS.md section 5), keyed by chunk_id; cleared on every new search.
  const [selectedChunkIds, setSelectedChunkIds] = useState<string[]>([]);
  const [collectModalOpen, setCollectModalOpen] = useState(false);
  const { modelStatus } = useModelStatus();
  const [form] = Form.useForm<SearchFormValues>();
  const fusionMode = Form.useWatch('fusion_mode', form) ?? 'rrf';
  const thresholdEnabled = Form.useWatch('threshold_enabled', form) ?? false;
  const selectedKbId = Form.useWatch('kb_id', form);

  const rewriteAvailable = modelStatus?.chat_configured ?? false;
  const rerankAvailable = modelStatus?.rerank_configured ?? false;
  // M7-CONTRACTS.md section 0.6/§4.4: the selected kb's graph_enabled forces fusion_mode=rrf.
  const selectedKbGraphEnabled = kbs.find((kb) => kb.kb_id === selectedKbId)?.graph_enabled ?? false;

  useEffect(() => {
    listKnowledgeBases().then(setKbs);
  }, []);

  // Selecting a graph-enabled kb while "加权归一化" is still picked would otherwise submit a
  // request the server rejects as INVALID_PARAM; snap back to RRF right away instead.
  useEffect(() => {
    if (selectedKbGraphEnabled && form.getFieldValue('fusion_mode') === 'weighted') {
      form.setFieldsValue({ fusion_mode: 'rrf' });
    }
  }, [selectedKbGraphEnabled, form]);

  // rerank_enabled defaults to true only once a rerank model is confirmed configured
  // (M2-CONTRACTS.md section 1.5); model-status resolves asynchronously after mount, so sync
  // the switch once it lands rather than relying on the form's static initialValues.
  useEffect(() => {
    if (modelStatus) {
      form.setFieldsValue({ rerank_enabled: modelStatus.rerank_configured });
    }
  }, [modelStatus, form]);

  const handleFinish = async (values: SearchFormValues) => {
    setLoading(true);
    try {
      const payload: SearchRequest = {
        query: values.query,
        recall_top_k: values.recall_top_k,
        top_n: values.top_n,
        rewrite_enabled: rewriteAvailable ? values.rewrite_enabled : false,
        rerank_enabled: rerankAvailable ? values.rerank_enabled : false,
        score_threshold: values.threshold_enabled ? values.score_threshold : null,
        fusion: {
          mode: values.fusion_mode,
          w_vec: values.fusion_mode === 'weighted' ? values.w_vec : undefined,
          rrf_k: values.fusion_mode === 'rrf' ? values.rrf_k : undefined,
        },
        metadata_filter: buildMetadataFilter(values),
      };
      const res = await search(values.kb_id, payload);
      setResult(res);
      setSearchedQuery(values.query);
      setSearchedKbId(values.kb_id);
      setSelectedChunkIds([]);
      setHasSearched(true);
    } finally {
      setLoading(false);
    }
  };

  const toggleChunkSelected = (chunkId: string, checked: boolean) => {
    setSelectedChunkIds((prev) => (checked ? [...prev, chunkId] : prev.filter((id) => id !== chunkId)));
  };

  const selectedNodes = result?.nodes.filter((node) => selectedChunkIds.includes(node.chunk_id)) ?? [];

  const thresholdTag = result ? describeThresholdApplied(result.applied.threshold_applied_on, result.degraded) : null;

  return (
    <div>
      <Card style={{ marginBottom: 16 }}>
        <Form<SearchFormValues>
          form={form}
          layout="vertical"
          onFinish={handleFinish}
          initialValues={{
            recall_top_k: DEFAULT_RECALL_TOP_K,
            top_n: DEFAULT_TOP_N,
            rewrite_enabled: false,
            fusion_mode: 'rrf',
            w_vec: DEFAULT_W_VEC,
            rrf_k: DEFAULT_RRF_K,
            rerank_enabled: true,
            threshold_enabled: false,
            score_threshold: DEFAULT_SCORE_THRESHOLD,
          }}
        >
          <Form.Item name="kb_id" label="知识库" rules={[{ required: true, message: '请选择知识库' }]}>
            <Select
              placeholder="请选择要检索的知识库"
              options={kbs.map((kb) => ({ label: kb.name, value: kb.kb_id }))}
            />
          </Form.Item>
          <Form.Item name="query" label="检索内容" rules={[{ required: true, message: '请输入检索内容' }]}>
            <Input.TextArea placeholder="输入需要检索的问题或关键词" rows={3} />
          </Form.Item>

          <Collapse
            style={{ marginBottom: 16 }}
            defaultActiveKey={['recall', 'fusion', 'rerank', 'filter', 'return']}
            items={[
              {
                key: 'rewrite',
                label: '改写',
                children: (
                  <Space direction="vertical">
                    <Form.Item
                      name="rewrite_enabled"
                      label="启用查询改写"
                      valuePropName="checked"
                      tooltip="超时 800ms 或未配置对话模型时自动降级为原始 query"
                      style={{ marginBottom: rewriteAvailable ? 0 : 8 }}
                    >
                      <Switch disabled={!rewriteAvailable} />
                    </Form.Item>
                    {!rewriteAvailable && (
                      <Typography.Text type="secondary">未配置对话模型（chat），查询改写不可用</Typography.Text>
                    )}
                  </Space>
                ),
              },
              {
                key: 'recall',
                label: '召回',
                children: (
                  <Form.Item name="recall_top_k" label="recall_top_k（召回数量）" style={{ marginBottom: 0 }}>
                    <InputNumber min={1} max={200} style={{ width: '100%' }} />
                  </Form.Item>
                ),
              },
              {
                key: 'fusion',
                label: '融合',
                children: (
                  <>
                    <Form.Item name="fusion_mode" label="融合模式">
                      <Radio.Group
                        optionType="button"
                        options={[
                          { label: 'RRF', value: 'rrf' },
                          { label: '加权归一化', value: 'weighted', disabled: selectedKbGraphEnabled },
                        ]}
                      />
                    </Form.Item>
                    {selectedKbGraphEnabled && (
                      <Typography.Text type="secondary">{GRAPH_FUSION_MUTEX_HINT}</Typography.Text>
                    )}
                    {fusionMode === 'weighted' && (
                      <Form.Item
                        name="w_vec"
                        label="向量路权重 w_vec（BM25 权重 = 1 - w_vec）"
                        tooltip="每路候选集内 min-max 归一化后按权重加权求和"
                      >
                        <Slider min={0} max={1} step={0.01} marks={{ 0: '0', 0.5: '0.5', 1: '1' }} />
                      </Form.Item>
                    )}
                    {fusionMode === 'rrf' && (
                      <Form.Item name="rrf_k" label="rrf_k" style={{ marginBottom: 0 }}>
                        <InputNumber min={1} max={200} style={{ width: '100%' }} />
                      </Form.Item>
                    )}
                  </>
                ),
              },
              {
                key: 'rerank',
                label: '重排',
                children: (
                  <Space direction="vertical">
                    <Form.Item
                      name="rerank_enabled"
                      label="启用重排序"
                      valuePropName="checked"
                      tooltip="候选上限 50；超时 1.5s 或失败降级为融合结果排序"
                      style={{ marginBottom: rerankAvailable ? 0 : 8 }}
                    >
                      <Switch disabled={!rerankAvailable} />
                    </Form.Item>
                    {!rerankAvailable && (
                      <Typography.Text type="secondary">未配置重排模型（rerank），重排序不可用</Typography.Text>
                    )}
                  </Space>
                ),
              },
              {
                key: 'filter',
                label: '过滤',
                children: (
                  <>
                    <Form.Item name="threshold_enabled" label="启用阈值过滤" valuePropName="checked">
                      <Switch />
                    </Form.Item>
                    {thresholdEnabled && (
                      <Form.Item
                        name="score_threshold"
                        label="score_threshold（0.01-1.0）"
                        tooltip="rerank 开启时作用于 rerank 分；关闭/降级时作用于向量 cosine 分；BM25 单路时不生效"
                      >
                        <Slider min={0.01} max={1} step={0.01} />
                      </Form.Item>
                    )}
                    <Typography.Title level={5} style={{ marginTop: 8 }}>
                      metadata_filter
                    </Typography.Title>
                    <Form.Item
                      name="tag_ids"
                      label="标签（tag_ids）"
                      tooltip="按文档标签过滤；暂无标签字典接口，可直接输入标签值后回车"
                    >
                      <Select mode="tags" placeholder="输入标签后回车，可多选" tokenSeparators={[',']} />
                    </Form.Item>
                    <Space size="large" wrap style={{ width: '100%' }}>
                      <Form.Item name="session_id" label="会话 ID">
                        <Input placeholder="按会话精确匹配" style={{ width: 220 }} />
                      </Form.Item>
                      <Form.Item name="sender" label="发送人">
                        <Input placeholder="按发送人精确匹配" style={{ width: 220 }} />
                      </Form.Item>
                    </Space>
                    <Form.Item name="msg_time_range" label="时间范围（msg_time）" style={{ marginBottom: 0 }}>
                      <DatePicker.RangePicker showTime style={{ width: '100%' }} />
                    </Form.Item>
                  </>
                ),
              },
              {
                key: 'return',
                label: '返回',
                children: (
                  <Form.Item name="top_n" label="top_n（返回结果数）" style={{ marginBottom: 0 }}>
                    <InputNumber min={1} max={50} style={{ width: '100%' }} />
                  </Form.Item>
                ),
              },
            ]}
          />

          {modelStatus && !modelStatus.embedding_configured && (
            <Alert
              type="info"
              showIcon
              message="零 Key 模式下仅 BM25 召回生效，recall_top_k/top_n 仍作用于该单路检索，score_threshold 不生效"
              style={{ marginBottom: 16 }}
            />
          )}
          <Form.Item>
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={loading}>
              开始检索
            </Button>
          </Form.Item>
        </Form>
      </Card>

      {result && result.degraded.length > 0 && (
        <Alert
          type="warning"
          showIcon
          message="检索已降级"
          description={result.degraded.map(describeDegradedReason).join('；')}
          style={{ marginBottom: 16 }}
        />
      )}

      {result && <AppliedInfoBar applied={result.applied} degraded={result.degraded} originalQuery={searchedQuery} />}

      {result && result.nodes.length > 0 && (
        <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }}>
          <Typography.Text type="secondary">已选 {selectedChunkIds.length} 项</Typography.Text>
          <Button disabled={selectedChunkIds.length === 0} onClick={() => setCollectModalOpen(true)}>
            收进评测集
          </Button>
        </Space>
      )}

      <Spin spinning={loading}>
        {hasSearched && result && result.nodes.length === 0 && <Empty description="未检索到相关结果" />}
        {result?.nodes.map((node, index) => (
          <RetrievalNodeCard
            key={node.chunk_id}
            node={node}
            rank={index + 1}
            thresholdTag={thresholdTag}
            selected={selectedChunkIds.includes(node.chunk_id)}
            onSelectChange={(checked) => toggleChunkSelected(node.chunk_id, checked)}
          />
        ))}
        {!hasSearched && <Empty description="请选择知识库并输入检索内容开始调试" />}
      </Spin>

      {searchedKbId && (
        <CollectToEvalModal
          open={collectModalOpen}
          kbId={searchedKbId}
          query={searchedQuery}
          selectedNodes={selectedNodes}
          onClose={() => setCollectModalOpen(false)}
          onCollected={() => {
            setCollectModalOpen(false);
            setSelectedChunkIds([]);
          }}
        />
      )}
    </div>
  );
}
