// Author: owlzhangfq@gmail.com
import { useEffect, useState } from 'react';
import { SearchOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Row,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd';
import { listFragmentRules, memorySearchDebug } from '../../../api/memory';
import type { MemoryFragmentRule, MemorySearchDebugRequest, MemorySearchResult } from '../../../api/types';

interface Props {
  libraryId: string;
}

/**
 * Search debug tab: runs the exact open-API SearchMemory pipeline (intent recognition, rewrite,
 * vector recall, rerank) with tunable knobs, so operators can calibrate thresholds before
 * agents go live with a Memory Key.
 */
export default function SearchDebugTab({ libraryId }: Props) {
  const [rules, setRules] = useState<MemoryFragmentRule[]>([]);
  const [result, setResult] = useState<MemorySearchResult | null>(null);
  const [searching, setSearching] = useState(false);
  const [form] = Form.useForm<MemorySearchDebugRequest>();

  useEffect(() => {
    listFragmentRules(libraryId).then(setRules);
  }, [libraryId]);

  const handleSearch = async () => {
    const values = await form.validateFields();
    setSearching(true);
    try {
      setResult(await memorySearchDebug(libraryId, values));
    } finally {
      setSearching(false);
    }
  };

  return (
    <Row gutter={16}>
      <Col xs={24} md={8}>
        <Card title="检索参数" size="small">
          <Form<MemorySearchDebugRequest>
            form={form}
            layout="vertical"
            initialValues={{
              max_results: 5,
              intent_recognition: true,
              rewrite: true,
              rerank: true,
              similarity_threshold: 0.3,
            }}
          >
            <Form.Item name="user_id" label="用户 ID" rules={[{ required: true, message: '请输入用户 ID' }]}>
              <Input placeholder="记忆实体的 user_id" maxLength={64} />
            </Form.Item>
            <Form.Item name="query" label="查询内容" rules={[{ required: true, message: '请输入查询内容' }]}>
              <Input.TextArea rows={3} maxLength={1000} placeholder="例如：用户喜欢什么口味的咖啡？" />
            </Form.Item>
            <Form.Item name="fragment_rule_id" label="限定记忆片段规则">
              <Select
                allowClear
                placeholder="不限定（默认检索全部规则）"
                options={rules.map((rule) => ({ label: rule.name, value: rule.rule_id }))}
              />
            </Form.Item>
            <Form.Item name="max_results" label="最大返回条数（1-100）">
              <InputNumber min={1} max={100} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="intent_recognition" label="意图判别" valuePropName="checked" extra="判断查询是否需要召回记忆，无需时直接返回空">
              <Switch />
            </Form.Item>
            <Form.Item name="rewrite" label="查询改写" valuePropName="checked" extra="将口语化查询改写为更适合向量检索的表述">
              <Switch />
            </Form.Item>
            <Form.Item name="rerank" label="重排序" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="similarity_threshold" label="相似度阈值（0-1）">
              <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
            </Form.Item>
            <Button type="primary" icon={<SearchOutlined />} loading={searching} onClick={handleSearch} block>
              执行检索
            </Button>
          </Form>
        </Card>
      </Col>

      <Col xs={24} md={16}>
        <Card title="检索结果" size="small" loading={searching}>
          {!result ? (
            <Empty description="设置参数后点击「执行检索」，结果与开放 API SearchMemory 完全一致" />
          ) : (
            <Space direction="vertical" style={{ width: '100%' }} size="middle">
              <Space wrap>
                <Tag color={result.intent_recalled ? 'green' : 'orange'}>
                  {result.intent_recalled ? '意图判别：需要召回' : '意图判别：无需召回'}
                </Tag>
                {result.rewritten_query && (
                  <Typography.Text type="secondary">改写后查询：{result.rewritten_query}</Typography.Text>
                )}
              </Space>

              <List
                header={<Typography.Text strong>记忆节点（{result.memory_nodes.length}）</Typography.Text>}
                dataSource={result.memory_nodes}
                locale={{ emptyText: '未召回任何记忆节点' }}
                renderItem={(node) => (
                  <List.Item>
                    <List.Item.Meta
                      title={
                        <Space>
                          {node.score != null && <Tag color="blue">score {node.score.toFixed(4)}</Tag>}
                          <Tag>{node.source === 'EXTRACTED' ? '模型抽取' : '直接写入'}</Tag>
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {node.memory_node_id}
                          </Typography.Text>
                        </Space>
                      }
                      description={<Typography.Text>{node.content}</Typography.Text>}
                    />
                  </List.Item>
                )}
              />

              {result.profiles.length > 0 && (
                <div>
                  <Typography.Text strong>用户画像（{result.profiles.length}）</Typography.Text>
                  {result.profiles.map((profile) => (
                    <Descriptions key={profile.rule_id} bordered size="small" column={2} style={{ marginTop: 8 }} title={profile.rule_name}>
                      {profile.attributes.map((attr) => (
                        <Descriptions.Item key={attr.name} label={attr.name}>
                          {attr.value ?? <Typography.Text type="secondary">未抽取</Typography.Text>}
                        </Descriptions.Item>
                      ))}
                    </Descriptions>
                  ))}
                </div>
              )}
            </Space>
          )}
        </Card>
      </Col>
    </Row>
  );
}
