# MCP 接入指南（让任何 Agent 直接调用知识库与记忆库）

> 面向对象：使用 MCP（Model Context Protocol）兼容客户端的接入方——Claude Desktop / Cursor / Cline / 各类自研 Agent 框架。
> 一句话：配一个 URL、带一把既有 Key，知识库检索问答与记忆库读写就成为你 Agent 的工具，零胶水代码。

## 1. 一分钟总览

1. kb-rag 暴露两个 MCP 服务端点，凭证与 REST 开放 API **完全同一把 Key**：

| MCP 服务 | 端点 | 凭证 | Key 从哪来 |
| --- | --- | --- | --- |
| `kb-rag-knowledge`（知识库应用） | `POST /api/v1/knowledge/mcp` | `Authorization: Bearer kb-sk-*` | 控制台「应用中心」→ API Key |
| `kb-rag-memory`（记忆库） | `POST /api/v1/memory/mcp` | `Authorization: Bearer kb-mk-*` | 控制台「记忆库」详情页 → Memory Key |

2. 传输形态：MCP **Streamable HTTP**——单个 JSON-RPC 2.0 请求，单个 JSON 响应（不开 SSE 流）。协议版本 `2025-03-26`，兼容 `2024-11-05`。
3. 鉴权、限流、审计与 REST 端点走同一条过滤器链：Key 被禁用/轮换即刻生效，超 QPS 返回 429。
4. 控制台「MCP 调试」页面可在线做 initialize 握手、查看工具目录、发起 tools/call，并自动生成 curl 与客户端配置片段。

## 2. 客户端配置

MCP 客户端（Claude Desktop / Cursor / Cline 等）的 `mcpServers` 配置：

```json
{
  "mcpServers": {
    "kb-rag-knowledge": {
      "type": "streamable-http",
      "url": "http(s)://<你的部署地址>/api/v1/knowledge/mcp",
      "headers": { "Authorization": "Bearer kb-sk-xxxxxxxxxxxxxxxx" }
    },
    "kb-rag-memory": {
      "type": "streamable-http",
      "url": "http(s)://<你的部署地址>/api/v1/memory/mcp",
      "headers": { "Authorization": "Bearer kb-mk-xxxxxxxxxxxxxxxx" }
    }
  }
}
```

两个服务相互独立，按需接一个或两个。明文 Key 仅在签发/轮换时展示一次，请自行留存。

## 3. 工具目录

### 3.1 kb-rag-knowledge（2 个工具）

| 工具 | 对应 REST 端点 | 说明 |
| --- | --- | --- |
| `knowledge_search` | `POST /api/v1/knowledge/search` | 纯检索：返回最相关的知识分片，不生成回答 |
| `knowledge_chat` | `POST /api/v1/knowledge/chat` | RAG 问答：检索 + 生成带引用的回答（仅非流式；要流式请直接调 REST 的 SSE） |

两工具参数一致（同 REST 请求体）：必填 `app_id`、`query`；可选 `app_version`（缺省当前正式版）、`messages`（多轮历史）、`top_n` / `score_threshold` / `max_content_length`（覆盖白名单）。

### 3.2 kb-rag-memory（6 个工具）

| 工具 | 对应 REST 端点 | 必填参数 |
| --- | --- | --- |
| `memory_add` | `POST /api/v1/memory/add` | `user_id`（`messages` 与 `custom_content` 至少一个） |
| `memory_search` | `POST /api/v1/memory/search` | `user_id`、`query` |
| `memory_list` | `GET /api/v1/memory/memory_nodes` | `user_id`（可选 `rule_id` / `page_num` / `page_size`） |
| `memory_update` | `PATCH /api/v1/memory/memory_nodes/{nodeId}` | `memory_node_id`、`user_id`、`custom_content` |
| `memory_delete` | `DELETE /api/v1/memory/memory_nodes/{nodeId}` | `memory_node_id`、`user_id` |
| `memory_get_profile` | `GET /api/v1/memory/profiles` | `user_id`（可选 `rule_id`） |

参数语义与《记忆库接入指南》完全一致；操作的记忆库由 Key 绑定关系决定，参数无需也无法指定库。各工具参数的权威定义以 `tools/list` 返回的 `inputSchema` 为准。

## 4. 协议交互（curl 示例）

以下以知识库端点为例，记忆库端点只需换 URL 与 Key。

### 4.1 initialize 握手

```bash
curl -X POST "$BASE/api/v1/knowledge/mcp" \
  -H "Authorization: Bearer $API_KEY" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"my-agent","version":"1.0.0"}}}'
```

返回 `result.protocolVersion`、`result.capabilities.tools` 与 `result.serverInfo`。随后客户端通常会发 `notifications/initialized` 通知（无 `id`），服务端回 **202 无响应体**。

### 4.2 tools/list 工具目录

```bash
curl -X POST "$BASE/api/v1/knowledge/mcp" \
  -H "Authorization: Bearer $API_KEY" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

### 4.3 tools/call 执行工具

```bash
curl -X POST "$BASE/api/v1/knowledge/mcp" \
  -H "Authorization: Bearer $API_KEY" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"knowledge_search","arguments":{"app_id":"app0001","query":"什么是混合检索？"}}}'
```

成功响应的 `result`：

```json
{
  "content": [{"type": "text", "text": "{...JSON 文本...}"}],
  "structuredContent": { "nodes": [ ... ], "request_id": "...", "degraded": [] },
  "isError": false
}
```

`structuredContent` 与 REST 响应的 `data` 结构一致；`content[0].text` 是同一份数据的 JSON 文本（给只读文本的客户端）。

## 5. 两个失败平面（务必区分）

| 平面 | 表现 | 含义与处理 |
| --- | --- | --- |
| 协议错误 | 响应带 `error`：`-32700` 解析错 / `-32600` 非法请求（批量数组、缺 id）/ `-32601` 方法不存在 / `-32602` 工具不存在或 arguments 非对象 | 客户端实现问题，修请求 |
| 业务失败 | `tools/call` 成功响应但 `result.isError: true`，`content[0].text` 形如 `INVALID_PARAM: app_id must not be blank` | 给 Agent 的工具反馈：修正参数后重试即可（记忆不存在、参数校验失败、越权 404 语义等都在这一平面） |

HTTP 状态恒为 200（通知 202）；非 200/202 只可能来自鉴权/限流层，错误信封与 REST 相同：

| HTTP | code | 含义 |
| --- | --- | --- |
| 401 | `INVALID_API_KEY` | Key 缺失/格式错/已轮换 |
| 401 | `API_KEY_DISABLED` | Key 已被禁用 |
| 429 | `RATE_LIMITED` | 超过该 Key 的 QPS 上限，按 `Retry-After` 退避 |

## 6. 边界与最佳实践

- **chat 不支持流式**：`tools/call` 是单次请求应答，`knowledge_chat` 传 `stream: true` 会得到 `isError: true` 的反馈；需要 token 级流式渲染时直接调 REST `POST /api/v1/knowledge/chat`（SSE）。
- **无会话状态**：服务端不签发 `Mcp-Session-Id`，每个请求自带完整上下文，可任意水平扩展与重试。
- **不支持批量**：JSON-RPC 批量数组按 2025-03-26 修订直接拒绝（-32600），一次一个请求。
- **典型 Agent 编排**：会话开始 `memory_search` 召回长期记忆拼进系统提示词 → 过程中按需 `knowledge_search` / `knowledge_chat` 查资料 → 会话结束 `memory_add` 写回本轮对话抽取新记忆。
- 调试请优先用控制台「MCP 调试」页面：三种结果平面（协议错/业务错/成功）有显式标注，且能一键生成与你表单等价的 curl。
