# MCP 接入指南（2026-07-28 / 旧版双协议）

> 适用于 Claude Desktop、Cursor、Cline 与自研 Agent。只需一个 URL 和一把既有 Key，
> 即可把知识检索、RAG 问答和长期记忆读写作为 MCP 工具调用。

## 1. 端点与凭证

| MCP 服务 | Streamable HTTP 端点 | 凭证来源 |
| --- | --- | --- |
| `kb-rag-knowledge` | `POST /api/v1/knowledge/mcp` | 应用中心签发的 `Bearer kb-sk-*` |
| `kb-rag-memory` | `POST /api/v1/memory/mcp` | 记忆库详情签发的 `Bearer kb-mk-*` |

MCP 与 REST 复用同一身份、授权范围、限流和审计。Key 禁用或轮换立即生效；记忆库由
Memory Key 绑定关系确定，工具参数不能另行指定库。

服务端同时支持：

- `2026-07-28`：推荐。无 initialize、无 session，每个请求自带协议元数据；
- `2025-03-26` / `2024-11-05`：兼容既有客户端，继续使用 initialize 握手。

## 2. 标准客户端配置

```json
{
  "mcpServers": {
    "kb-rag-knowledge": {
      "type": "streamable-http",
      "url": "https://<部署地址>/api/v1/knowledge/mcp",
      "headers": { "Authorization": "Bearer kb-sk-xxxxxxxxxxxxxxxx" }
    },
    "kb-rag-memory": {
      "type": "streamable-http",
      "url": "https://<部署地址>/api/v1/memory/mcp",
      "headers": { "Authorization": "Bearer kb-mk-xxxxxxxxxxxxxxxx" }
    }
  }
}
```

配置里只放稳定的 Authorization。`MCP-Protocol-Version`、`Mcp-Method`、`Mcp-Name` 会随
每次请求变化，应由支持 2026-07-28 的客户端动态生成，不能写死在静态配置中。

## 3. 2026-07-28 推荐流程

现代请求必须同时满足：

1. `Accept: application/json, text/event-stream`；
2. `MCP-Protocol-Version: 2026-07-28` 与 body `_meta` 中版本相同；
3. `Mcp-Method` 与 JSON-RPC `method` 相同；
4. `tools/call` 还需 `Mcp-Name` 与 `params.name` 相同；
5. `_meta.io.modelcontextprotocol/clientCapabilities` 必须是对象，`clientInfo` 建议填写。

以下示例预先设置：

```bash
BASE=https://<部署地址>
API_KEY=kb-sk-xxxxxxxxxxxxxxxx
```

### 3.1 能力发现

```bash
curl -X POST "$BASE/api/v1/knowledge/mcp" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: server/discover" \
  -d '{
    "jsonrpc":"2.0","id":1,"method":"server/discover","params":{
      "_meta":{
        "io.modelcontextprotocol/protocolVersion":"2026-07-28",
        "io.modelcontextprotocol/clientCapabilities":{},
        "io.modelcontextprotocol/clientInfo":{"name":"my-agent","version":"1.0.0"}
      }
    }
  }'
```

返回 `supportedVersions`、`capabilities.tools`、`ttlMs=300000` 与 `cacheScope=public`。

### 3.2 列出工具

```bash
curl -X POST "$BASE/api/v1/knowledge/mcp" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/list" \
  -d '{
    "jsonrpc":"2.0","id":2,"method":"tools/list","params":{
      "_meta":{
        "io.modelcontextprotocol/protocolVersion":"2026-07-28",
        "io.modelcontextprotocol/clientCapabilities":{}
      }
    }
  }'
```

工具目录按名称稳定排序，可按返回的 `ttlMs` 缓存。工具 inputSchema 不含租户私有数据，因此
`cacheScope=public`；实际调用结果绝不可按该规则共享缓存。

### 3.3 调用工具

```bash
curl -X POST "$BASE/api/v1/knowledge/mcp" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/call" \
  -H "Mcp-Name: knowledge_search" \
  -d '{
    "jsonrpc":"2.0","id":3,"method":"tools/call","params":{
      "name":"knowledge_search",
      "arguments":{"app_id":"app0001","query":"什么是混合检索？"},
      "_meta":{
        "io.modelcontextprotocol/protocolVersion":"2026-07-28",
        "io.modelcontextprotocol/clientCapabilities":{}
      }
    }
  }'
```

成功 result 同时提供文本与结构化数据：

```json
{
  "resultType": "complete",
  "_meta": {
    "io.modelcontextprotocol/serverInfo": {"name": "kb-rag-knowledge", "version": "0.24.0"}
  },
  "content": [{"type": "text", "text": "{...JSON 文本...}"}],
  "structuredContent": {"nodes": [], "request_id": "...", "degraded": []},
  "isError": false
}
```

若工具名含非 ASCII、首尾空白或本身形如 sentinel，`Mcp-Name` 必须使用
`=?base64?{工具名 UTF-8 字节的 Base64}?=`；body 仍保留原工具名。

## 4. 旧版兼容流程

旧客户端可继续按 M20 流程调用，不需要现代 `_meta` 与三个镜像头：

```bash
curl -X POST "$BASE/api/v1/knowledge/mcp" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
    "protocolVersion":"2025-03-26","capabilities":{},
    "clientInfo":{"name":"legacy-agent","version":"1.0.0"}
  }}'
```

随后调用 `tools/list` / `tools/call`。旧版未知方法、非法参数等 JSON-RPC 错误仍使用 HTTP 200；
现代客户端不得先 initialize，应该先 `server/discover` 或直接发送带 `_meta` 的业务请求。

## 5. 工具目录

### 5.1 知识库

| 工具 | REST 孪生端点 | 必填参数 |
| --- | --- | --- |
| `knowledge_search` | `POST /api/v1/knowledge/search` | `app_id`、`query` |
| `knowledge_chat` | `POST /api/v1/knowledge/chat` | `app_id`、`query` |

可选参数包括 `app_version`、`messages`、`top_n`、`score_threshold`、`max_content_length`。
`knowledge_chat` 在 MCP 中只返回单次 JSON；需要 token 流时调用 REST SSE。传 `stream:true`
会得到工具业务错误，而不是协议错误。

### 5.2 记忆库

| 工具 | 作用 | 必填参数 |
| --- | --- | --- |
| `memory_add` | 抽取或直接写入长期记忆 | `user_id`，且 messages/custom_content 至少一个 |
| `memory_search` | 语义召回记忆与画像 | `user_id`、`query` |
| `memory_list` | 分页读取记忆 | `user_id` |
| `memory_update` | 更新一条记忆 | `memory_node_id`、`user_id`、`custom_content` |
| `memory_delete` | 删除一条记忆 | `memory_node_id`、`user_id` |
| `memory_get_profile` | 读取结构化画像 | `user_id` |

权威参数结构始终以当前端点 `tools/list` 返回的 `inputSchema` 为准。

## 6. 错误平面与处理

| 时代/平面 | HTTP / JSON-RPC | 处理 |
| --- | --- | --- |
| 现代头缺失、畸形或头体不一致 | 400 / `-32020` | 修正版本、方法或名称镜像头 |
| 现代版本不支持 | 400 / `-32022` | 从 `error.data.supported` 选择版本重试 |
| 现代方法未实现 | 404 / `-32601` | 不要调用未声明能力 |
| 现代参数形态错误 | 400 / `-32602` | 按 schema 修请求 |
| 旧版协议错误 | 200 / `-32700`、`-32600`、`-32601`、`-32602` | 修正 JSON-RPC 请求 |
| 工具业务失败 | 200 / result.`isError=true` | 让 Agent 阅读 content 并修参数重试 |
| Origin 不在白名单 | 403 / 空 body | 配置 `CORS_ALLOWED_ORIGINS` 或移除伪造 Origin |
| Key 无效/禁用 | 401 / REST 错误信封 | 重新签发或启用 Key |
| 超 QPS | 429 / REST 错误信封 | 按 `Retry-After` 退避 |

服务端无会话状态，不签发 `Mcp-Session-Id`。每个请求都重新认证，可安全重试和水平扩展。
控制台「MCP 调试」支持现代/旧版切换，会展示 HTTP 状态、JSON-RPC error、工具业务错误与
实际 curl，建议先在该页完成接入验证。
