import { useEffect, useState } from 'react';
import { SearchOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Form, Input, InputNumber, Select, Space, Spin } from 'antd';
import { search } from '../../api/search';
import { listKnowledgeBases } from '../../api/kb';
import type { KnowledgeBase, SearchRequest, SearchResponse } from '../../api/types';
import { useModelStatus } from '../../context/ModelStatusContext';
import { describeDegradedReason } from '../../utils/statusMeta';
import RetrievalNodeCard from './components/RetrievalNodeCard';

const DEFAULT_RECALL_TOP_K = 50;
const DEFAULT_TOP_N = 5;

interface SearchFormValues {
  kb_id: string;
  query: string;
  recall_top_k: number;
  top_n: number;
}

export default function SearchPage() {
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [result, setResult] = useState<SearchResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const { modelStatus } = useModelStatus();
  const [form] = Form.useForm<SearchFormValues>();

  useEffect(() => {
    listKnowledgeBases().then(setKbs);
  }, []);

  const handleFinish = async (values: SearchFormValues) => {
    setLoading(true);
    try {
      const payload: SearchRequest = {
        query: values.query,
        recall_top_k: values.recall_top_k,
        top_n: values.top_n,
      };
      const res = await search(values.kb_id, payload);
      setResult(res);
      setHasSearched(true);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <Card style={{ marginBottom: 16 }}>
        <Form<SearchFormValues>
          form={form}
          layout="vertical"
          onFinish={handleFinish}
          initialValues={{ recall_top_k: DEFAULT_RECALL_TOP_K, top_n: DEFAULT_TOP_N }}
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
          <Space size="large">
            <Form.Item name="recall_top_k" label="recall_top_k（召回数量）">
              <InputNumber min={1} max={200} />
            </Form.Item>
            <Form.Item name="top_n" label="top_n（返回结果数）">
              <InputNumber min={1} max={50} />
            </Form.Item>
          </Space>
          {modelStatus && !modelStatus.embedding_configured && (
            <Alert
              type="info"
              showIcon
              message="零 Key 模式下仅 BM25 召回生效，recall_top_k/top_n 仍作用于该单路检索"
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

      <Spin spinning={loading}>
        {hasSearched && result && result.nodes.length === 0 && <Empty description="未检索到相关结果" />}
        {result?.nodes.map((node, index) => (
          <RetrievalNodeCard key={node.chunk_id} node={node} rank={index + 1} />
        ))}
        {!hasSearched && <Empty description="请选择知识库并输入检索内容开始调试" />}
      </Spin>
    </div>
  );
}
