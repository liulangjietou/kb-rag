# M22 开发契约（MCP 2026-07-28 双协议兼容 · 增量于 M1-M21）

> 规范基线：MCP 官方 `2026-07-28` schema 与 Streamable HTTP transport。M22 不是把旧版
> 原地替换掉，而是在同一 URL 上同时服务“initialize 握手时代”和“逐请求元数据时代”。

## 0. 第一性原理与范围

MCP 客户端能否可靠调用，取决于三个事实：服务端必须先知道请求遵循哪个时代的语义；网关看到
的路由头必须与业务 body 是同一事实；升级不能让已部署的旧客户端突然失联。因此本期不新增工具，
只收紧 transport 边界。

- **现代协议**：`2026-07-28`，无 initialize、无 session；支持 `server/discover` / `ping` /
  `tools/list` / `tools/call`，每个请求自带 `_meta`。
- **旧版协议**：保留 `2025-03-26` 与 M20 已承诺的 `2024-11-05` initialize 流程、响应结构和
  HTTP 200 错误承载语义。
- **共用不变式**：两个端点、两组工具、Key 身份、限流、审计和业务失败 `isError=true` 均不变。
- **仍不做**：SSE 响应、subscriptions、resources、prompts、MRTR、MCP Apps、OAuth 扩展与
  `Mcp-Param-*` 自定义镜像头。当前工具 schema 未声明 `x-mcp-header`，因此没有自定义头需要校验。

## 1. 时代识别与逐请求元数据

同一端点按当前请求独立识别协议，不保存协商状态：

1. 任一现代镜像头存在、`params._meta.io.modelcontextprotocol/protocolVersion` 存在，或方法为
   `server/discover`，按现代协议处理；
2. 否则按旧版处理。旧版 `initialize` 对未知版本仍回落到 `2025-03-26`，不错误地回落到现代版；
3. 现代请求的 `params._meta` 必须包含：
   - `io.modelcontextprotocol/protocolVersion: "2026-07-28"`
   - `io.modelcontextprotocol/clientCapabilities: {}`（可含能力，但必须是对象）
   - `io.modelcontextprotocol/clientInfo` 可选；出现时必须有字符串 `name` / `version`。

现代成功 result 一律含 `resultType: "complete"`，并在 `_meta.io.modelcontextprotocol/serverInfo`
回传服务名与版本。

## 2. Streamable HTTP 镜像头与状态码

| 头 | 现代版要求 | 校验来源 |
| --- | --- | --- |
| `MCP-Protocol-Version` | 每个 request 必填 | `params._meta.io.modelcontextprotocol/protocolVersion` |
| `Mcp-Method` | 每个 request 必填 | 顶层 `method` |
| `Mcp-Name` | `tools/call` 必填 | `params.name` |

- HTTP 头名大小写不敏感，值大小写敏感；Servlet 容器负责头名语义，引擎做值的精确比较。
- `Mcp-Name` 支持规范的精确 sentinel：`=?base64?{UTF-8 的 Base64}?=`；非法 Base64、非法 UTF-8、
  缺头或解码后不一致都视为 `HeaderMismatch`。
- 缺失、畸形或头体不一致：HTTP 400 + JSON-RPC `-32020`。
- 版本头与 body 一致但服务端不支持：HTTP 400 + `-32022`，`error.data` 返回
  `{supported:["2026-07-28","2025-03-26","2024-11-05"], requested:"..."}`。
- 现代版方法未实现：HTTP 404 + `-32601`；参数形态错误：HTTP 400 + `-32602`。
- 旧版协议错误继续 HTTP 200；通知继续 202 无 body。

## 3. Origin 与身份边界

- 新增 `McpOriginValidationFilter`，顺序位于 RequestId 后、两条 Key 鉴权过滤器前，仅覆盖两个
  MCP 精确路径。
- 无 `Origin` 的非浏览器客户端放行；带 `Origin` 时必须与现有 `kb.web.allowed-origins` /
  `CORS_ALLOWED_ORIGINS` 白名单精确匹配，否则 HTTP 403、无 body。
- 复用现有白名单，不增加第二套来源配置；非法来源在验证 Key 前被拒绝，避免消耗认证/限流资源。
- 通过 Origin 后，知识库仍由 `ApiKeyAuthFilter` 验 `kb-sk-*`，记忆库仍由
  `MemoryKeyAuthFilter` 验 `kb-mk-*`。

## 4. 发现、目录缓存与确定性

- `server/discover` 返回全部支持版本、`capabilities.tools={}`、使用说明、`ttlMs=300000`、
  `cacheScope=public`。
- 现代 `tools/list` 同样返回 `ttlMs=300000` / `cacheScope=public`；目录只含公开 schema，
  不随 Key 或租户改变，可以跨授权上下文缓存。
- 现代工具目录按名称排序，保证相同端点重复请求得到稳定顺序；旧版保留 M20 的既有声明顺序。

## 5. 控制台与调用方文档

- 「MCP 调试」增加现代/旧版切换，默认现代版；切换协议会清空工具与响应，避免跨时代复用状态。
- 现代版首步为 `server/discover`，旧版首步为 `initialize`；`tools/list` / `tools/call` 根据选择
  自动构造 body `_meta` 与动态镜像头。
- curl 预览展示真实的 `Accept`、版本、方法与名称头；客户端配置仍只填写 URL 与 Authorization，
  标准 MCP 客户端应自行协商并逐请求生成动态头。
- OpenAPI 升至 `0.24.0-m22`；`docs/MCP接入指南.md` 以现代版为主流程，旧版单列兼容示例。

## 6. 测试与验收

- Java 引擎矩阵：旧版回归、现代发现/缓存/结果元数据、三头校验、Base64 sentinel、版本协商、
  400/404 状态、业务失败平面与非法参数。
- Filter：非法 Origin 403、合法/缺 Origin 放行、非 MCP 路径不受影响。
- Web：现代/旧版请求构造、Unicode `Mcp-Name` 编码。

验收最短路径：

1. 带现代三头和 `_meta` 调 `server/discover` → 200，版本列表首项 `2026-07-28`；
2. 调 `tools/list` → 200，含 `resultType=complete`、`ttlMs=300000`、按名称稳定排序；
3. 改错 `Mcp-Method` → 400/-32020；请求 `resources/list` → 404/-32601；
4. 不带现代头按 M20 顺序 initialize → tools/list → tools/call，响应与升级前一致；
5. 合法 Key + 非白名单 Origin → 403；不带 Origin 的服务间客户端正常调用。
