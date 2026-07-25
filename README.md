# kb-rag-deploy

kb-rag 是一个可自托管、开箱即用的企业知识库 / RAG（检索增强生成）系统：上传文档 →
自动解析切分 → 双引擎混合检索（向量 + BM25）→ 标注与评测闭环 → 对外开放平台。
本仓库是四仓库中的**部署与契约仓**：docker-compose 编排、环境变量模板、跨服务
OpenAPI 接口契约、备份/预检脚本与总体文档。

一句话架构：**Java 主服务（检索/管理编排）+ Python 解析服务（文档转 Markdown）+
React 管理台，三者围绕 MySQL（事实源）/ Elasticsearch 与 Milvus（检索引擎）/
MinIO（对象存储）构建，全部通过 docker-compose 一键拉起中间件。**

> 本仓库当前状态：M1 里程碑（最小可用闭环：上传 → 索引 → 检索）。
> 完整契约见 [`docs/M1-CONTRACTS.md`](docs/M1-CONTRACTS.md)。

## 目录

- [快速启动](#快速启动)
- [四仓库说明](#四仓库说明)
- [部署模式与资源要求](#部署模式与资源要求)
- [环境变量](#环境变量)
- [中文分词（IK）](#中文分词ik)
- [备份与恢复](#备份与恢复)
- [接口契约（OpenAPI）](#接口契约openapi)
- [开源工程文档](#开源工程文档)

## 快速启动

推荐**轻量模式（lite）**：MySQL + Elasticsearch（同时承担 BM25 全文与向量检索）+
MinIO，8GB 内存即可跑起来，是本项目默认引导路径。

### 前置检查

```bash
git clone <this-repo> kb-rag-deploy && cd kb-rag-deploy
cp .env.example .env        # 按需修改 .env 中的密码，占位口令(CHANGE_ME_*)必须替换
./scripts/preflight.sh lite # 校验 docker / 内存 / 端口占用 / 是否还在用占位口令
```

### 路径一：零 Key 直接跑（无需任何模型 API Key）

不填 `DASHSCOPE_API_KEY` 即为零 Key 模式：系统正常启动，检索自动降级为 BM25 单路
（响应体 `degraded: ["vector_route_unavailable"]`），分片切分回退"按长度切分"，
依赖模型的界面项会置灰并提示"需配置模型 Provider"。这是本项目刻意设计的第一条
明路——**不需要任何账号、任何 Key，clone 下来就能看到"上传 → 检索"跑通**。

```bash
docker compose -f docker-compose.lite.yml up -d
docker compose -f docker-compose.lite.yml ps   # 确认 mysql/elasticsearch/minio 均 healthy
```

随后按 kb-rag-server / kb-rag-web 各自仓库的 README 启动应用层（M1 阶段应用直接跑在
宿主机，端口约定见 [`docs/M1-CONTRACTS.md` §0](docs/M1-CONTRACTS.md)：kb-rag-server
`8080` / kb-rag-parser `8001` / kb-rag-web dev `5173`）。

### 路径二：填 DASHSCOPE_API_KEY 获得全功能

在 `.env` 中设置：

```bash
DASHSCOPE_API_KEY=sk-xxxxxxxx        # 阿里云百炼平台申请
EMBEDDING_PROVIDER=dashscope
EMBEDDING_MODEL=text-embedding-v4
EMBEDDING_DIM=1024
```

之后重启应用层（无需重启中间件容器）即可获得向量检索能力：检索走
向量 + BM25 双路 → RRF(k=60) 融合 → top_n，`score_type` 会明确标注每个结果节点
来自 `cosine`（向量）还是 `bm25_rank`。

### 升级到完整模式（full）

需要独立向量引擎 Milvus 或多实例部署时的 Redis，切换到完整模式：

```bash
./scripts/preflight.sh full
docker compose -f docker-compose.yml up -d                     # 不含 redis
docker compose -f docker-compose.yml --profile redis up -d     # 含 redis（可选）
```

`docker-compose.yml` 通过 Compose `include` 复用 `docker-compose.lite.yml` 的
mysql/elasticsearch/minio 定义，额外叠加：

- **milvus-standalone**（v2.4.x）：自带独立 `etcd`（元数据）与独立 `milvus-minio`
  （向量分段对象存储），**不与应用侧 MinIO 混用**，避免两套业务的对象存储互相污染
- **redis:7.2.x**：标注 optional，默认不随 `up` 启动，需 `--profile redis` 显式开启；
  单实例部署无需 Redis（`cache.provider=local`，见需求文档 §5 Redis 职责边界）

lite → full 的索引迁移路径（切 `VECTOR_ENGINE=milvus` 后从 MySQL 事实源全量重建索引、
别名原子切换，重建期间 ES 持续服务）详见 `docs/M1-CONTRACTS.md` §3 与需求文档 §5。

## 四仓库说明

| 仓库 | 职责 | 技术栈 |
| --- | --- | --- |
| [kb-rag-server](../kb-rag-server) | Java 主服务：知识库/文档/索引管线编排、混合检索、管理台 API | JDK 17、Spring Boot 3.3.x、MyBatis-Plus、Flyway |
| [kb-rag-parser](../kb-rag-parser) | Python 文档解析服务：pdf/docx/txt/md/xlsx/csv → Markdown | Python 3.11、FastAPI |
| [kb-rag-web](../kb-rag-web) | 前端管理台：登录、知识库管理、检索调试 | React 18、TypeScript、Vite、Ant Design 5 |
| **kb-rag-deploy**（本仓库） | docker-compose、环境变量模板、OpenAPI 接口契约、部署文档 | docker-compose |

## 部署模式与资源要求

| 模式 | 中间件 | 内存要求 | 适用场景 |
| --- | --- | --- | --- |
| lite（轻量，默认） | MySQL + Elasticsearch（BM25+向量双职责） + MinIO | 约 **8GB** | 本地开发、小规模自托管、开源试用第一印象 |
| full（完整） | lite 全部 + Milvus standalone（独立 etcd/minio） + Redis（可选） | 建议 **24GB**（中间件约 12GB + kb-rag-parser 8GB） | 独立向量引擎、多实例部署 |

kb-rag-parser 另分 CPU 档（最低 8GB 内存，100 页解析 SLA < 30min）与 GPU 档
（≥8GB 显存，100 页 < 5min），详见需求文档 §5 资源要求矩阵；kb-rag-parser 的
containerize 与内存限制配置随后续里程碑（kb-rag-parser 仓库自带 Dockerfile）落地，
不在本仓库 M1 交付范围内。

## 环境变量

复制 `.env.example` 为 `.env` 并按注释修改。文件分两部分：

1. **应用侧契约变量**（`docs/M1-CONTRACTS.md` §1）：kb-rag-server / kb-rag-parser 直接消费，
   例如 `MYSQL_HOST` / `ES_URI` / `MILVUS_URI`（lite 模式可空） / `DASHSCOPE_API_KEY`
   （可空 = 零 Key 模式）
2. **docker-compose 专用变量**：仅供中间件容器初始化使用（如 `MYSQL_ROOT_PASSWORD`、
   各服务端口、Milvus 专属 MinIO 凭据、备份与预检相关配置）

所有密码类变量默认值均为 `CHANGE_ME_*` 占位符，**`scripts/preflight.sh` 会在启动前
拦截仍在使用占位口令的情况**，这是本项目防御式编程的唯一拦截点（避免在
compose 文件、应用代码、运维脚本里多处重复校验）。

## 中文分词（IK）

M1 阶段 Elasticsearch 使用内置 `standard` 分词器起步（英文/数字友好，中文按字切分，
召回率有限）。生产使用中文语料前，建议安装 IK 分词插件：

```bash
docker exec -it kb-rag-es elasticsearch-plugin install \
  https://get.infini.cloud/elasticsearch/analysis-ik/8.11.4
docker restart kb-rag-es
```

安装后需要重建索引（mapping 的 analyzer 从 `standard` 切到 `ik_max_word`）。
自定义词典热更新（DB 为源同步到 ES，容器重建不丢词条）是 M2 落地项，见需求文档 §6
`t_kb_ik_dict`。

## 备份与恢复

```bash
./scripts/backup.sh              # mysqldump 全量 + MinIO 数据卷全量导出，按份数轮转
```

- 备份产物落地 `.env` 中的 `BACKUP_DIR`（默认 `./backups`），按 `BACKUP_KEEP_COUNT`
  （默认 7）滚动清理旧备份
- 建议用 cron 每日调度（RPO 目标 ≤24h），示例见 `scripts/backup.sh` 文件头注释
- **恢复顺序**：MySQL → MinIO → 触发"从事实源重建索引"（kb-rag-server 管理台功能，
  M6 里程碑）
- RTO 不设硬指标，但需实测端到端恢复时长并写入部署文档（见需求文档 §5，M6 验收含一次
  "备份-删库-恢复-检索可用"演练）

## 接口契约（OpenAPI）

- [`docs/openapi/kb-server.yaml`](docs/openapi/kb-server.yaml)：kb-rag-server 管理台
  内部 API（登录、知识库、文档、检索调试、系统状态），含 `RetrievalNode`、统一错误响应、
  `degraded` 降级枚举
- [`docs/openapi/kb-parser.yaml`](docs/openapi/kb-parser.yaml)：kb-rag-parser 解析
  服务 API

本地校验语法：

```bash
python3 -c "import yaml, sys; yaml.safe_load(open(sys.argv[1]))" docs/openapi/kb-server.yaml
python3 -c "import yaml, sys; yaml.safe_load(open(sys.argv[1]))" docs/openapi/kb-parser.yaml
```

对外 API Key 开放平台网关是后续里程碑（M4c）范畴，不在本文件中。

## 开源工程文档

- [LICENSE](LICENSE)（Apache-2.0）
- [NOTICE](NOTICE)：第三方依赖许可声明（MySQL / Elasticsearch / Milvus / MinIO /
  Redis / MinerU 预留 / PaddleOCR 预留）
- [SECURITY.md](SECURITY.md)：漏洞报告渠道
- [CONTRIBUTING.md](CONTRIBUTING.md)：分支模型、提交规范、PR 自查清单
- [CHANGELOG.md](CHANGELOG.md)
- [docs/M1-CONTRACTS.md](docs/M1-CONTRACTS.md)：M1 各仓库实现的唯一共同契约
