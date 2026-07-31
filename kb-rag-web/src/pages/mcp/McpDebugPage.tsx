// Author: owlzhangfq@gmail.com
import { useMemo, useState } from 'react';
import { ApiOutlined, SendOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Descriptions, Input, Radio, Row, Select, Space, Tag, Typography, message } from 'antd';
import {
  MCP_PATHS,
  MCP_PROTOCOL_VERSION,
  mcpCallTool,
  mcpInitialize,
  mcpListTools,
  type McpEndpoint,
  type McpRpcOutcome,
  type McpToolInfo,
} from '../../api/mcp';

/** Per-endpoint copy: which credential unlocks it and where that credential is issued. */
const ENDPOINT_META: Record<McpEndpoint, { label: string; keyPrefix: string; keyHint: string }> = {
  knowledge: {
    label: '知识库应用 MCP（kb-rag-knowledge）',
    keyPrefix: 'kb-sk-',
    keyHint: '使用应用中心签发的 API Key（kb-sk-*），与 REST 开放接口同一凭证、同一 app_scope 与限流。',
  },
  memory: {
    label: '记忆库 MCP（kb-rag-memory）',
    keyPrefix: 'kb-mk-',
    keyHint: '使用记忆库详情页签发的 Memory Key（kb-mk-*），工具只会操作该 Key 绑定的记忆库。',
  },
};

/** A placeholder value per JSON Schema type, so the prefilled arguments parse as-is. */
function placeholderOf(schema: Record<string, unknown>): unknown {
  switch (schema.type) {
    case 'integer':
    case 'number':
      return 0;
    case 'boolean':
      return false;
    case 'array':
      return [];
    case 'object':
      return {};
    default:
      return '';
  }
}

/** Prefills the required arguments of a tool from its inputSchema, one placeholder per field. */
function templateOf(tool: McpToolInfo): string {
  const properties = (tool.inputSchema.properties ?? {}) as Record<string, Record<string, unknown>>;
  const required = (tool.inputSchema.required ?? []) as string[];
  const args: Record<string, unknown> = {};
  required.forEach((name) => {
    args[name] = placeholderOf(properties[name] ?? {});
  });
  return JSON.stringify(args, null, 2);
}

function buildCurl(endpoint: McpEndpoint, apiKey: string, body: string): string {
  return [
    `curl -X POST '${window.location.origin}${MCP_PATHS[endpoint]}' \\`,
    `  -H 'Authorization: Bearer ${apiKey || ENDPOINT_META[endpoint].keyPrefix + 'xxxxxxxxxxxxxxxx'}' \\`,
    `  -H 'Content-Type: application/json' \\`,
    `  -d '${body.replace(/\n\s*/g, ' ')}'`,
  ].join('\n');
}

/** The mcpServers snippet an MCP-capable agent (Claude Desktop / Cursor / Cline...) pastes in. */
function buildClientConfig(endpoint: McpEndpoint, apiKey: string): string {
  return JSON.stringify(
    {
      mcpServers: {
        [`kb-rag-${endpoint}`]: {
          type: 'streamable-http',
          url: `${window.location.origin}${MCP_PATHS[endpoint]}`,
          headers: { Authorization: `Bearer ${apiKey || ENDPOINT_META[endpoint].keyPrefix + 'xxxxxxxxxxxxxxxx'}` },
        },
      },
    },
    null,
    2,
  );
}

/**
 * MCP 调试 page (M20-CONTRACTS.md): fires real initialize / tools\/list / tools\/call exchanges at
 * the two MCP endpoints with a pasted-in plaintext key, and shows both failure planes untouched --
 * a JSON-RPC `error` is a protocol violation, a result with `isError: true` is a business refusal.
 * The plaintext is only ever shown once at key creation/rotation, so it must be re-pasted here.
 */
export default function McpDebugPage() {
  const [endpoint, setEndpoint] = useState<McpEndpoint>('knowledge');
  const [apiKey, setApiKey] = useState('');
  const [tools, setTools] = useState<McpToolInfo[]>([]);
  const [selectedTool, setSelectedTool] = useState<string | undefined>(undefined);
  const [argsText, setArgsText] = useState('{}');
  const [outcome, setOutcome] = useState<McpRpcOutcome | null>(null);
  const [busy, setBusy] = useState(false);

  const meta = ENDPOINT_META[endpoint];
  const activeTool = tools.find((tool) => tool.name === selectedTool);

  const callBody = useMemo(
    () =>
      JSON.stringify(
        {
          jsonrpc: '2.0',
          id: 1,
          method: 'tools/call',
          params: { name: selectedTool ?? 'tool_name', arguments: JSON.parse(safeArgs(argsText) ?? '{}') },
        },
        null,
        2,
      ),
    [selectedTool, argsText],
  );

  const switchEndpoint = (next: McpEndpoint) => {
    setEndpoint(next);
    setTools([]);
    setSelectedTool(undefined);
    setArgsText('{}');
    setOutcome(null);
  };

  const requireKey = (): boolean => {
    if (!apiKey.trim()) {
      message.warning(`请先粘贴 ${meta.keyPrefix}* 明文密钥`);
      return false;
    }
    return true;
  };

  const handleInitialize = async () => {
    if (!requireKey()) return;
    setBusy(true);
    try {
      setOutcome(await mcpInitialize(endpoint, apiKey.trim()));
    } finally {
      setBusy(false);
    }
  };

  const handleListTools = async () => {
    if (!requireKey()) return;
    setBusy(true);
    try {
      const { outcome: listOutcome, tools: listed } = await mcpListTools(endpoint, apiKey.trim());
      setOutcome(listOutcome);
      setTools(listed);
      if (listed.length > 0) {
        setSelectedTool(listed[0].name);
        setArgsText(templateOf(listed[0]));
      }
    } finally {
      setBusy(false);
    }
  };

  const handleCall = async () => {
    if (!requireKey()) return;
    if (!selectedTool) {
      message.warning('请先执行 tools/list 并选择一个工具');
      return;
    }
    const parsed = safeArgs(argsText);
    if (parsed === null) {
      message.error('arguments 不是合法 JSON 对象');
      return;
    }
    setBusy(true);
    try {
      setOutcome(await mcpCallTool(endpoint, apiKey.trim(), selectedTool, JSON.parse(parsed)));
    } finally {
      setBusy(false);
    }
  };

  const handleToolSelected = (name: string) => {
    setSelectedTool(name);
    const tool = tools.find((candidate) => candidate.name === name);
    if (tool) {
      setArgsText(templateOf(tool));
    }
  };

  const result = outcome?.response?.result;
  const rpcError = outcome?.response?.error;
  const businessError = result?.isError === true;

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert
            type="info"
            showIcon
            message="MCP（Model Context Protocol）调试"
            description={`两个 MCP 端点与 REST 开放接口共用凭证、鉴权、限流与审计。${meta.keyHint}`}
          />
          <Radio.Group
            value={endpoint}
            onChange={(event) => switchEndpoint(event.target.value as McpEndpoint)}
            options={(Object.keys(ENDPOINT_META) as McpEndpoint[]).map((key) => ({
              label: ENDPOINT_META[key].label,
              value: key,
            }))}
            optionType="button"
            buttonStyle="solid"
          />
          <Input.Password
            value={apiKey}
            onChange={(event) => setApiKey(event.target.value)}
            placeholder={`${meta.keyPrefix}...（明文密钥仅在创建/轮换时展示一次，需自行留存）`}
            autoComplete="off"
            style={{ maxWidth: 480 }}
          />
          <Space wrap>
            <Button icon={<ApiOutlined />} loading={busy} onClick={handleInitialize}>
              initialize 握手
            </Button>
            <Button icon={<UnorderedListOutlined />} loading={busy} onClick={handleListTools}>
              tools/list 列出工具
            </Button>
          </Space>
        </Space>
      </Card>

      <Row gutter={16}>
        <Col xs={24} lg={12}>
          <Card title="tools/call 调试" size="small">
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <Select
                placeholder="先 tools/list 再选择工具"
                value={selectedTool}
                onChange={handleToolSelected}
                options={tools.map((tool) => ({ label: tool.name, value: tool.name }))}
                style={{ width: '100%' }}
              />
              {activeTool && (
                <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  {activeTool.description}
                </Typography.Paragraph>
              )}
              <Input.TextArea
                value={argsText}
                onChange={(event) => setArgsText(event.target.value)}
                rows={10}
                style={{ fontFamily: 'monospace' }}
              />
              <Button type="primary" icon={<SendOutlined />} loading={busy} onClick={handleCall}>
                发起 tools/call
              </Button>
              {activeTool && (
                <>
                  <Typography.Text type="secondary">inputSchema：</Typography.Text>
                  <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, overflowX: 'auto', maxHeight: 240 }}>
                    {JSON.stringify(activeTool.inputSchema, null, 2)}
                  </pre>
                </>
              )}
            </Space>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="响应" size="small">
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              {outcome && (
                <Descriptions size="small" bordered column={1}>
                  <Descriptions.Item label="HTTP 状态">{outcome.http_status}</Descriptions.Item>
                  <Descriptions.Item label="结果平面">
                    {rpcError ? (
                      <Tag color="red">JSON-RPC error（协议错误 {rpcError.code}）</Tag>
                    ) : businessError ? (
                      <Tag color="orange">isError: true（业务失败）</Tag>
                    ) : outcome.response ? (
                      <Tag color="green">成功</Tag>
                    ) : (
                      <Tag>无响应体</Tag>
                    )}
                  </Descriptions.Item>
                </Descriptions>
              )}
              {rpcError && <Alert type="error" showIcon message={`协议错误 ${rpcError.code}`} description={rpcError.message} />}
              {businessError && (
                <Alert
                  type="warning"
                  showIcon
                  message="业务失败（工具结果 isError: true）"
                  description={String((result?.content as { text?: string }[] | undefined)?.[0]?.text ?? '')}
                />
              )}
              {outcome ? (
                <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, overflowX: 'auto', maxHeight: 420 }}>
                  {JSON.stringify(outcome.response, null, 2)}
                </pre>
              ) : (
                <Typography.Text type="secondary">尚未发起请求</Typography.Text>
              )}
            </Space>
          </Card>
        </Col>
      </Row>

      <Card title="接入示例" size="small">
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Text type="secondary">curl（协议版本 {MCP_PROTOCOL_VERSION}，Streamable HTTP，单次 JSON 响应）：</Typography.Text>
          <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, overflowX: 'auto' }}>
            {buildCurl(endpoint, apiKey, callBody)}
          </pre>
          <Typography.Text type="secondary">MCP 客户端配置（Claude Desktop / Cursor / Cline 等的 mcpServers）：</Typography.Text>
          <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, overflowX: 'auto' }}>
            {buildClientConfig(endpoint, apiKey)}
          </pre>
        </Space>
      </Card>
    </Space>
  );
}

/** Returns a normalised JSON object string, or null when the text is not a JSON object. */
function safeArgs(text: string): string | null {
  try {
    const parsed = JSON.parse(text);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return null;
    }
    return JSON.stringify(parsed);
  } catch {
    return null;
  }
}
