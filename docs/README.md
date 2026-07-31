# kb-rag

可自托管、开箱即用的企业知识库 / RAG（检索增强生成）系统：上传文档 → 自动解析切分 →
双引擎混合检索（向量 + BM25）→ 标注与评测闭环 → 对外开放平台。

**一句话架构**：Java 主服务（检索/管理编排）+ Python 解析服务（文档转 Markdown）+
React 管理台，三者围绕 MySQL（事实源）/ Elasticsearch 与 Qdrant（检索引擎）/
MinIO（对象存储）/ Neo4j（可选，图检索）构建，全部通过 docker-compose 一键拉起中间件。

> 本仓库由原先四个独立仓库（`kb-rag-server` / `kb-rag-parser` / `kb-rag-web` /
> `kb-rag-deploy`）合并而成，各子项目的完整提交历史已一并保留。

## 架构图

```mermaid
flowchart TB
    subgraph clients["接入层"]
        WEB["kb-rag-web<br/>React 18 + AntD 5（dev :20002）"]
        AGENT["智能体应用<br/>REST + API Key"]
    end

    subgraph server["kb-rag-server（Java 17 / Spring Boot 3，:20000）"]
        API["kb-api<br/>HTTP 边界与装配点"]
        APP["kb-app<br/>应用编排（索引管线 / 检索链路 / 评测发布）"]
        DOMAIN["kb-domain<br/>实体 + 出站端口 + 领域算法"]
        INFRA["kb-infrastructure<br/>端口实现（引擎 / 存储 / 模型 Provider）"]
        API --> APP --> DOMAIN
        API --> INFRA --> DOMAIN
    end

    PARSER["kb-rag-parser<br/>Python 3.11 + FastAPI（:20001）<br/>文档解析 / 图片抽取 / 可选 OCR，不调用大模型"]

    subgraph middleware["中间件（docker-compose 一键拉起）"]
        MYSQL[("MySQL :13306<br/>唯一事实源")]
        ES[("Elasticsearch :9200<br/>BM25（lite 模式兼向量）")]
        QDRANT[("Qdrant :6333<br/>向量检索（full 模式）")]
        MINIO[("MinIO :9000<br/>原件 / 解析产物 / 归档")]
        NEO4J[("Neo4j :7687<br/>图检索（可选）")]
    end

    LLM["大模型服务（DashScope）<br/>嵌入 / 重排 / 对话 / 视觉<br/>零 Key 可降级运行"]

    WEB -- "/api（Vite 代理 / Nginx 反代）" --> API
    AGENT -- "/api/v1/knowledge/*" --> API
    INFRA -- "HTTP multipart" --> PARSER
    INFRA --> MYSQL & ES & QDRANT & MINIO & NEO4J
    INFRA --> LLM
```

- **kb-rag-server**：唯一业务中枢，负责管理台 API、对外开放 API、索引管线编排、检索链路与全部大模型调用。
- **kb-rag-parser**：纯解析微服务，只做文件解析与图片抽取，不调用任何大模型。
- **MySQL 是唯一事实源**：ES / Qdrant / Neo4j 均为派生索引，可从 MySQL 幂等重建。

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
更完整的架构说明与全量流程图（状态机、双写补偿、应用发布门禁等），见
[`../kb-rag-deploy/docs/ARCHITECTURE.md`](../kb-rag-deploy/docs/ARCHITECTURE.md) 与
[`../kb-rag-deploy/docs/FLOWS.md`](../kb-rag-deploy/docs/FLOWS.md)。

## 目录结构

| 子目录 | 职责 | 技术栈 |
| --- | --- | --- |
| [`kb-rag-server`](kb-rag-server/) | Java 主服务：知识库与文档生命周期、索引管线编排、检索融合、标注评测、应用发布、对外 API，以及全部大模型调用 | Java + Spring Boot + MyBatis |
| [`kb-rag-parser`](kb-rag-parser/) | Python 解析微服务：文档转结构化 Markdown + 按页文本 + 图片，聊天记录导出转结构化会话 | Python 3.11 + FastAPI |
| [`kb-rag-web`](kb-rag-web/) | React 管理台 | Vite + React 18 + TypeScript + Ant Design 5 |
| [`kb-rag-deploy`](kb-rag-deploy/) | 部署与契约：docker-compose 编排、环境变量模板、跨服务 OpenAPI 契约、备份与预检脚本、总体文档 | Docker Compose + Shell |

## 快速启动

```bash
cd kb-rag-deploy && cp .env.example .env
```

编辑 `.env` 填入数据库口令、MinIO 密钥与（可选的）`DASHSCOPE_API_KEY`，然后拉起中间件：

```bash
docker compose -f kb-rag-deploy/docker-compose.lite.yml up -d
```

完整的部署模式、资源要求矩阵、各里程碑接口契约与架构流程图，见
[`../kb-rag-deploy/README.md`](../kb-rag-deploy/README.md) 与 `../kb-rag-deploy/docs`。

## 安全说明

`.env` 已在 `../.gitignore` 中忽略，任何真实密钥、口令均不入库；提交前请以
`.env.example` 为模板，确保仅提交占位值。
