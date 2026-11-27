# 本地开发排障速查

症状 → 先确认什么 → 常见原因 → 下一步。端口以契约为准：server `20000`、parser `20001`、web dev `20002`、管理 actuator `20003`（仅本机）。

## 服务起不来

| 症状 | 先确认 | 常见原因 | 处理 |
| --- | --- | --- | --- |
| `Connection refused` 访问 API | `curl -fsS http://127.0.0.1:20003/actuator/health` | Java 未启动或未加载 `.env` | 从 `kb-rag-deploy` source `.env` 后启动 jar；IDE 加 `spring.config.import=optional:file:.../.env[.properties]` |
| 健康检查非 UP | 日志中 MySQL/ES 连接错误 | 中间件未起或端口冲突 | `docker compose -f docker-compose.lite.yml ps`；`kb-rag-deploy/scripts/preflight.sh` |
| parser 健康失败 | `curl :20001/health` | venv 未建或端口占用 | `kb-rag-parser` 下创建 venv 并 `uvicorn app.main:app` |

## 索引一直不「已就绪」

1. 管理台文档详情看阶段与错误码。
2. 无 embedding Key 时向量会 `SKIPPED`，BM25 仍可用（见根 README）。
3. 解析失败：查 server 调 parser 的 HTTP 日志；单独对 parser 上传样例复现。
4. 补偿：见 [`kb-rag-deploy/docs/DOCUMENT-UPLOAD-RECOVERY.md`](../kb-rag-deploy/docs/DOCUMENT-UPLOAD-RECOVERY.md)。

## 检索有结果、问答失败

- 未配置 `CHAT_MODEL` / Key → 开放 `chat` 返回 `UPSTREAM_MODEL_ERROR`，`search` 仍可用。
- 员工 SSE 中断：[`CHAT-STREAM-LIFECYCLE.md`](../kb-rag-deploy/docs/CHAT-STREAM-LIFECYCLE.md)、[`CHAT-DIAGNOSTICS.md`](CHAT-DIAGNOSTICS.md)。

## 前端连不上后端

- Vite 代理目标是否与 server 端口一致（默认 `20000`）。
- 会话：浏览器是否带 `satoken`；401 时先重新登录。
- `npm run build` 通过但 dev 白屏：看控制台与 `npm test` 是否已有失败用例。

## CI 本地复现

```bash
./scripts/dev-gate.sh              # 全模块（耗时）
./scripts/dev-gate.sh server web     # 只跑指定模块
```

单模块命令表见 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。

## 仍无法定位

1. 对照 [`CODEBASE-MAP.md`](CODEBASE-MAP.md) 找到对应 `*Service` 与现有 `*Test`。
2. 在 `kb-rag-deploy/docs/` 搜功能关键词（契约文档通常写明边界与已知偏离）。
3. GitNexus：`query` / `context` 查调用链（见根目录 `AGENTS.md`）。