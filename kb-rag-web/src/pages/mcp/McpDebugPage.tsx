// Author: owlzhangfq@gmail.com
import { useMemo, useState } from 'react';
import { ApiOutlined, SendOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Descriptions, Input, Radio, Row, Select, Space, Tag, Typography, message } from 'antd';
import {
  MCP_PATHS,
  MCP_PROTOCOL_VERSIONS,
  buildMcpHeaders,
  buildMcpRequest,
  mcpCallTool,
  mcpDiscover,
  mcpInitialize,
  mcpListTools,
  type McpEndpoint,
  type McpProtocolEra,
  type McpRpcOutcome,
  type McpToolInfo,
} from '../../api/mcp';
import PageHeader from '../../components/PageHeader';

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

function buildCurl(
  endpoint: McpEndpoint,
  apiKey: string,
  era: McpProtocolEra,
  body: string,
  method: string,
  params: Record<string, unknown>,
): string {
  const transportHeaders = buildMcpHeaders(era, method, params);
  return [
    `curl -X POST '${window.location.origin}${MCP_PATHS[endpoint]}' \\`,
    `  -H 'Authorization: Bearer ${apiKey || ENDPOINT_META[endpoint].keyPrefix + 'xxxxxxxxxxxxxxxx'}' \\`,
    ...Object.entries(transportHeaders).map(([name, value]) => `  -H '${name}: ${value}' \\`),
    `  -d ${quoteForShell(body.replace(/\n\s*/g, ' '))}`,
  ].join('\n');
}

/** POSIX shell 单引号字面量，保证 query 中含撇号时生成的 curl 仍可直接执行。 */
function quoteForShell(value: string): string {
  return `'${value.replaceAll("'", "'\"'\"'")}'`;
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
 * MCP 双协议调试页：现代版发 server/discover 与逐请求元数据，旧版发 initialize；两者共用
 * tools/list / tools/call，并原样展示 HTTP、JSON-RPC 与工具业务结果平面。
 */
export default function McpDebugPage() {
  const [endpoint, setEndpoint] = useState<McpEndpoint>('knowledge');
  const [protocolEra, setProtocolEra] = useState<McpProtocolEra>('modern');
  const [apiKey, setApiKey] = useState('');
  const [tools, setTools] = useState<McpToolInfo[]>([]);
  const [selectedTool, setSelectedTool] = useState<string | undefined>(undefined);
  const [argsText, setArgsText] = useState('{}');
  const [outcome, setOutcome] = useState<McpRpcOutcome | null>(null);
  const [busy, setBusy] = useState(false);

  const meta = ENDPOINT_META[endpoint];
  const activeTool = tools.find((tool) => tool.name === selectedTool);

  const callParams = useMemo(
    () => ({ name: selectedTool ?? 'tool_name', arguments: JSON.parse(safeArgs(argsText) ?? '{}') }),
    [selectedTool, argsText],
  );
  const callBody = useMemo(
    () => JSON.stringify(buildMcpRequest(protocolEra, 1, 'tools/call', callParams), null, 2),
    [protocolEra, callParams],
  );

  const switchEndpoint = (next: McpEndpoint) => {
    setEndpoint(next);
    setTools([]);
    setSelectedTool(undefined);
    setArgsText('{}');
    setOutcome(null);
  };

  const switchProtocol = (next: McpProtocolEra) => {
    setProtocolEra(next);
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

  const handleDiscovery = async () => {
    if (!requireKey()) return;
    setBusy(true);
    try {
      setOutcome(protocolEra === 'modern'
        ? await mcpDiscover(endpoint, apiKey.trim())
        : await mcpInitialize(endpoint, apiKey.trim()));
    } finally {
      setBusy(false);
    }
  };

  const handleListTools = async () => {
    if (!requireKey()) return;
    setBusy(true);
    try {
      const { outcome: listOutcome, tools: listed } = await mcpListTools(endpoint, apiKey.trim(), protocolEra);
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
      setOutcome(await mcpCallTool(endpoint, apiKey.trim(), protocolEra, selectedTool, JSON.parse(parsed)));
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
    <div className="knowledge-workbench-page mcp-workbench-page">
      <PageHeader
        eyebrow="PROTOCOL CONSOLE"
        title="MCP 调试"
        description="在同一控制台验证端点能力、工具清单、调用参数与 JSON-RPC 响应平面。"
      />
      <Space className="mcp-workbench-stack" direction="vertical" size="large">
        <Card className="workbench-config-card mcp-connection-card">
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
            <Radio.Group
              value={protocolEra}
              onChange={(event) => switchProtocol(event.target.value as McpProtocolEra)}
              options={[
                { label: '2026-07-28（逐请求元数据）', value: 'modern' },
                { label: '2025-03-26（initialize 兼容）', value: 'legacy' },
              ]}
              optionType="button"
              buttonStyle="solid"
            />
            <Input.Password
              className="mcp-key-input"
              value={apiKey}
              onChange={(event) => setApiKey(event.target.value)}
              placeholder={`${meta.keyPrefix}...（明文密钥仅在创建/轮换时展示一次，需自行留存）`}
              autoComplete="off"
            />
            <Space wrap>
              <Button icon={<ApiOutlined />} loading={busy} onClick={handleDiscovery}>
                {protocolEra === 'modern' ? 'server/discover 能力发现' : 'initialize 握手'}
              </Button>
              <Button icon={<UnorderedListOutlined />} loading={busy} onClick={handleListTools}>
                tools/list 列出工具
              </Button>
            </Space>
          </Space>
        </Card>

        <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card className="mcp-panel-card" title="tools/call 调试" size="small">
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
                className="technical-text"
              />
              <Button type="primary" icon={<SendOutlined />} loading={busy} onClick={handleCall}>
                发起 tools/call
              </Button>
              {activeTool && (
                <>
                  <Typography.Text type="secondary">inputSchema：</Typography.Text>
                  <pre className="mcp-code-block mcp-code-block--schema">
                    {JSON.stringify(activeTool.inputSchema, null, 2)}
                  </pre>
                </>
              )}
            </Space>
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card className="mcp-panel-card" title="响应" size="small">
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
                <pre className="mcp-code-block mcp-code-block--response">
                  {JSON.stringify(outcome.response, null, 2)}
                </pre>
              ) : (
                <Typography.Text type="secondary">尚未发起请求</Typography.Text>
              )}
            </Space>
          </Card>
        </Col>
        </Row>

        <Card className="mcp-panel-card mcp-examples-card" title="接入示例" size="small">
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Typography.Text type="secondary">
              curl（协议版本 {MCP_PROTOCOL_VERSIONS[protocolEra]}，Streamable HTTP，单次 JSON 响应）：
            </Typography.Text>
            <pre className="mcp-code-block">
              {buildCurl(endpoint, apiKey, protocolEra, callBody, 'tools/call', callParams)}
            </pre>
            <Typography.Text type="secondary">
              MCP 客户端配置（Claude Desktop / Cursor / Cline 等的 mcpServers）：
            </Typography.Text>
            <pre className="mcp-code-block">{buildClientConfig(endpoint, apiKey)}</pre>
          </Space>
        </Card>
      </Space>
    </div>
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
