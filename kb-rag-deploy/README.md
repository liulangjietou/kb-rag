# kb-rag-deploy

kb-rag 是一个可自托管、开箱即用的企业知识库 / RAG（检索增强生成）系统：上传文档 →
自动解析切分 → 双引擎混合检索（向量 + BM25）→ 标注与评测闭环 → 对外开放平台。
本仓库是四仓库中的**部署与契约仓**：docker-compose 编排、环境变量模板、跨服务
OpenAPI 接口契约、备份/预检脚本与总体文档。

一句话架构：**Java 主服务（检索/管理编排）+ Python 解析服务（文档转 Markdown）+
React 管理台，三者围绕 MySQL（事实源）/ Elasticsearch 与 Qdrant（检索引擎）/
MinIO（对象存储）/ Neo4j（可选，图检索）构建，全部通过 docker-compose 一键拉起中间件。**

> 本仓库当前状态：**M1-M21 已实现**；其中 M15/M16 为权限与企业化，M17/M18 为网页抓取增强，
> M19 为 Agent 长期记忆，M20 为 MCP 协议层，M21 为最终答案质量评测与发布门禁。
> 一期交付上传解析→混合检索（向量+BM25+图路三路融合）→分片标注→评测闭环→应用发布→
> 多知识库路由→索引快照回滚→GraphRAG 的完整链路；二期增强聊天记录导入
> （TXT/HTML 格式、本地 OCR 兜底、重叠滑窗归并、映射档案维护界面，M8）与标注
> 语义/图搜能力（M9）；核心能力增强补齐检索质量闭环（M10）、内容治理（M11）、
> URL 导入与增量同步（M12）、Prometheus 业务指标（M13）；M14 对齐竞品能力，补齐
> 外部数据源连接器、配置化元数据抽取、切分策略扩展、Rerank 混合模式、视觉理解整页
> 索引与以图搜图六项特性，见下文
> [核心能力增强（M10-M13）](#核心能力增强m10-m13)与
> [竞品能力对齐（M14）](#竞品能力对齐m14)。
> 各里程碑契约见 [`docs/M1-CONTRACTS.md`](docs/M1-CONTRACTS.md) 至
> [`docs/M21-CONTRACTS.md`](docs/M21-CONTRACTS.md)；系统整体架构与核心流程图见
> [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 与 [`docs/FLOWS.md`](docs/FLOWS.md)。

## 目录

- [快速启动](#快速启动)
- [四仓库说明](#四仓库说明)
- [部署模式与资源要求](#部署模式与资源要求)
- [环境变量](#环境变量)
- [中文分词（IK）](#中文分词ik)
  - [启用 ik（M2）](#启用-ikm2)
- [压测（M2）](#压测m2)
- [多知识库路由（M5）](#多知识库路由m5)
- [应用发布与索引快照回滚（M6）](#应用发布与索引快照回滚m6)
- [备份与恢复（M6）](#备份与恢复m6)
- [压测种子数据（M6）](#压测种子数据m6)
- [GraphRAG 知识图谱（M7，可选）](#graphrag-知识图谱m7可选)
- [Demo 数据集与聊天记录映射（M3）](#demo-数据集与聊天记录映射m3)
- [聊天记录格式扩展与映射维护（M8）](#聊天记录格式扩展与映射维护m8)
- [核心能力增强（M10-M13）](#核心能力增强m10-m13)
- [竞品能力对齐（M14）](#竞品能力对齐m14)
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

需要独立向量引擎 Qdrant 或多实例部署时的 Redis，切换到完整模式：

```bash
./scripts/preflight.sh full
docker compose -f docker-compose.yml up -d                     # 不含 redis
docker compose -f docker-compose.yml --profile redis up -d     # 含 redis（可选）
```

`docker-compose.yml` 通过 Compose `include` 复用 `docker-compose.lite.yml` 的
mysql/elasticsearch/minio 定义，额外叠加：

- **qdrant**（v1.18.x）：单容器自带存储，数据落在 `kb_rag_qdrant_data` 卷的
  `/qdrant/storage`，不依赖额外的元数据服务或对象存储，也不占用应用侧 MinIO
- **redis:7.2.x**：标注 optional，默认不随 `up` 启动，需 `--profile redis` 显式开启；
  单实例部署无需 Redis（`cache.provider=local`，见需求文档 §5 Redis 职责边界）

lite → full 的索引迁移路径（切 `VECTOR_ENGINE=qdrant` 后从 MySQL 事实源全量重建索引、
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
| full（完整） | lite 全部 + Qdrant（单容器，自带存储） + Redis（可选） | 建议 **16GB**（中间件约 4GB + kb-rag-parser 8GB） | 独立向量引擎、多实例部署 |
| + graph（可选叠加，M7） | 在 lite 或 full 基础上 `--profile graph` 追加 Neo4j 5 | 额外约 **1GB**（512MB heap + 256MB pagecache，默认值可调） | 需要 GraphRAG 图检索能力时按需开启，不影响基线资源承诺 |

kb-rag-parser 另分 CPU 档（最低 8GB 内存，100 页解析 SLA < 30min）与 GPU 档
（≥8GB 显存，100 页 < 5min），详见需求文档 §5 资源要求矩阵；kb-rag-parser 的
containerize 与内存限制配置随后续里程碑（kb-rag-parser 仓库自带 Dockerfile）落地，
不在本仓库 M1 交付范围内。

## 环境变量

复制 `.env.example` 为 `.env` 并按注释修改。文件分两部分：

1. **应用侧契约变量**（`docs/M1-CONTRACTS.md` §1）：kb-rag-server / kb-rag-parser 直接消费，
   例如 `MYSQL_HOST` / `ES_URI` / `QDRANT_URI`（lite 模式可空） / `DASHSCOPE_API_KEY`
   （可空 = 零 Key 模式） / `NEO4J_URI`（可空 = 不使用图能力，M7）
2. **docker-compose 专用变量**：仅供中间件容器初始化使用（如 `MYSQL_ROOT_PASSWORD`、
   各服务端口、Qdrant 专属 MinIO 凭据、备份与预检相关配置）

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
**验收口径（需求文档 §5，M2 定版）：基础链路（多路召回+融合+重排，不含改写）
P95 < 2s、完整链路（含 Query 改写）P95 < 3s**（见 docs/M2-CONTRACTS.md §7）。参数与
退出码说明见脚本头注释；服务未启动/`KB_ID`/`TOKEN` 有误时会给出明确报错而不是挂起或崩溃。

## 多知识库路由（M5）

应用版本可挂 1..15 个知识库（`kb_refs`，含配额权重，正整数默认 1，
`kb.retrieval.max-linked-kb` 控制挂载上限）；旧版仅存单 `kb_id` 的快照读侧兼容翻译，
无需迁移。

- 路由开关开启且应用挂 ≥2 库时，`RoutingService` 调用 ChatProvider 做 LLM 选库，
  输出与候选知识库白名单求交集（注入防护③）；空交集/解析失败/超时/未配置对话模型
  一律降级为检索全部关联库并记 `degraded=route_fallback_all`；决策结果按
  query+候选集哈希缓存
- 跨库检索基于库内排名做 Reciprocal Rank Fusion 合并（只用名次不用分数）；rerank
  候选总预算（全局默认 50，非每库）按 `kb_refs` 权重比例分配到各库，向下取整、
  余量归权重最高库
- 对外/管理 `search`、`chat`、`chat-preview` 响应新增 `routed_kb_ids`
  （`applied` 信息条或顶层，SSE `done` 事件同增）与 `RetrievalNode.metadata.kb_id`

详见 [`docs/M5-CONTRACTS.md`](docs/M5-CONTRACTS.md)。

## 应用发布与索引快照回滚（M6）

应用版本发布门禁通过（或 force 放行）之后、状态切 `RELEASED` 之前，对关联知识库的
物理索引执行一次**不可变快照**（ES `_clone`：源索引临时置只读 → clone → 两端解锁，
段级硬链接毫秒级完成；Qdrant 为同步批量拷贝），并固化当时的版本可见集
`visible_version_ids`。

- 经 `RELEASED` 版本发起的对外调用（含 rollback 重新发布的历史版本）固定检索这份
  快照与固化可见集，**回滚即刻恢复历史知识状态**；`TESTING` 灰度/chat-preview/
  管理台调试/评测仍走实时别名与当前激活集合，不受快照影响
- 快照索引被误删时降级为实时别名检索，并记 `degraded=snapshot_index_missing`
- 按应用保留最近 3 个 `SUPERSEDED` 版本快照，超出的由定时任务清理物理索引并解除
  归档保护（pin）；`RELEASED` 快照永不清理
- **验收口径（M6-CONTRACTS.md §4⑦）**：灌入 10 万分片种子数据后按 M2 同口径重跑
  压测，P95 由 100 分片基线的 33.7ms 劣化至 39.0ms（**+15.9%**），未超过 **≤20%**
  的验收阈值

详见 [`docs/M6-CONTRACTS.md`](docs/M6-CONTRACTS.md)。

## 备份与恢复（M6）

```bash
./scripts/backup.sh                                   # mysqldump 全量 + ES 快照(kb_*) + MinIO 镜像
./scripts/restore.sh ./backup/<UTC 时间戳> [--yes]     # 按 MySQL -> ES -> MinIO 顺序原地恢复
```

- 备份产物落地 `.env` 中的 `BACKUP_DIR`（默认 `./backup`）下的 `<UTC 时间戳>/` 子目录，
  含 `mysql/*.sql.gz` + `es-snapshot.json` + `minio/<bucket>/` + `manifest.json`
  （三段各自 status 与体积），按 `BACKUP_KEEP_COUNT`（默认 7）滚动清理旧的时间戳目录
- ES 快照走 `_snapshot` API，物理数据落 compose 挂载的共享仓库目录 `./backup/es-repo`
  （对应 ES 容器 `path.repo`），离线归档需把整个 `./backup` 目录一起搬走
- 建议用 cron 每日调度（RPO 目标 ≤24h），示例见 `scripts/backup.sh` 文件头注释
- **恢复顺序**：MySQL → Elasticsearch（先删 `kb_*` 索引再按快照 `_restore`）→ MinIO
  （`mc mirror --remove` 逆向镜像）；`restore.sh` 恢复前交互确认（`--yes` 跳过），
  恢复后打印验证提示
- RTO 不设硬指标，但需实测端到端恢复时长并写入部署文档；完整的 RPO 说明、脚本参数、
  恢复演练步骤清单见 [`docs/backup-restore.md`](docs/backup-restore.md)（M6 验收⑥
  "备份-删库-恢复-检索可用"演练照此文档执行）

## 压测种子数据（M6）

```bash
python3 scripts/seed-bench.py                 # 零 Key 灌入 10 万分片种子知识库（幂等）
KB_ID=kb_benchseed TOKEN=<登录 token> ./scripts/benchmark.sh   # 复用 M2 压测脚本
python3 scripts/seed-bench.py --clean-only    # 压测结束后清理
```

`scripts/seed-bench.py` 直写 MySQL（`t_kb_knowledge_base`/`t_kb_document`/
`t_kb_document_version`/`t_kb_chunk`）+ Elasticsearch bulk（零 Key 三段命名
`kb_{kbId}_none_v1` 物理索引 + `kb_{kbId}_es` 别名），供 10 万分片规模压测复用，
不依赖模型 Key。参数、依赖说明与验收口径见
[`docs/backup-restore.md`](docs/backup-restore.md) 第 4 节。

## GraphRAG 知识图谱（M7，可选）

可选能力，**默认不开启**：compose 需显式加 `--profile graph` 才会启动 Neo4j，
应用侧 `NEO4J_URI` 留空即代表不使用图能力，两者相互独立、互不阻塞，不影响
lite 8GB / full 16GB 的资源承诺。

```bash
# lite 模式启用 Neo4j（full 模式同样适用，替换 -f 的 compose 文件即可）
docker compose -f docker-compose.lite.yml --profile graph up -d
```

之后在 `.env` 中设置 `NEO4J_URI=bolt://localhost:7687`（及 `NEO4J_USER`/
`NEO4J_PASSWORD`）并重启应用层即可启用图能力：

- 知识库级实体/关系抽取（逐分片 LLM 抽取 JSON、输出强校验，非法项计入
  `t_kb_task.skipped_count`）写入 Neo4j：`(:Entity)-[:REL]->(:Entity)` +
  `(:Entity)-[:MENTIONED_IN]->(:Chunk)` 溯源边；Neo4j 为**可从 MySQL 全量重建的
  派生存储**，文档/知识库删除会级联清理图数据
- 检索侧作为库内第三路进 RRF：query 轻量切词 → Neo4j 实体名 fulltext（cjk 分析器）
  匹配 → N 跳扩展（默认 2）→ 溯源回 chunk，关联度 = 匹配分/(1+跳数)；回溯的 chunk
  仍需回 MySQL 事实源二次校验版本可见集与 `enabled`（图路不击穿版本隔离）；开启
  图路的库，库内融合强制走 RRF（与加权融合互斥）
- Neo4j 未配置/不可达时降级 `degraded=graph_route_unavailable`；快照上下文
  （经 `RELEASED` 版本调用）图路直接关闭且不计降级（能力边界而非故障）
- 管理端提供实体/关系抽取触发、抽取概要、实体列表与实体关联分片查询五个端点，
  以及知识图谱可视化页（前端零依赖 SVG 力导向布局）

**抽取吞吐（M16 起）**：抽取按无栅栏流水线跑，`GRAPH_EXTRACT_CONCURRENCY`（默认 8）
是一次抽取任务内并发进行的 LLM 调用数——抽取耗时几乎全在模型调用上，这个值直接决定
一次全量抽取跑多久（一万分片按每次 3 秒算，并发 2 是四个多小时，并发 8 是一小时出头）。
图写入由单写入者线程串行执行，调高并发不影响 Neo4j 的正确性，只需看模型侧限流吃不吃得住；
同时进行的抽取任务数受独立线程池上限（2）约束，所以**模型侧峰值并发 = 2 × 该值**（默认 16）。
`GRAPH_EXTRACT_BATCH_SIZE` 已随栅栏机制一同移除（存量 `.env` 里留着不报错也不生效）。

详见 [`docs/M7-CONTRACTS.md`](docs/M7-CONTRACTS.md) 与
[`docs/M16-CONTRACTS.md`](docs/M16-CONTRACTS.md) §4.3。

## Demo 数据集与聊天记录映射（M3）

- [`demo/`](demo/)：开箱即用的 Demo 文档集（pdf/docx/xlsx/md 各一，原创 RAG/知识库
  技术说明）+ `manifest.json`（建议 query）+ `eval-cases.json`（示例评测集，本期只
  分发、导入功能见 M4b）+ `tools/generate_demo_docs.py`（docx/pdf/xlsx 可复现生成
  脚本）。详见 [`demo/README.md`](demo/README.md)。`DEMO_DATA_DIR` 环境变量指向本
  目录（容器内默认 `/opt/kb-rag/demo`），供 `POST /api/v1/system/demo/import`
  一键导入使用（`docs/M3-CONTRACTS.md` §3.7）。
- [`mappings/chat/memotrace.yml`](mappings/chat/memotrace.yml)：聊天记录（微信
  「留痕」/MemoTrace 类工具导出）列名映射模板，配合 kb-rag-parser
  `POST /api/v1/parse/chat` 的 `mapping_profile` 参数使用；如何为新来源新增映射
  档案见 [`mappings/README.md`](mappings/README.md)。
- M3 新增环境变量（`.env.example`）：`VISION_MODEL`/`VISION_TIMEOUT_MS`（图片理解
  VisionProvider 配置）、`SCANNED_PAGE_TEXT_THRESHOLD`（扫描页判定阈值）、
  `MAX_IMAGES_PER_DOC`（单文档图片数上限）、`DEMO_DATA_DIR`；后补
  `IMAGE_DESCRIBE_CONCURRENCY`（单文档图片描述并发度，默认 8，见
  `docs/M3-CONTRACTS.md` §7.6）。

**索引吞吐调优（M16 起）**：文档索引是多级并发串起来的，乘起来才是上游服务看到的量。
`INDEX_CONCURRENCY`（默认 4）决定同时索引几个文档；每个文档内部的嵌入批次再按
`EMBEDDING_CONCURRENCY`（默认 4，**全局**上限）并发——一个批次就是一次嵌入请求，500 个分片按
批大小 10 算是 50 次往返，串行跑是索引里最长的一段等待。

调参前先认清三个**不随机器变大而变大**的天花板：**模型侧限流**（撞上的表现是任务失败率上升
而不是变慢）、**`PARSER_MAX_WORKERS`**（解析是真 CPU 密集且 uvicorn 单进程，把
`INDEX_CONCURRENCY` 调到远超它只是把队列挪到 parser 门口）、**`MYSQL_POOL_SIZE`**（不同步扩
就是把瓶颈换成 connection timeout）。10 核 / 64GB 单机的一套参考值见
[`docs/M16-CONTRACTS.md`](docs/M16-CONTRACTS.md) §4.5。

## 聊天记录格式扩展与映射维护（M8）

在 M3 的 CSV/Excel 基础上，聊天记录导入新增 **TXT / HTML** 两种格式
（`file_ext=txt|html`），复用既有两步式导入（preview → confirm）。

- TXT 内置两种行模板（留痕/MemoTrace 风格的换行式、微信 PC 端风格的同行式），
  HTML 内置留痕导出的 DOM 选择器模板（仅标准库解析，剥离 `script`/`style`，
  不加载任何远程资源）；两者均按公开约定编写，**真实导出样例到位后再校准**
  （见 `docs/M8-CONTRACTS.md` §5），行首正则/DOM 选择器均可通过映射档案自定义
- 不匹配任何内置/自定义模板的行数占比 > 30% 时判定解析失败并报可操作错误，
  避免拿错格式静默产出垃圾分片
- 映射档案不再只是仓库内静态文件：管理台"系统设置 → 导入映射"tab 提供 CRUD
  （新建/编辑/复制内置模板/删除自定义），后端 `t_kb_source_mapping` 表启动时从
  仓库内置模板种子化（幂等，只补缺不覆盖，`is_builtin=true` 的内置模板不可删）；
  `mappings/chat/memotrace.yml` 等本地 yml 文件继续作为内置模板的种子来源
- 聊天聚合新增重叠滑窗 `window_overlap`（消息数，默认 0=兼容既有顺切逻辑）；
  检索侧对同一会话且消息区间 `msg_span` 重叠率 ≥0.5 的命中做近重复归并（只保留
  排名最高者，被并者进 `metadata.merged_window_chunk_ids` 供调试页展示）
- kb-rag-parser 新增可选本地 OCR 兜底：`OCR_ENGINE=none|paddle`（默认 `none`，
  `.env.example` 同名变量）；扫描页三级次序为 server 侧 VLM（有 Key）→ parser 侧
  PaddleOCR（`OCR_ENGINE=paddle` 且已装 `requirements-ocr.txt`）→ 跳过并降级；
  未装依赖时设为 `paddle` 会在 parser 启动时 fast-fail 并给出安装指引

详见 [`docs/M8-CONTRACTS.md`](docs/M8-CONTRACTS.md)。

## 核心能力增强（M10-M13）

四个里程碑均为纯新增能力，无端点行为变化；契约见 `docs/M10~M13-CONTRACTS.md`：

- **M10 检索质量闭环**：检索反馈持久化与转评测集、检索洞察（脱敏摘要/零命中/
  降级标记）与内容缺口报表；新增 `INSIGHT_ENABLED` / `INSIGHT_RETENTION_DAYS` /
  `INSIGHT_CLEANUP_BATCH_SIZE` / `INSIGHT_CLEANUP_CRON`
- **M11 内容治理**：库级审核开关与 DRAFT→PENDING_REVIEW→PUBLISHED|REJECTED 状态机、
  文档有效期窗口、回收站（trash/restore/purge + 保留期自动清除）；新增
  `TRASH_RETENTION_DAYS` / `TRASH_PURGE_BATCH_SIZE` / `TRASH_PURGE_CRON` / `TRASH_PURGE_ENABLED`；
  注意 `DELETE /documents/{docId}` 语义已改为移入回收站（见 CHANGELOG 醒目提示）
- **M12 URL 导入与增量同步**：网页登记即抓、定时增量同步（四态结果、hash 去重）、
  SSRF 防线；新增 `WEB_IMPORT_*` 六个变量；注意 `UPLOAD_ALLOWED_EXTENSIONS` 默认值已含 `html`
- **M13 Prometheus 业务指标**：默认从独立回环管理端口
  `127.0.0.1:20003/actuator/prometheus` 抓取 `kb_search_seconds` /
  `kb_task_completed_total` / `kb_task_backlog` / `kb_openapi_rejected_total` /
  `kb_websource_sync_total` 及 JVM/HTTP 基础指标；管理端口与 `20000` 业务端口分离，健康响应
  不暴露组件详情。远程抓取必须显式修改管理地址并配置网络访问控制，见
  [`docs/ACTUATOR-SECURITY.md`](docs/ACTUATOR-SECURITY.md)

## 竞品能力对齐（M14）

六项特性均为纯新增能力，向后兼容既有端点；契约见 [`docs/M14-CONTRACTS.md`](docs/M14-CONTRACTS.md)：

- **外部数据源连接器**：新增 `ext-source` 端点族（登记/列表/同步/条目/编辑/连通性测试/移除），
  首个实现为 S3/OSS 兼容对象存储，登记后异步首同步并支持增量（hash 去重、四态条目结果）；
  凭证 `secret_key` 恒以掩码返回、编辑留空即保留旧值。新增 `EXT_SOURCE_*` 相关变量
- **配置化元数据抽取**：索引配置新增 `metadata_rules`（constant / regex / keyword_match 三类，
  单库至多 10 条），抽取值随分片指纹参与陈旧判定；检索过滤新增 `custom` 等值过滤
- **切分策略扩展**：`split_strategy` 由 `fixed_length` / `llm_semantic` 扩展至
  `separator` / `heading` / `page`，配套 `split_separator` / `split_separator_is_regex` /
  `split_heading_level` 条件字段
- **Rerank 混合模式**：检索请求新增 `rerank_mode`（`semantic` / `hybrid`）与
  `rerank_w_semantic`（0-1，仅 `hybrid` 生效），语义分与原始融合分线性加权
- **视觉理解整页索引**：索引配置新增 `multimodal_enabled`，开启后整页走多模态向量
  （DashScope multimodal-embedding-v1）；新增 `MULTIMODAL_*` 相关变量与
  `mm_route_skipped` / `mm_route_unavailable` 降级码
- **以图搜图入口**：检索请求新增 `images`（至多 3 张裸 base64），走多模态向量近邻检索

以上六项新增约 12 个环境变量，详见 [`.env.example`](.env.example) 中 M14 分节与
模型状态接口的 `multimodal_configured` 字段。

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

对外 API Key 开放平台网关（应用发布、API Key 管理、限流、审计）已随 M4c 交付，
契约详见 [`docs/M4c-CONTRACTS.md`](docs/M4c-CONTRACTS.md)，端点已同步进
`docs/openapi/kb-server.yaml`。

## 开源工程文档

- [LICENSE](LICENSE)（Apache-2.0）
- [NOTICE](NOTICE)：第三方依赖许可声明（MySQL / Elasticsearch / Qdrant / MinIO /
  Neo4j / Redis / MinerU 预留未集成 / PaddleOCR）
- [SECURITY.md](SECURITY.md)：漏洞报告渠道
- [CONTRIBUTING.md](CONTRIBUTING.md)：分支模型、提交规范、PR 自查清单
- [CHANGELOG.md](CHANGELOG.md)
- [UPGRADING.md](UPGRADING.md)：升级指引（镜像 tag、Flyway 迁移、ES/Qdrant schema
  变更、备份先行）
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)：系统架构总览（组件拓扑、领域端口、
  检索链路、数据模型）
- [docs/FLOWS.md](docs/FLOWS.md)：核心流程图（上传索引、检索、发布快照、图路等）
- [docs/M1-CONTRACTS.md](docs/M1-CONTRACTS.md)：M1 各仓库实现的唯一共同契约

## 文档

- [知识库需求文档（v1.14，唯一事实源）](docs/知识库需求文档.md)
- [系统架构总览（ARCHITECTURE.md）](docs/ARCHITECTURE.md) /
  [核心流程图（FLOWS.md）](docs/FLOWS.md)
- [M1](docs/M1-CONTRACTS.md) ~ [M21 开发契约](docs/M21-CONTRACTS.md)（按里程碑记录
  实现细节与已接受偏离）
- [OpenAPI 定义](docs/openapi/)
