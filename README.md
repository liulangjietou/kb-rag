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
  - [启用 ik（M2）](#启用-ikm2)
- [压测（M2）](#压测m2)
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
`20000` / kb-rag-parser `20001` / kb-rag-web dev `20002`）。

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

安装后需要重建索引（mapping 的 analyzer 从 `standard` 切到 `ik_max_word`）。这条手工
路径功能上可用，但容器重建（升级/迁移环境）后插件会丢失、且没有自定义词典热更新
能力；生产环境建议改用下方「启用 ik（M2）」的可复现构建路径。

### 启用 ik（M2）

M2 起在 `es-ik/` 下提供固化的 Dockerfile（基于官方 Elasticsearch 镜像装
analysis-ik 插件，见 [`es-ik/Dockerfile`](es-ik/Dockerfile)）与
[`docker-compose.es-ik.yml`](docker-compose.es-ik.yml) override，容器重建不再
丢插件，并对接 kb-rag-server 的自定义词典热更新通道（`t_kb_ik_dict`，见
docs/M2-CONTRACTS.md §3）。这一层是**可选叠加**，不改动 lite/full 基线文件。

#### 构建与 `-f` 叠加用法

```bash
# lite 模式叠加 ik（-f 顺序：基线在前，override 在后）
docker compose -f docker-compose.lite.yml -f docker-compose.es-ik.yml up -d --build

# full 模式同样适用
docker compose -f docker-compose.yml -f docker-compose.es-ik.yml up -d --build
```

- `--build` 首次启用、或修改过 `es-ik/Dockerfile`/`IK_VERSION` 后必须带上；之后
  再执行 `up -d` 可省略，直接复用已构建的 `kb-rag-es-ik:8.11.4` 镜像
- 只覆盖 `elasticsearch` 一个服务：改用自建镜像并挂载
  [`es-ik/config/IKAnalyzer.cfg.xml`](es-ik/config/IKAnalyzer.cfg.xml)（`remote_ext_dict`/
  `remote_ext_stopwords` 指向 kb-rag-server 的热更新端点）；已存在的 `kb_rag_es_data`
  数据卷沿用不受影响
- 已建索引的 mapping analyzer 仍是 `standard`，切到 ik 镜像后需要重建索引才会真正
  生效（`POST /api/v1/documents/{docId}/reindex`，或 M2 的
  `POST /api/v1/kb/{kbId}/rebuild`；服务端 analyzer 探测已存在的 fallback 逻辑
  见 M2-CONTRACTS.md §3）
- 想回退到内置 `standard` 分词器：去掉 `-f docker-compose.es-ik.yml` 重新 `up -d`
  即可，ES 数据卷不受影响（回退不会删数据，但已用 `ik_max_word` 建的索引在
  standard 分词器下语义上不等价，回退前请评估）

#### macOS / Linux 的 `host.docker.internal` 差异

`IKAnalyzer.cfg.xml` 里的远程词典地址指向
`http://host.docker.internal:20000/internal/dict/ik/{ext|stop}.txt`（kb-rag-server
M2 阶段仍跑在宿主机 20000 端口，见 M1-CONTRACTS.md §0）：

- **macOS（Docker Desktop）**：`host.docker.internal` 由 Docker Desktop 内置 DNS
  自动解析到宿主机，开箱即用，无需任何额外配置
- **Linux（Docker Engine）**：`host.docker.internal` 默认不存在，
  `docker-compose.es-ik.yml` 已用 `extra_hosts: host.docker.internal:host-gateway`
  补齐（需 Docker Engine >= 20.10）；更早版本不支持 `host-gateway`，需把
  `es-ik/config/IKAnalyzer.cfg.xml` 中的 `host.docker.internal` 手动换成宿主机在
  容器网络里可达的实际内网 IP，并重新 `--build`

#### 验证词典热更新

```bash
# 1) 热更新端点本身可达（免登录，纯文本，见 M2-CONTRACTS.md §3）
curl -i http://127.0.0.1:20000/internal/dict/ik/ext.txt
# 期望：200，响应头含 Last-Modified / ETag，body 是一行一词的纯文本扩展词表

# 2) 管理台新增一个扩展词条（TOKEN 来自 POST /api/v1/auth/login）
curl -s -X POST http://127.0.0.1:20000/api/v1/dict/ik \
  -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -d '{"word":"知识库检索","dict_type":"EXT"}'

# 3) 再次拉取热更新端点：新词已出现，且 Last-Modified/ETag 已变化
curl -i http://127.0.0.1:20000/internal/dict/ik/ext.txt

# 4) ik 插件按内部轮询间隔（默认约 60s）自动重新加载，无需重启 ES 容器；
#    用 _analyze 直接验证新词是否已切成一个 token
curl -s -X POST http://127.0.0.1:9200/_analyze -H "Content-Type: application/json" \
  -d '{"analyzer":"ik_max_word","text":"知识库检索能力很强"}' | python3 -m json.tool
```

若第 4 步仍是旧的切分结果，等一个轮询周期后重试；持续不生效则 `docker logs
kb-rag-es` 排查 `remote_ext_dict` 拉取失败的日志（多数是上一小节的 host 解析问题）。

## 压测（M2）

```bash
KB_ID=<目标知识库 kb_id> TOKEN=<登录 token> ./scripts/benchmark.sh
```

对指定知识库并发跑检索请求（默认 200 次、并发 5，query 从内置 10 条中文查询轮换，
可用 `QUERY_FILE` 换成自定义语料），统计并输出 P50/P95/P99 延迟与错误数。
**M2 验收口径：基础链路 P95 < 2s**（见 docs/M2-CONTRACTS.md §7）。参数与退出码
说明见脚本头注释；服务未启动/`KB_ID`/`TOKEN` 有误时会给出明确报错而不是挂起或崩溃。

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

## 文档

- [知识库需求文档（v1.9，唯一事实源）](docs/知识库需求文档.md)
- [M1 开发契约](docs/M1-CONTRACTS.md) / [M2 开发契约](docs/M2-CONTRACTS.md)
- [OpenAPI 定义](docs/openapi/)
