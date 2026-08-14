# kb-rag

可自托管、开箱即用的企业知识库 / RAG（检索增强生成）系统：上传文档 → 自动解析切分 →
向量 + BM25 双路混合检索 → 标注与评测闭环 → 对外开放平台（REST + MCP），
并内置面向 Agent 的记忆库（长期记忆抽取 / 画像 / 记忆检索）。

**一句话架构**：Java 主服务（检索/管理编排）+ Python 解析服务（文档转 Markdown）+
React 管理台，三者围绕 MySQL（事实源）/ Elasticsearch 与 Qdrant（检索引擎）/
MinIO（对象存储）/ Neo4j（可选，图检索）构建，全部通过 docker-compose 一键拉起中间件。

> 本仓库由原先四个独立仓库（`kb-rag-server` / `kb-rag-parser` / `kb-rag-web` /
> `kb-rag-deploy`）合并而成，各子项目的完整提交历史已一并保留。

## 核心能力

| 能力域 | 已实现能力 |
| --- | --- |
| 数据接入 | PDF / DOCX / TXT / Markdown / SQL / XLSX / CSV / HTML / 图片上传，聊天记录导入，网页静态或 JS 渲染抓取，登录站点凭据，外部数据源连接器 |
| 索引与检索 | 文档版本化、可配置切分与父子分片、向量 + BM25 + 可选图路召回、RRF / 加权融合、Query 改写、重排、图片检索、多知识库路由 |
| 质量与治理 | 分片标注、评测集与 LLM-as-judge、发布门禁、检索反馈与洞察、文档审核、有效期、回收站、索引补偿与重建 |
| 应用与 Agent | 应用版本发布与快照回滚、知识库 REST API、MCP 工具、SSE 流式问答、Agent 长期记忆抽取 / 画像 / 检索 |
| 企业与运维 | 多租户隔离、RBAC 与知识库数据范围、文档 ACL、LDAP / OIDC / SAML / CAS、限流与审计、Prometheus 指标、备份恢复 |

当前 `main` 的实现基线覆盖 M1–M20；其中 M15 / M16 为权限与企业化能力，M17 / M18
为网页抓取增强，M19 为记忆库，M20 为 MCP 协议层。详细边界以
[`ARCHITECTURE.md`](kb-rag-deploy/docs/ARCHITECTURE.md) 和现有里程碑契约为准。

## 架构图

```mermaid
flowchart TB
    subgraph clients["接入层"]
        WEB["kb-rag-web<br/>React 18 + AntD 5（dev :20002）"]
        AGENT["智能体应用<br/>REST + API Key / Memory Key"]
        MCPC["MCP 客户端<br/>Claude Desktop / Cursor / 自研 Agent"]
    end

    subgraph server["kb-rag-server（Java 17 / Spring Boot 3，:20000）"]
        API["kb-api<br/>HTTP 边界与装配点<br/>REST + MCP JSON-RPC 引擎"]
        APP["kb-app<br/>应用编排（索引管线 / 检索链路 / 评测发布）"]
        DOMAIN["kb-domain<br/>实体 + 出站端口 + 领域算法"]
        INFRA["kb-infrastructure<br/>端口实现（引擎 / 存储 / 模型 Provider）"]
        API --> APP --> DOMAIN
        API --> INFRA --> DOMAIN
    end

    PARSER["kb-rag-parser<br/>Python 3.11 + FastAPI（:20001）<br/>文档解析 / 图片抽取 / 可选 OCR，不调用大模型"]

    subgraph middleware["中间件（docker-compose 一键拉起）"]
        MYSQL[("MySQL :13306<br/>唯一事实源")]
        ES[("Elasticsearch :9200<br/>BM25（lite 模式兼向量）<br/>+ 记忆检索副本")]
        QDRANT[("Qdrant :6333<br/>向量检索（full 模式）")]
        MINIO[("MinIO :9000<br/>原件 / 解析产物 / 归档")]
        NEO4J[("Neo4j :7687<br/>图检索（可选）")]
    end

    LLM["大模型服务（DashScope）<br/>嵌入 / 重排 / 对话 / 视觉<br/>零 Key 可降级运行"]

    WEB -- "/api（Vite 代理 / Nginx 反代）" --> API
    AGENT -- "/api/v1/knowledge/*（kb-sk-*）<br/>/api/v1/memory/*（kb-mk-*）" --> API
    MCPC -- "JSON-RPC 2.0 over HTTP<br/>/api/v1/knowledge/mcp · /api/v1/memory/mcp" --> API
    INFRA -- "HTTP multipart" --> PARSER
    INFRA --> MYSQL & ES & QDRANT & MINIO & NEO4J
    INFRA --> LLM
```

- **kb-rag-server**：唯一业务中枢，负责管理台 API、对外开放 API（REST + MCP）、索引管线编排、检索链路、记忆库与全部大模型调用。
- **kb-rag-parser**：纯解析微服务，只做文件解析与图片抽取，不调用任何大模型。
- **MySQL 是唯一事实源**：ES / Qdrant / Neo4j 均为派生索引，可从 MySQL 幂等重建。
- **三条独立鉴权链**：管理台 Bearer Token、知识库 API Key（`kb-sk-*`）、记忆库 Memory Key（`kb-mk-*`）；
  MCP 端点刻意落在既有过滤器前缀之下，零改动复用同一条鉴权 / 限流 / 审计管线。

## 核心流程图

### 索引管线：上传 → 解析 → 切分 → 嵌入 → 双写

```mermaid
flowchart LR
    U["上传文档<br/>（校验扩展名/大小/magic number）"] --> S1["原件落 MinIO<br/>事务写 document + version"]
    S1 -- "事务提交后异步" --> S2["解析<br/>（kb-rag-parser HTTP）"]
    S2 --> S3["清洗 / 脱敏"]
    S3 --> S4["图片 VLM 文本代理<br/>（零 Key 跳过）"]
    S4 --> S5["切分<br/>（策略路由，父子两级可选）"]
    S5 --> S6["嵌入<br/>（零 Key 全部 SKIPPED）"]
    S6 --> S7["双写引擎<br/>chunk 先写 MySQL 事实源，<br/>同步状态表 + 定时补偿保障最终一致"]
    S7 --> S8["版本激活<br/>process_status=INDEXED"]
```

### 检索链路：多路召回 → 融合 → 重排 → 过滤

```mermaid
flowchart LR
    Q["query<br/>（可带图片，VLM 转文本）"] --> RW["query 改写<br/>（超时降级原 query）"]
    RW --> RT["多库路由<br/>（LLM 提议 + 白名单裁决）"]
    RT --> R1["向量路召回"]
    RT --> R2["BM25 路召回"]
    RT --> R3["图路召回（可选）"]
    R1 & R2 & R3 --> F["库内融合<br/>（RRF / 加权）→ 跨库 RRF"]
    F --> RR["rerank 重排<br/>（超时降级粗排）"]
    RR --> PC["父子归并 → 阈值过滤 → top_n"]
    PC --> OUT["nodes + degraded + score_type"]
```

每级均可选且带明确降级语义（`degraded` 原因码透出）；各路召回强制过滤版本可见集与 `enabled=1`。

### 应用发布：门禁双跑 → 快照冻结 → RELEASED

```mermaid
flowchart LR
    T["TESTING 版本发起正式发布"] --> G["门禁双跑<br/>（候选配置 vs 当前正式版，<br/>同语料同时刻各跑一份评测）"]
    G --> J["三态裁决<br/>通过 / 拦截（可 force 放行留痕）/<br/>样本不足仅记录不拦截"]
    J --> SN["冻结发布快照<br/>（快照索引 + 可见版本集同时固化，<br/>门禁所测索引 = 发布后所用索引）"]
    SN --> REL["RELEASED（单应用唯一）<br/>原正式版退位 SUPERSEDED，可秒级回滚"]
```

RELEASED 版本的对外调用永远命中冻结快照，后续文档增删不影响线上应用，直到发布新版本。

### 记忆库：写入抽取与检索（`/api/v1/memory/*`，Memory Key 鉴权）

```mermaid
flowchart LR
    subgraph write["写入（memory_add）"]
        A["messages / custom_content<br/>二选一"] --> X["LLM 片段抽取<br/>（ADD / UPDATE，参考最近 50 条旧记忆；<br/>custom_content 直写不经 LLM）"]
        X --> W["MySQL 事实源 + ES 检索副本<br/>（嵌入失败该节点降级 BM25）"]
        X --> PF["画像字段抽取<br/>（规则绑定时幂等 upsert）"]
    end
    subgraph search["检索（memory_search）"]
        Q["user_id + query"] --> I["意图识别可 veto<br/>→ query 改写（失败降级原 query）"]
        I --> SE["kNN + BM25 并联召回<br/>（零 Key 降级 BM25 单路）"]
        SE --> R["rerank + 阈值过滤 → 回事实源 hydrate<br/>强制 filter：library_id + user_id + 未过期"]
    end
```

一把 Memory Key 绑定一个记忆库，库内按 `user_id` 隔离；隔离是查询谓词，越权一律 404。

### MCP 协议层：一个 URL + 一把既有 Key 直接接入

```mermaid
flowchart LR
    C["MCP 客户端<br/>（Claude Desktop / Cursor /<br/>Cline / 自研 Agent）"] -- "POST JSON-RPC 2.0<br/>Bearer kb-sk-* / kb-mk-*" --> FL["既有过滤器链<br/>（鉴权 / 限流 / 审计零改动复用）"]
    FL --> EN["McpServerEngine（无状态，零依赖手写）<br/>initialize / ping / tools/list / tools/call"]
    EN --> TK["知识库：knowledge_search · knowledge_chat<br/>记忆库：memory_add · search · list ·<br/>update · delete · get_profile"]
    TK --> RS["复用 REST 孪生服务，返回结构同源<br/>业务失败 → isError:true 工具结果<br/>协议违规 → JSON-RPC error"]
```

接入配置与工具目录见 [`docs/MCP接入指南.md`](docs/MCP接入指南.md)、[`docs/记忆库接入指南.md`](docs/记忆库接入指南.md)。

更完整的架构说明与全量流程图（文档/版本状态机、双写补偿、索引重建、评测运行、图路、备份恢复等），见
[`kb-rag-deploy/docs/ARCHITECTURE.md`](kb-rag-deploy/docs/ARCHITECTURE.md) 与
[`kb-rag-deploy/docs/FLOWS.md`](kb-rag-deploy/docs/FLOWS.md)。

## 目录结构

| 子目录 | 职责 | 技术栈 |
| --- | --- | --- |
| [`kb-rag-server`](kb-rag-server/) | Java 主服务：知识库与文档生命周期、索引管线编排、检索融合、标注评测、应用发布、记忆库、对外 API（REST + MCP），以及全部大模型调用 | Java + Spring Boot + MyBatis |
| [`kb-rag-parser`](kb-rag-parser/) | Python 解析微服务：文档转结构化 Markdown + 按页文本 + 图片，聊天记录导出转结构化会话 | Python 3.11 + FastAPI |
| [`kb-rag-web`](kb-rag-web/) | React 管理台 | Vite + React 18 + TypeScript + Ant Design 5 |
| [`kb-rag-deploy`](kb-rag-deploy/) | 部署与契约：docker-compose 编排、环境变量模板、跨服务 OpenAPI 契约、备份与预检脚本、总体文档 | Docker Compose + Shell |

## 快速启动

推荐**轻量模式（lite）**：MySQL + Elasticsearch（同时承担 BM25 与向量检索）+ MinIO，
8GB 内存即可跑起来。不填 `DASHSCOPE_API_KEY` 即为**零 Key 模式**——不需要任何账号和
Key，clone 下来就能看到「上传 → 检索」跑通（检索自动降级 BM25 单路，依赖模型的功能置灰并给出引导）。

### 0. 环境要求

- Docker Engine / Docker Desktop，并支持 `docker compose`
- JDK 17、Maven 3.6+
- Python 3.11+
- Node.js 22、npm
- lite 模式约需 8GB 可用内存；full 模式建议 16GB 以上

以下命令均从仓库根目录开始执行，应用层需要分别占用三个终端。

### 1. 配置并拉起中间件

```bash
cd kb-rag-deploy
cp .env.example .env
# 编辑 .env：所有 CHANGE_ME_* 占位口令必须替换；DASHSCOPE_API_KEY 可留空
./scripts/preflight.sh lite # 校验 docker / 内存 / 端口占用 / 是否还在用占位口令
docker compose -f docker-compose.lite.yml up -d
docker compose -f docker-compose.lite.yml ps
```

`mysql`、`elasticsearch`、`minio` 均显示 `healthy` 后，再启动应用层。

### 2. 启动解析服务（终端 A）

```bash
cd kb-rag-parser
python3.11 -m venv .venv
.venv/bin/pip install -r requirements.txt
set -a; source ../kb-rag-deploy/.env; set +a
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 20001
```

### 3. 构建并启动 Java 主服务（终端 B）

```bash
cd kb-rag-server
mvn -B -ntp -DskipTests package
set -a; source ../kb-rag-deploy/.env; set +a
java -jar kb-api/target/kb-rag-server.jar
```

> `source ../kb-rag-deploy/.env` 不能省略：中间件与应用必须使用同一组 MySQL / MinIO
> 凭据。上面的写法适用于 bash / zsh；其他 Shell 请使用等价的环境变量加载方式。

### 4. 启动管理台（终端 C）

```bash
cd kb-rag-web
npm install
npm run dev
```

### 5. 验证

```bash
curl -fsS http://127.0.0.1:20001/health
curl -fsS http://127.0.0.1:20000/actuator/health
```

两个接口分别返回 `{"status":"UP"}` 和整体状态 `UP` 后，打开
`http://localhost:20002`。首次启动 kb-rag-server 时会在日志中打印随机生成的 `admin`
初始密码，可搜索日志关键字 `bootstrap administrator created`；该密码只打印一次，首次登录后
系统会强制修改密码，请在首次启动时立即保存。若密码丢失，只能由另一名具备 `user:manage`
权限的管理员在用户管理中重置；当前没有未登录密码恢复入口，重启服务也不会重新生成密码。

完成登录后，按下面的最短路径验证零 Key 闭环：

1. 在「知识库」中新建知识库并上传一份包含可检索文本的文档。
2. 等待文档状态变为「已就绪」。
3. 进入「检索调试」，选择该知识库并查询文档中明确出现的关键词。
4. 结果应返回相关分片，同时降级提示包含 `vector_route_unavailable`，表示当前按预期使用 BM25 单路召回。

`DASHSCOPE_API_KEY` 是嵌入、重排、对话和视觉 Provider 的默认共享凭据。配置后重新加载 `.env`
并重启 Java 主服务，即可使用向量检索、重排、VLM 图片理解，以及由具体操作触发的对话、评测、
路由和记忆抽取能力；这些业务能力仍受应用版本配置、评测任务或记忆规则约束，不会在后台自动执行。
Query 改写还需显式设置 `RETRIEVAL_REWRITE_ENABLED=true`，因为它会给每次检索增加一次模型调用，默认关闭。
零 Key 模式下已入库文档的嵌入状态为 `SKIPPED`；补 Key 后需要在知识库详情对这些文档执行
逐篇或批量重建，旧文档才会生成向量，不能只重启服务。
每次修改 `.env` 后都要在启动该进程的终端重新执行 `set -a; source ../kb-rag-deploy/.env; set +a`，
否则 Shell 仍保留旧值。

完整的部署模式、资源要求矩阵与 full 模式（独立 Qdrant）升级路径，见
[`kb-rag-deploy/README.md`](kb-rag-deploy/README.md)。
full 编排中还预留了可选 Redis 容器，但当前 Java 主服务仍使用进程内限流，单实例运行不需要 Redis。

停止本地环境时，先在三个应用终端按 `Ctrl+C`，再执行：

```bash
cd ../kb-rag-deploy # 当前终端位于任一 kb-rag-* 子目录时
docker compose -f docker-compose.lite.yml down
```

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [`kb-rag-deploy/README.md`](kb-rag-deploy/README.md) | 部署总入口：部署模式、环境变量、ik 分词、备份恢复、各里程碑功能说明 |
| [`kb-rag-deploy/docs/ARCHITECTURE.md`](kb-rag-deploy/docs/ARCHITECTURE.md) | 系统整体架构 |
| [`kb-rag-deploy/docs/FLOWS.md`](kb-rag-deploy/docs/FLOWS.md) | 全量核心流程图（状态机、双写补偿、索引重建、评测、备份恢复等） |
| `kb-rag-deploy/docs/M1-CONTRACTS.md` ～ `M17-CONTRACTS.md`、`M19-CONTRACTS.md`、`M20-CONTRACTS.md` | 已落库的里程碑实现契约；M18 的站点凭据实现与后续隔离修复见架构文档 |
| [`kb-rag-deploy/docs/openapi/kb-server.yaml`](kb-rag-deploy/docs/openapi/kb-server.yaml) | Java 主服务 OpenAPI 契约 |
| [`kb-rag-deploy/docs/openapi/kb-parser.yaml`](kb-rag-deploy/docs/openapi/kb-parser.yaml) | Python 解析服务 OpenAPI 契约 |
| [`docs/MCP接入指南.md`](docs/MCP接入指南.md) | MCP 客户端（Claude Desktop / Cursor 等）接入配置与工具目录 |
| [`docs/记忆库接入指南.md`](docs/记忆库接入指南.md) | 记忆库 REST + MCP 接入、Memory Key 管理 |
| [`docs/知识库需求文档.md`](docs/知识库需求文档.md) | 需求全集与设计取舍 |
| [`docs/自测步骤.md`](docs/自测步骤.md) | 端到端自测清单 |
| 各子目录 `README.md` / `CONTRIBUTING.md` / `SECURITY.md` | 子项目自身的架构细节、开发规范与安全上报渠道 |

## 安全说明

`.env` 已在 [`.gitignore`](.gitignore) 中忽略，任何真实密钥、口令均不入库；提交前请以
`.env.example` 为模板，确保仅提交占位值。安全漏洞请通过各子目录的 `SECURITY.md` 渠道私下上报，不要开公开 issue。

## 许可与第三方依赖

四个子项目的项目代码均以 Apache License 2.0 许可发布：
[`server`](kb-rag-server/LICENSE)、[`parser`](kb-rag-parser/LICENSE)、
[`web`](kb-rag-web/LICENSE)、[`deploy`](kb-rag-deploy/LICENSE)。

需要特别注意：`kb-rag-parser` 当前直接依赖 PyMuPDF，而该依赖采用 AGPL-3.0 / 商业许可
双重授权。分发或以网络服务形式部署前，请先阅读
[`kb-rag-parser/README.md`](kb-rag-parser/README.md#许可注意) 与
[`kb-rag-parser/NOTICE`](kb-rag-parser/NOTICE)，并根据实际使用方式完成许可证合规评估。
