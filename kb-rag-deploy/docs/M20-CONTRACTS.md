# M20 开发契约（MCP 协议层 · 增量于 M1-M19 契约）

> 需求依据：知识库应用与记忆库的开放能力目前只有 REST 一种形态，每个 Agent 框架都要写一遍胶水代码。本期给两者各加一层 **MCP（Model Context Protocol）**：任何 MCP 兼容客户端（Claude Desktop / Cursor / Cline / 自研 Agent…）配一个 URL 加一把既有 Key 即可直接调用，无需任何定制开发。控制台新增「MCP 调试」页面，主仓补 `docs/MCP接入指南.md`。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带错误码、lombok、CollectionUtils 判空、无魔法值、fast-fail 只在 Controller、不主动 commit）。

## 0. 范围与边界

- **本期做**：①两个 MCP 端点——`POST /api/v1/knowledge/mcp`（知识库应用，工具 knowledge_search / knowledge_chat）与 `POST /api/v1/memory/mcp`（记忆库，memory_add / memory_search / memory_list / memory_update / memory_delete / memory_get_profile 六工具）；②手写轻量 JSON-RPC 2.0 引擎（`McpServerEngine`，无状态），支持 initialize / ping / tools/list / tools/call / notifications/*；③控制台「MCP 调试」页面（initialize 握手、工具目录、tools/call 调试、curl 与客户端配置示例）；④文档与 OpenAPI 契约（升 `0.20.0-m20`）。
- **本期不做**：SSE 流式响应（MCP Streamable HTTP 允许单 JSON 响应，tools/call 本就是单次请求应答，chat 要流式走 REST 孪生端点的 SSE）；MCP resources / prompts / sampling 能力（capabilities 只声明 tools）；stdio transport（服务端形态天然是 HTTP）；会话管理与 `Mcp-Session-Id`（引擎无状态，每个请求自带完整上下文，规范允许无会话服务器）；JSON-RPC 批量数组（2025-03-26 修订已移除，直接 -32600）。
- **零依赖红线**：不引入 MCP SDK 与任何新 Maven / npm 依赖——协议面就是「一个 POST 上的 JSON-RPC 2.0 子集」，Jackson 手写引擎 226 行闭合，SDK 带来的会话/流式包袱本期恰恰都不要。
- **兼容红线**：纯新增——两个新 Controller 落在既有过滤器前缀之下（见 §2），零过滤器改动、零配置改动、存量端点与行为零变化。

## 1. 协议面（`McpServerEngine`，kb-api `api.mcp`）

- **Transport**：MCP Streamable HTTP 的单 JSON 响应形态——`POST` 收一个 JSON-RPC 请求对象，回一个 JSON 响应对象；通知（`notifications/*`，无 id）回 **202 无 body**。协议版本 `2025-03-26`，客户端报 `2024-11-05` 时按其回显（向下兼容），报未知版本时回自身版本由客户端决定去留。
- **方法表**：`initialize`（协商版本 + `capabilities.tools` + serverInfo）、`ping`（空对象）、`tools/list`（工具目录，含 JSON Schema 形态的 inputSchema）、`tools/call`（执行工具）；其余方法 -32601。
- **两个失败平面（本期核心不变式）**：
  - **协议违规 → JSON-RPC error**：JSON 解析失败 -32700、批量数组/缺 id -32600、方法不存在 -32601、工具名不在目录/arguments 非对象 -32602。
  - **业务失败 → 工具结果**：执行中抛出的 `BizException` 映射为 tools/call **成功响应**里 `isError: true` 的结果，`content[0].text` 形如 `错误码: 消息`——对 Agent 来说「参数不对/记忆不存在」是需要它自行修正重试的工具反馈，不是协议层故障。
- **成功结果**：`content: [{type: "text", text: <JSON 文本>}]` + `structuredContent: <同 REST data 结构>` + `isError: false`——文本给只读 text 的老客户端，结构化给新客户端，两者同源。
- HTTP 状态恒 200（通知 202）——JSON-RPC 的错误在 body 里，HTTP 非 200 只可能来自过滤器链（401/429，信封同 REST）。

## 2. 鉴权：MCP 是第二种 transport，不是第二种身份

| 端点 | 落在谁的前缀下 | 凭证 | 复用了什么 |
|---|---|---|---|
| `POST /api/v1/knowledge/mcp` | `ApiKeyAuthFilter`（`/api/v1/knowledge/*`） | `Authorization: Bearer kb-sk-*` | 鉴权、app_scope、按 Key 令牌桶限流、调用审计 |
| `POST /api/v1/memory/mcp` | `MemoryKeyAuthFilter`（`/api/v1/memory/*`） | `Authorization: Bearer kb-mk-*` | 鉴权、库绑定关系、按 Key 令牌桶限流 |

- 端点路径**刻意**选在两条既有过滤器链的 URL 前缀之下：MCP 请求进 Controller 前已经过完全同一条鉴权/限流/审计管线，一行过滤器代码都不用改。Controller 只从 request attribute 读过滤器放进来的 principal，读不到即 500 级装配故障（正常流量不可能触达）。
- 记忆库隔离红线原样成立：工具操作的库来自 principal（Key 绑定关系），arguments 无需也无法指定 library_id；越权继续 404。

## 3. 工具集（参数与返回结构 = REST 孪生端点）

### 3.1 知识库（server name `kb-rag-knowledge`）
| 工具 | REST 孪生 | 语义要点 |
|---|---|---|
| `knowledge_search` | `POST /api/v1/knowledge/search` | 纯检索；必填 app_id / query，覆盖白名单 top_n / score_threshold / max_content_length 同 REST |
| `knowledge_chat` | `POST /api/v1/knowledge/chat` | RAG 问答，**仅非流式**——tools/call 是单次请求应答，`stream: true` 直接 400 INVALID_PARAM 并在消息里指路 REST SSE |

- 参数绑定复用 `KnowledgeCallRequest`（Jackson treeToValue + jakarta Validator 显式校验——tree 转换不触发 bean validation，`McpArgumentBinder` 手动补上这一刀）。

### 3.2 记忆库（server name `kb-rag-memory`）
| 工具 | REST 孪生 |
|---|---|
| `memory_add` | `POST /api/v1/memory/add` |
| `memory_search` | `POST /api/v1/memory/search` |
| `memory_list` | `GET /api/v1/memory/memory_nodes`（page_num/page_size 默认 1/10 同 REST） |
| `memory_update` | `PATCH /api/v1/memory/memory_nodes/{nodeId}`（path 参数收进 arguments 的 memory_node_id） |
| `memory_delete` | `DELETE /api/v1/memory/memory_nodes/{nodeId}`（返回 `{deleted, memory_node_id}`——MCP 工具结果不宜为空） |
| `memory_get_profile` | `GET /api/v1/memory/profiles` |

- add/search 复用 REST DTO；list/update/delete/profile 的 GET/path 形态参数收敛为 Controller 内部 record（@JsonProperty snake_case + jakarta 校验注解），字段名与 REST 的 query/path 参数逐一对应。

## 4. kb-rag-web（「MCP 调试」页面）

- 新增一级菜单「MCP 调试」（`/mcp`，`ApiOutlined`，`app:read` 或 `memory:read` 任一可见，位于「记忆库」与「评测中心」之间）。
- `pages/mcp/McpDebugPage.tsx`：端点二选一（知识库/记忆库，切换即清场）→ 粘贴明文 Key（kb-sk-* / kb-mk-*，与 API 调试 tab 同一约定：明文仅签发时展示一次）→ initialize 握手 / tools/list 拉目录（选中工具自动按 inputSchema.required 预填参数模板）→ tools/call 发起调用。
- 响应区显式区分**三种结果平面**：JSON-RPC error（红，协议错误码）、`isError: true`（橙，业务失败文本）、成功（绿）；原始 JSON-RPC 响应全文展示。
- 接入示例区随表单实时生成 curl 与 MCP 客户端 `mcpServers` 配置片段（type=streamable-http + url + Authorization header）。
- `api/mcp.ts`：仿 publicApi.ts 绕过共享 axios 实例直连 fetch——粘贴的 Key 不是管理台 JWT，401/429 正是页面要观察的对象。

## 5. kb-rag-deploy（收尾）

- **OpenAPI kb-server.yaml 升至 `0.20.0-m20`**：新增 `mcp` tag、两个 MCP path、`McpJsonRpcRequest` / `McpJsonRpcResponse` schema。
- CHANGELOG 新增 M20 条目（server/web/deploy 三仓）。
- 调用方文档：主仓 `docs/MCP接入指南.md`（客户端配置、工具目录、curl 示例、两个失败平面说明、错误码速查）。

## 6. 单测（已交付，随分支）

- `McpServerEngineTest`（kb-api，离线）：initialize 版本协商与回落、通知 202 无 body、tools/list 目录、tools/call 文本+结构化双形态、BizException → isError 平面、未知工具 -32602、未知方法 -32601、坏 JSON -32700、批量数组与缺 id -32600、ping 空对象，共 12 例。

## 7. 验收

1. 控制台应用中心签发 API Key → curl 带 `Bearer kb-sk-*` 依次调 `initialize` / `tools/list` / `tools/call knowledge_search`（app_id + query）→ 三步 200，最后一步 `structuredContent.nodes` 与 REST search 一致。
2. `tools/call knowledge_chat` 带 `"stream": true` → 200 但 `isError: true`，文本含 INVALID_PARAM 与 REST SSE 指路。
3. 记忆库签发 Memory Key → `tools/call memory_add`（custom_content）→ `memory_search` 同 user_id 命中、换 user_id 不命中；`memory_delete` 不存在的节点 → `isError: true` 文本含 404 语义错误码。
4. 发一个 `{"jsonrpc":"2.0","method":"notifications/initialized"}` → 202 无 body；发 `[]` → -32600；坏 JSON → -32700。
5. Key 禁用/轮换后调 MCP → 401 信封同 REST；连打超 QPS → 429 RATE_LIMITED——证明与 REST 同一条过滤器链。
6. Claude Desktop / Cursor 任一 MCP 客户端按 `mcpServers` 配置片段接入 → 工具目录可见、调用成功。
7. 控制台「MCP 调试」页：无 `app:read` 且无 `memory:read` 的角色菜单不可见、直贴 `/mcp` 原地 403。
