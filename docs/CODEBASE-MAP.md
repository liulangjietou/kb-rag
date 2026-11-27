# kb-rag 代码导航（留档用）

> 面向「第一次改这个仓」的开发者。细节实现仍以 [`kb-rag-deploy/docs/ARCHITECTURE.md`](../kb-rag-deploy/docs/ARCHITECTURE.md) 与 OpenAPI 为准；本文强调**从哪进、往哪跟、测什么**。

## 1. 仓库切片

| 路径 | 运行时角色 | 典型改动 |
| --- | --- | --- |
| `kb-rag-server/kb-api` | HTTP 入口、鉴权过滤器、Controller | 新 REST、契约 DTO、SSE 端点 |
| `kb-rag-server/kb-app` | 业务编排（索引、检索、员工问答、质量运营） | 服务类、定时任务、OpenAPI 实现 |
| `kb-rag-server/kb-domain` | 实体、Mapper、领域模型、纯逻辑与出站端口（`domain.port`） | 表映射、切分/检索参数模型、端口接口 |
| `kb-rag-server/kb-infrastructure` | 端口实现：ES/Qdrant/Neo4j/MinIO/模型 Provider/parser 客户端 | 外部系统客户端、流式订阅 |
| `kb-rag-server/kb-common` | 异常、工具类 | 横切工具 |
| `kb-rag-parser` / `kb-rag-parse-java` | 文档解析（二选一部署） | 解析契约、版面规则 |
| `kb-rag-web` | React 管理台 + 员工工作台 | 页面、API 封装 |
| `kb-rag-deploy` | Compose、`.env.example`、运维脚本、里程碑契约 | 部署与配置漂移门禁 |

## 2. 启动与请求主路径

```text
浏览器 / 外部客户端
  → kb-rag-web (dev :20002, 代理 /api)
  → KbRagServerApplication (kb-api :20000)
       → AuthInterceptor / PermissionInterceptor（控制台 Sa-Token 会话）
         或 ApiKeyAuthFilter / MemoryKeyAuthFilter（开放 API）
       → *Controller (kb-api)
       → *Service (kb-app)
       → Mapper (kb-domain) / 出站端口 VectorStore、FulltextStore、GraphStore、
         ChatProvider、EmbeddingProvider、RerankProvider… (kb-domain.port，kb-infrastructure 实现)
  → 中间件：MySQL（事实）、MinIO（原件）、ES/Qdrant/Neo4j（检索派生）

文档入库旁路：kb-app IndexPipelineService → DocumentParserClient（HttpDocumentParserClient）→ HTTP :20001
            → kb-rag-parse-java 或 kb-rag-parser（二选一，同一契约）
```

**本地最短链路**：见根 [`README.md`](../README.md)「快速启动」；端到端验收见 [`自测步骤.md`](自测步骤.md)。本地跑主要门禁：仓库根目录 `./scripts/dev-gate.sh`（覆盖五个模块的单测/构建与部署配置校验；不含 web e2e、Compose 组合与 OpenAPI/Shell 语法校验，这些只在 CI 跑）。

## 3. 核心数据流（改功能前先对号入座）

### 3.1 文档入库

`DocumentController` / 来源同步 → `IndexPipelineService` → `DocumentParserClient` → 清洗/脱敏 → `ChunkSplitter`（`SplitterRouter` 按 `resolveSplitStrategy(扩展名)` 选策略）→ `ChunkEmbedder` → `ChunkIndexWriter` 写 ES/Qdrant（同步行 `t_kb_chunk_index_sync`，失败由 `IndexSyncCompensationService` 补偿）→ `VersionActivationHandler` 激活文档版本。

单测锚点：`IndexPipelineServiceTest`（管线阶段）、`IndexSyncCompensationServiceTest`、domain 层 `*SplitterTest`（如 `EmbeddingSemanticTextSplitterTest`、`TableRowTextSplitterTest`）。

### 3.2 检索与开放 API

`KnowledgeApiService` / `RetrievalService` → `RewriteService` → `RoutingService` → 多路召回（`VectorStore` / `FulltextStore` / `GraphRetrievalService` / `MultimodalRetrievalService`）→ `FusionRouter` + `CrossKbRrfFusion` → `RerankService` → `ParentChildMerger` → `RetrievalNodeAssembler`。

单测锚点：`RetrievalServiceTest`、`RoutingServiceTest`、`RerankServiceTest`；契约测在 `kb-api/src/test` 的 `*ContractTest`。

### 3.3 员工问答（SSE）

`EmployeeConversationController`（`/api/v1/workspace/apps/{appId}/conversations/{id}/runs`，SSE 订阅 `.../runs/{runId}/events` 走 `EmployeeRunSubscriptions`）→ `EmployeeConversationService` → `EmployeeRunCoordinator` → `KnowledgeApiService`（检索 + `ChatStreamListener` 流式生成）；评价 `PUT .../runs/{runId}/feedback` → `EmployeeConversationService#feedback` → `EmployeeFeedbackQualityAutomation`（开关关闭时直接返回）。

单测锚点：`EmployeeRunCoordinatorTest`、`EmployeeAnswerFeedbackServiceTest`、`EmployeeFeedbackQualityAutomationTest`；HTTP/SSE 集成测 `EmployeeRunHttpIntegrationTest`。

### 3.4 质量运营

控制台质量问题（`KnowledgeQualityIssueController` → `KnowledgeQualityIssueService`）、员工负面反馈列表（`EmployeeFeedbackController` → `EmployeeFeedbackService`）、**系统设置 → 质量自动化**（`SystemController` → `QualityAutomationConfigService`）。

单测锚点：`KnowledgeQualityIssueServiceTest`、`QualityAutomationConfigServiceTest`、前端 `QualityAutomationTab.test.tsx`。

## 4. 测试落点约定

| 层级 | 放哪 | 命名 |
| --- | --- | --- |
| 纯领域逻辑 | `kb-domain/src/test` | `*Test` |
| 业务服务 | `kb-app/src/test` | `*Test`，权限类可叫 `*AccessTest` |
| HTTP 契约 | `kb-api/src/test` | `*ContractTest`、`*IntegrationTest` |
| 前端组件 | `kb-rag-web/src/**/*.test.tsx` | Vitest + Testing Library |
| 部署契约 | `kb-rag-deploy/tests` | `test_*.py` |

提交前命令与 CI 对齐：[`CONTRIBUTING.md`](../CONTRIBUTING.md)、`./scripts/dev-gate.sh`。

## 5. 配置与环境

- 运行期配置：`kb-rag-deploy/.env`（从 `.env.example` 复制）。服务端不会自己读取该文件：命令行用 `set -a; source .env; set +a` 注入进程环境，IDE 启动可加参数 `--spring.config.import=optional:file:<绝对路径>/kb-rag-deploy/.env[.properties]`。
- 系统级开关（非 `.env`）：`t_kb_system_config`，读写经 `SystemConfigService`。
- 应用版本快照：`AppConfigSnapshot`（发布门禁冻结，**不做缺省回落**）。

## 6. 改代码时的检查清单

1. API / 字段变更 → 更新 `kb-rag-deploy/docs/openapi/kb-server.yaml` 与相关 `M*-CONTRACTS.md`。
2. 新环境变量 → `.env.example` + 部署 README。
3. 行为变更 → 同 PR 内补或改单测；员工/开放 API 优先契约测。
4. 跨模块重命名 → 用 GitNexus `impact` / `rename`，避免盲目全局替换。

## 7. 相关文档

- [架构说明](../kb-rag-deploy/docs/ARCHITECTURE.md) · [流程图](../kb-rag-deploy/docs/FLOWS.md)
- [开发排障](TROUBLESHOOTING-DEV.md) · [首轮审查纪要](FIRST-PASS-REVIEW.md)
- [贡献指南](../CONTRIBUTING.md)