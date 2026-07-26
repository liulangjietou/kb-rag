// Author: owlzhangfq@gmail.com
import { useEffect, useMemo, useState } from 'react';
import { SendOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Select, Space, Switch, Tabs, Tag, Typography, message } from 'antd';
import { listApiKeys } from '../../../api/apiKey';
import { chatViaApiKey, searchViaApiKey, streamChatViaApiKey, type PublicApiResult } from '../../../api/publicApi';
import type { ApiKey, ChatResponse, RetrievalNode, SearchResponse } from '../../../api/types';
import { describeDegradedReason } from '../../../utils/statusMeta';

interface ApiDebugTabProps {
  appId: string;
}

interface DebugFormValues {
  key_id: string;
  api_key: string;
  app_version?: string;
  query: string;
  top_n?: number;
  threshold_enabled: boolean;
  score_threshold?: number;
  max_content_length?: number;
  stream: boolean;
}

function buildCurl(endpoint: 'search' | 'chat', apiKey: string, body: Record<string, unknown>): string {
  const path = endpoint === 'search' ? '/api/v1/knowledge/search' : '/api/v1/knowledge/chat';
  const payload = JSON.stringify(body, null, 2);
  return [
    `curl -X POST '${window.location.origin}${path}' \\`,
    `  -H 'Authorization: Bearer ${apiKey || 'kb-sk-xxxxxxxxxxxxxxxx'}' \\`,
    `  -H 'Content-Type: application/json' \\`,
    `  -d '${payload}'`,
  ].join('\n');
}

/**
 * API 调试 tab (M4c-CONTRACTS.md section 4): pick an existing API Key row for identification, pick
 * the actual secret (the plaintext is only ever shown once at creation/rotation -- see
 * ApiKeyWithSecret's doc comment -- so it must be re-pasted here rather than looked up), and fire
 * a real request against the external, API-Key-gated /api/v1/knowledge/search and /chat endpoints.
 * Chat additionally supports SSE streaming render. Both modes show an equivalent curl example.
 */
export default function ApiDebugTab({ appId }: ApiDebugTabProps) {
  const [form] = Form.useForm<DebugFormValues>();
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const thresholdEnabled = Form.useWatch('threshold_enabled', form) ?? false;
  const streamEnabled = Form.useWatch('stream', form) ?? false;
  const apiKeyValue = Form.useWatch('api_key', form) ?? '';
  const queryValue = Form.useWatch('query', form) ?? '';
  const appVersionValue = Form.useWatch('app_version', form);
  const topNValue = Form.useWatch('top_n', form);
  const scoreThresholdValue = Form.useWatch('score_threshold', form);
  const maxContentLengthValue = Form.useWatch('max_content_length', form);

  const [searching, setSearching] = useState(false);
  const [searchResult, setSearchResult] = useState<PublicApiResult<SearchResponse> | null>(null);

  const [chatting, setChatting] = useState(false);
  const [chatAnswer, setChatAnswer] = useState('');
  const [chatReferences, setChatReferences] = useState<RetrievalNode[]>([]);
  const [chatDegraded, setChatDegraded] = useState<string[]>([]);
  const [chatRequestId, setChatRequestId] = useState<string | null>(null);
  const [chatError, setChatError] = useState<{ code: string; message: string } | null>(null);

  useEffect(() => {
    listApiKeys().then(setKeys);
  }, []);

  const commonBody = useMemo(
    () => ({
      query: queryValue,
      app_id: appId,
      app_version: appVersionValue || undefined,
      top_n: topNValue,
      score_threshold: thresholdEnabled ? scoreThresholdValue : undefined,
      max_content_length: maxContentLengthValue,
    }),
    [queryValue, appId, appVersionValue, topNValue, thresholdEnabled, scoreThresholdValue, maxContentLengthValue],
  );

  const buildPayload = (values: DebugFormValues) => ({
    query: values.query,
    app_id: appId,
    app_version: values.app_version || undefined,
    top_n: values.top_n,
    score_threshold: values.threshold_enabled ? values.score_threshold ?? null : undefined,
    max_content_length: values.max_content_length,
  });

  const handleSearch = async () => {
    const values = await form.validateFields(['api_key', 'query', 'app_version', 'top_n', 'score_threshold', 'max_content_length']);
    setSearching(true);
    setSearchResult(null);
    try {
      const result = await searchViaApiKey(values.api_key, buildPayload(values));
      setSearchResult(result);
    } finally {
      setSearching(false);
    }
  };

  const handleChat = async () => {
    const values = await form.validateFields(['api_key', 'query', 'app_version', 'stream']);
    setChatting(true);
    setChatAnswer('');
    setChatReferences([]);
    setChatDegraded([]);
    setChatRequestId(null);
    setChatError(null);
    try {
      if (values.stream) {
        await streamChatViaApiKey(values.api_key, buildPayload(values), {
          onDelta: (delta) => setChatAnswer((prev) => prev + delta),
          onReferences: (references) => setChatReferences(references),
          onDone: (requestId, degraded) => {
            setChatRequestId(requestId);
            setChatDegraded(degraded);
          },
          onError: (error) => setChatError(error),
        });
      } else {
        const result = await chatViaApiKey(values.api_key, buildPayload(values));
        if (result.ok) {
          const data: ChatResponse = result.data;
          setChatAnswer(data.answer);
          setChatReferences(data.references);
          setChatDegraded(data.degraded);
          setChatRequestId(data.request_id);
        } else {
          setChatError(result.error);
        }
      }
    } finally {
      setChatting(false);
    }
  };

  const handleKeySelected = (keyId: string) => {
    const key = keys.find((k) => k.key_id === keyId);
    if (key && (key.app_scope === null || key.app_scope.includes(appId))) {
      // scope OK, no warning needed
    } else if (key) {
      message.warning('该 Key 的 app_scope 未包含当前应用，请求预计会返回 403 APP_ACCESS_DENIED');
    }
  };

  return (
    <Card>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="选择一个已创建的 API Key 行用于标识，并粘贴其明文密钥用于发起真实请求（密钥仅在创建/轮换时展示一次，需自行留存）"
      />
      <Form<DebugFormValues>
        form={form}
        layout="vertical"
        initialValues={{ threshold_enabled: false, stream: true }}
      >
        <Space size="large" wrap style={{ width: '100%' }}>
          <Form.Item name="key_id" label="选择 API Key（用于标识/校验 app_scope）" style={{ minWidth: 280 }}>
            <Select
              placeholder="请选择 API Key"
              options={keys.map((k) => ({ label: `${k.name}（${k.prefix}****${k.last4}）`, value: k.key_id }))}
              onChange={handleKeySelected}
            />
          </Form.Item>
          <Form.Item name="api_key" label="API Key 明文" rules={[{ required: true, message: '请输入 API Key 明文' }]} style={{ minWidth: 320 }}>
            <Input.Password placeholder="kb-sk-..." autoComplete="off" />
          </Form.Item>
          <Form.Item name="app_version" label="app_version（选填，缺省当前正式版）" style={{ minWidth: 200 }}>
            <Input placeholder="例如：V2.0，留空走当前 RELEASED" />
          </Form.Item>
        </Space>

        <Form.Item name="query" label="查询内容" rules={[{ required: true, message: '请输入查询内容' }]}>
          <Input.TextArea rows={2} placeholder="输入需要检索/提问的内容" />
        </Form.Item>

        <Space size="large" wrap>
          <Form.Item name="top_n" label="top_n（覆盖白名单）" style={{ marginBottom: 8 }}>
            <InputNumber min={1} max={50} style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="threshold_enabled" label="覆盖 score_threshold" valuePropName="checked" style={{ marginBottom: 8 }}>
            <Switch />
          </Form.Item>
          {thresholdEnabled && (
            <Form.Item name="score_threshold" label="score_threshold" style={{ marginBottom: 8 }}>
              <InputNumber min={0.01} max={1} step={0.01} style={{ width: 160 }} />
            </Form.Item>
          )}
          <Form.Item name="max_content_length" label="max_content_length（覆盖白名单）" style={{ marginBottom: 8 }}>
            <InputNumber min={1} style={{ width: 200 }} />
          </Form.Item>
        </Space>

        <Tabs
          items={[
            {
              key: 'search',
              label: 'search 调试',
              children: (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Button type="primary" icon={<SendOutlined />} loading={searching} onClick={handleSearch}>
                    发起 search 请求
                  </Button>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                    curl 示例：
                  </Typography.Paragraph>
                  <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, overflowX: 'auto' }}>
                    {buildCurl('search', apiKeyValue, commonBody)}
                  </pre>
                  {searchResult && !searchResult.ok && (
                    <Alert
                      type="error"
                      showIcon
                      message={`请求失败：${searchResult.error.code}`}
                      description={
                        <Space direction="vertical">
                          <Typography.Text>{searchResult.error.message}</Typography.Text>
                          {searchResult.error.retry_after && (
                            <Typography.Text>Retry-After: {searchResult.error.retry_after}s</Typography.Text>
                          )}
                        </Space>
                      }
                    />
                  )}
                  {searchResult?.ok && (
                    <>
                      {searchResult.data.degraded.length > 0 && (
                        <Alert
                          type="warning"
                          showIcon
                          message="检索已降级"
                          description={searchResult.data.degraded.map(describeDegradedReason).join('；')}
                        />
                      )}
                      <Descriptions size="small" bordered column={1}>
                        <Descriptions.Item label="request_id">{searchResult.data.request_id}</Descriptions.Item>
                        <Descriptions.Item label="命中数">{searchResult.data.nodes.length}</Descriptions.Item>
                      </Descriptions>
                      {searchResult.data.nodes.map((node, index) => (
                        <Card key={node.chunk_id} size="small" title={`#${index + 1} ${node.chunk_id}（${node.score_type}: ${node.score.toFixed(4)}）`}>
                          <Typography.Paragraph ellipsis={{ rows: 4, expandable: true, symbol: '展开' }} style={{ marginBottom: 0 }}>
                            {node.content}
                          </Typography.Paragraph>
                        </Card>
                      ))}
                    </>
                  )}
                </Space>
              ),
            },
            {
              key: 'chat',
              label: 'chat 调试',
              children: (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Form.Item name="stream" label="使用 SSE 流式返回" valuePropName="checked" style={{ marginBottom: 0 }}>
                    <Switch />
                  </Form.Item>
                  <Button type="primary" icon={<SendOutlined />} loading={chatting} onClick={handleChat}>
                    发起 chat 请求
                  </Button>
                  <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                    curl 示例：
                  </Typography.Paragraph>
                  <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, overflowX: 'auto' }}>
                    {buildCurl('chat', apiKeyValue, { ...commonBody, stream: streamEnabled })}
                  </pre>
                  {chatError && (
                    <Alert type="error" showIcon message={`请求失败：${chatError.code}`} description={chatError.message} />
                  )}
                  {(chatAnswer || chatting) && (
                    <Card size="small" title="回答">
                      <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                        {chatAnswer || (chatting ? '生成中...' : '')}
                      </Typography.Paragraph>
                    </Card>
                  )}
                  {chatDegraded.length > 0 && (
                    <Alert type="warning" showIcon message="已降级" description={chatDegraded.map(describeDegradedReason).join('；')} />
                  )}
                  {chatRequestId && <Typography.Text type="secondary">request_id: {chatRequestId}</Typography.Text>}
                  {chatReferences.length > 0 && (
                    <>
                      <Typography.Title level={5}>引用来源</Typography.Title>
                      <Space direction="vertical" style={{ width: '100%' }}>
                        {chatReferences.map((ref) => (
                          <Card key={ref.chunk_id} size="small">
                            <Space direction="vertical" size={4} style={{ width: '100%' }}>
                              <Tag>{ref.doc_id}</Tag>
                              <Typography.Paragraph ellipsis={{ rows: 3, expandable: true, symbol: '展开' }} style={{ marginBottom: 0 }}>
                                {ref.content}
                              </Typography.Paragraph>
                            </Space>
                          </Card>
                        ))}
                      </Space>
                    </>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Form>
    </Card>
  );
}
