# kb-rag-server

企业级 RAG 知识库系统的 Java 主服务。负责知识库与文档的生命周期管理、索引管线编排、检索融合、标注与评测、应用发布与对外 API，以及全部大模型调用（嵌入 / 重排 / 对话 / 视觉四类能力统一走 Provider 抽象）。

文档解析由 Python 服务 `kb-rag-parser` 承担，本服务通过 HTTP 调用；管理台为 `kb-rag-web`；一键起环境与跨仓文档在 `kb-rag-deploy`。

## 模块划分

```
kb-rag-server            # parent，统一依赖版本
├── kb-common            # Result / ErrorCode / JsonUtil / HashUtil / 异常 / request_id 上下文（不依赖 Spring）
├── kb-domain            # 实体 + Mapper + 枚举 + 纯领域算法（切分、融合、指纹、评测指标、门禁裁决）+ 出站端口接口
├── kb-infrastructure    # 端口实现：Elasticsearch、Qdrant、Neo4j、MinIO、模型 Provider、parser 客户端、Webhook
├── kb-app               # 应用编排：kb / document / index / retrieval / graph / annotation / eval /
│                        #           appcenter / openapi / chat / alert / dict / auth / system / config
└── kb-api               # Controller + DTO + 过滤器 + 健康探针 + Flyway 脚本 + 启动类
```

依赖方向：

```
kb-api ──► kb-app ──► kb-domain ──► kb-common
   └─────► kb-infrastructure ──► kb-domain ──► kb-common
```

kb-infrastructure 实现 kb-domain 定义的端口接口，kb-app 只依赖端口接口、从不依赖 kb-infrastructure 的具体类，kb-api 是唯一的装配点（`@SpringBootApplication(scanBasePackages = "io.kbrag")`）。领域层不认识任何中间件 SDK。

## 功能总览

| 里程碑 | 交付内容 |
|---|---|
| M1 | 基线：知识库与文档 CRUD、异步索引管线、双路召回 + RRF 融合的检索 API、Provider 与引擎抽象、登录鉴权、统一响应体与 `request_id` 透传 |
| M2 | 检索链路成型：Query 改写、重排、加权融合、阈值语义、父子分片、`metadata_filter` 引擎侧下推、双写补偿、ik 词典管理与热更新 |
| M3 | 多模态与清洗：图片资产管线与 VLM 文本代理、扫描件整页识别、清洗与脱敏规则、解析预览与确认、聊天记录导入、告警 Webhook、Demo 一键导入 |
| M4a | 文档级版本管理（版本号规则、指纹复用、保留与归档、回滚模式）与分片标注（编辑 / 启停 / 合并 / 拆分 + 跨版本继承） |
| M4b | 评测体系：评测集与证据标注、证据复核工作台、评测任务与报告（Recall/Precision/Hit Rate/MRR/NDCG + Wilson 区间）、LLM-as-judge、LLM 语义切分 |
| M4c | 应用发布与开放能力：应用八状态机、发布门禁双跑与三态裁决、对外 `/api/v1/knowledge/{search,chat}`、API Key 与限流、调用审计与归档 |
| M5 | 多知识库路由：LLM 选库 + 白名单裁决、跨库 RRF、按权重切分 rerank 候选配额 |
| M6 | 索引快照发布：发布时冻结物理索引与版本可见集、回滚可恢复历史知识状态、归档保护与快照保留清理 |
| M7 | GraphRAG：实体 / 关系抽取入 Neo4j、图路作为库内第三路进 RRF、级联清理、图谱管理端点 |
| M8 | 导入与解析增强：聊天记录 TXT/HTML 格式、聊天聚合重叠滑窗与检索侧近重复归并、字段映射档案维护 |
| M9 | 标注语义与图搜：父片按偏移精确剔除禁用子片、标注跨版本相似度辅助迁移（对称 Dice）、图片 query |

## 环境要求

- JDK 17
- Maven 3.6+
- MySQL 8、Elasticsearch 8.x、MinIO（必需）
- Qdrant 1.18+（仅完整模式需要，轻量模式留空 `QDRANT_URI` 即可）
- Neo4j 5（仅 GraphRAG 需要，留空 `NEO4J_URI` 即整体关闭图能力，其余功能完全不受影响）

## 两种部署形态

| 形态 | `VECTOR_ENGINE` | 向量路 | 全文路 | 说明 |
|---|---|---|---|---|
| 轻量模式（默认） | `es` | Elasticsearch `dense_vector` kNN | 同一个 Elasticsearch 索引 | 最小依赖集，8GB 可跑 |
| 完整模式 | `qdrant` | Qdrant collection | 独立的 Elasticsearch BM25 索引 | 换嵌入模型不会连带重建全文索引 |

两种形态下向量分都会被换算为标准 cosine 再线性映射到 `[0,1]`，因此同一个相似度阈值在两种形态下语义一致。

## 零 Key 模式

不配置 `DASHSCOPE_API_KEY` 时服务照常启动，全链路可用：

- 索引管线跳过嵌入，分片 `embedding_status=SKIPPED`
- 只建全文索引，物理索引名的嵌入版本段取固定占位值 `none`
- 检索退化为 BM25 单路，响应 `degraded` 数组包含 `vector_route_unavailable`
- Query 改写与重排自动关闭。**关掉的阶段不算降级**，不会往 `degraded` 里塞标记——零 Key 是受支持的部署形态而不是故障；只有调用方显式要求某个阶段却没有对应模型时，才会返回 `query_rewrite_unavailable` / `rerank_unavailable`
- 阈值失去可比分数（BM25 原始分无上界），自动失效并返回 `threshold_inactive`
- `GET /api/v1/system/model-status` 返回四类模型各自的配置状态，管理台据此置灰依赖模型的功能

同一套装置也用在图能力与向量引擎上：`ModelProviderConfig` 是唯一读模型凭据的地方，凭据为空即注入 `Unconfigured*` 实现；`GraphStoreConfig`（`NEO4J_URI` 空 → `DisabledGraphStore`）与 `QdrantClientConfig`（`QDRANT_URI` 空 → 不建 client、不注册健康探针）镜像同一模式。上游代码只写 `isConfigured()` / `isEnabled()` 一个分支，全链路无 null 检查。

后续配置嵌入模型时走「建新物理索引 + 全量嵌入 + 别名原子切换」升级，不是原地改索引。

## 检索链路

固定次序，每个阶段可关但不可换位：

```
Query 改写 → 多库路由 → 双路/三路召回（子片粒度）→ 库内融合（RRF | 加权）→ 跨库 RRF
           → 近重复窗口归并 → 重排（候选 ≤50）→ 父子归并 → 阈值过滤 → top_n
```

- 单库调用就是「一个库的多库调用」：库内部分（召回、库内融合、事实源复核）按库跑一遍，之后的阶段在合并候选集上跑一次。管理台调试页与应用生产流量因此走同一份代码
- **改写与重排都有硬超时**（默认 800ms / 1500ms），超时或失败一律回退，检索本身永不失败
- **阈值只作用于跨查询可比的分数**：重排跑过时作用于重排分，否则作用于归一化 cosine，BM25 单路时失效并标注 `threshold_inactive`；`nodes[].score_type` 与顶层 `applied.threshold_applied_on` 说明这次实际作用在哪个分数上
- **父子分片开启后引擎只索引子片**，父片正文只存 MySQL，检索按 `parent_id` 归并（父片分 = 命中子片最高分）；父片中被禁用的子片按落库的字符偏移精确剔除并替换为固定省略标记，偏移缺失时整片回退返回
- **强制过滤不可绕过**：各路召回必须在引擎侧过滤版本可见集与 `enabled=1`，由链路构建、请求参数碰不到；图路回溯的 chunk 在 MySQL 事实源二次复核同一谓词
- **MySQL 是事实源**：正文一律从库里读，不从引擎读。引擎命中但事实源行已不存在 → 丢弃并异步自愈删除；行存在但被禁用 → 只从排序剔除，绝不删除

`degraded` 取值见 `DegradedReason` 枚举，共 12 个：`query_rewrite_timeout` / `query_rewrite_error` / `query_rewrite_unavailable`、`rerank_timeout` / `rerank_error` / `rerank_unavailable`、`vector_route_unavailable`、`route_fallback_all`、`threshold_inactive`、`snapshot_index_missing`、`graph_route_unavailable`、`image_understanding_unavailable`。

## 索引命名与发布快照

物理索引三段命名 `kb_{知识库ID}_{嵌入版本段}_{快照段}`，业务读写一律走别名 `kb_{知识库ID}_{engine}`，映射关系以 `t_kb_index_registry` 为准。

- 嵌入版本段：零 Key 取 `none`；完整模式的 Elasticsearch 索引取 `bm25`（换嵌入模型不抖动全文索引）；其余取嵌入模型缩写（`text-embedding-v4` → `tev4`）
- 快照段：实时索引固定 `v1`；应用发布时冻结的快照索引取 `s{seq}`（库级自增序列）

发布快照是唯一不挂别名的索引，按物理名直查。应用版本发布时在门禁裁决之后、RELEASED 生效之前，同时冻结物理索引与 `visible_version_ids` 版本可见集——只冻结索引不冻结可见集会导致回滚后召回全空。快照路径关闭孤儿自愈与双写补偿，避免按实时语义误删快照数据。

## ik 词典热更新

`/internal/dict/ik/{ext|stop}.txt` 免登录暴露词表，供 Elasticsearch 的 ik 插件轮询（`remote_ext_dict`）。ik 从 ES 进程内发起纯 HTTP 请求，无法携带 Bearer Token，所以这个路径在 `WebMvcConfig` 的拦截器排除列表里显式列出并注明理由；它只回运维手动录入的领域词，不含任何文档内容或配置。响应带 `Last-Modified` 与 `ETag`，未变更时返回 304，避免每次轮询都让所有 ES 节点重载词典。

词条本身通过 `/api/v1/dict/ik` 管理（新增 / 修改 / 启停 / 删除），落 `t_kb_ik_dict`。

## 关于中文分词

`content` 字段默认使用 `ik_max_word`（`ES_CONTENT_ANALYZER` 可改）。若 Elasticsearch 未安装 ik 插件，建索引时会自动回退到 `standard` 并打一条 info 日志。ik 插件安装步骤见 `kb-rag-deploy` 的 README。

## 配置

全部配置项通过环境变量注入，`application.yml` 只做映射，`kb.*` 各段由 `KbProperties`（`@ConfigurationProperties(prefix = "kb")`）承载。除数据库口令与 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` 外都带可用的本地默认值——对象存储凭据没有默认值是刻意的，缺失时启动阶段直接报错并指明要设哪两个变量，不会等到上传时才失败。

核心变量：

```bash
SERVER_PORT=20000                            # 应用端口，parser 为 20001
MYSQL_HOST=127.0.0.1  MYSQL_PORT=13306  MYSQL_DB=kb_rag  MYSQL_USER=kbrag  MYSQL_PASSWORD=
ES_URI=http://127.0.0.1:9200
QDRANT_URI=                                  # 轻量模式留空
NEO4J_URI=                                   # 留空即关闭 GraphRAG
MINIO_ENDPOINT=http://127.0.0.1:9000  MINIO_ACCESS_KEY=<必填>  MINIO_SECRET_KEY=<必填>  MINIO_BUCKET=kb-files
VECTOR_ENGINE=es                             # es | qdrant
DASHSCOPE_API_KEY=                           # 留空即零 Key 模式
EMBEDDING_MODEL=text-embedding-v4  EMBEDDING_DIM=1024
RERANK_MODEL=gte-rerank-v2                   # 留空 RERANK_API_KEY 即关闭重排
CHAT_MODEL=qwen-plus                         # 留空 CHAT_API_KEY 即关闭 Query 改写、路由、生成与评测 judge
VISION_MODEL=qwen-vl-max                     # 留空 VISION_API_KEY 即不生成图片文本代理
PARSER_BASE_URL=http://127.0.0.1:20001
CORS_ALLOWED_ORIGINS=http://localhost:20002  # 管理台地址
```

`kb.*` 的主要段落（完整清单见 `kb-api/src/main/resources/application.yml`，每个键都带一行说明为什么是这个默认值）：

| 段 | 管什么 |
|---|---|
| `kb.vector` / `kb.es` / `kb.qdrant` / `kb.minio` / `kb.graph` | 中间件连接与索引参数（分片副本数、分词器、预签名 TTL、图谱跳数与抽取并发） |
| `kb.embedding` / `kb.rerank` / `kb.chat` / `kb.vision` | 四类模型的 provider / 模型名 / 凭据 / 端点 / 超时 |
| `kb.parser` / `kb.upload` / `kb.image` | parser 地址与超时、上传体积与扩展名白名单、单文档图片上限 |
| `kb.split` / `kb.doc.version` | 切分参数（定长与父子两级）与文档版本保留数 |
| `kb.retrieval` | 检索全链路参数：RRF k、召回与 top_n 上下限、融合模式与权重、改写与重排开关及超时、多库上限与各类缓存、图片 query 上限 |
| `kb.eval` / `kb.gate` | 评测并发与离线超时、命中重叠阈值、judge 模型；门禁最小 case 数、容差、待复核占比上限 |
| `kb.app` / `kb.open-api` | 发布快照保留数与清理调度；对外 API 默认限流、审计保留天数与归档调度 |
| `kb.sync` / `kb.alert` / `kb.annotation` / `kb.chat-import` / `kb.demo` | 双写补偿扫描、告警窗口与阈值、标注迁移最低分、聊天导入默认档案与脱敏、Demo 素材目录 |

嵌入、对话与视觉调用走 OpenAI 兼容端点（`EMBEDDING_BASE_URL` / `CHAT_BASE_URL` / `VISION_BASE_URL` 默认 `https://dashscope.aliyuncs.com/compatible-mode/v1`），改这一个变量即可切到 Azure OpenAI、Ollama 或 vLLM。重排没有 OpenAI 兼容形态，走 DashScope 原生端点，因此 `RERANK_URL` 配的是完整 URL 而不是 base URL。

四类模型凭据各自独立：`RERANK_API_KEY` / `CHAT_API_KEY` / `VISION_API_KEY` 缺省回落到 `DASHSCOPE_API_KEY`，单独留空即可只关掉对应的那一类能力，链路自动跳过它而不报错。

密钥只从环境变量读取，不入代码也不入配置文件。

## 构建与运行

本项目需要 JDK 17 构建。若本机默认 `java` 不是 17，显式指定 `JAVA_HOME`：

```bash
export JAVA_HOME=/path/to/jdk17          # macOS 上例如 ~/Library/Java/JavaVirtualMachines/corretto-17.0.16/Contents/Home
mvn -B -ntp -DskipTests package
java -jar kb-api/target/kb-rag-server.jar
```

启动时 Flyway 自动执行迁移（当前 V1–V11，23 张业务表）。数据库中没有管理员账号时会创建 `admin` 并把随机密码打印到启动日志（只打印一次），首次登录强制改密。

跑测试：

```bash
mvn -B -ntp test          # 单测
mvn -B -ntp verify        # CI 跑的命令，等价于全量单测 + 打包
```

全量单测不依赖任何外部中间件，可离线执行。CI 配置见 `.github/workflows/ci.yml`（temurin 17 + `mvn -B -ntp verify`）。

## 接口

接口清单不在本文件维护，以 OpenAPI 契约为准：`kb-rag-deploy/docs/openapi/kb-server.yaml`（75 条路径 / 92 个操作）。契约先行——改动端点或 DTO 时先改 yaml 再改代码。

鉴权分两条完全独立的链路：

| 面 | 路径 | 凭据 | 拦截位置 |
|---|---|---|---|
| 管理台 | `/api/v1/**` | `Authorization: Bearer <token>`（登录签发，默认 24h，落 `t_kb_auth_token` 只存 SHA-256 摘要） | `WebMvcConfig` 拦截器 |
| 对外开放 | `/api/v1/knowledge/**` | `Authorization: Bearer kb-sk-***`（API Key，库里只存 SHA-256 摘要与展示前缀） | `ApiKeyAuthFilter` servlet 过滤器 |

免鉴权的只有 `/api/v1/auth/login`、`/internal/dict/ik/**` 与 `/actuator/**`。

统一响应体：成功 `{"code":"OK","message":"success","data":...,"request_id":"..."}`，失败 `{"code":"...","message":"...","request_id":"..."}`。`request_id` 在入口过滤器生成（可由 `X-Request-Id` 请求头指定），写入日志 MDC，透传给 parser 服务，并随异步线程池的 `TaskDecorator` 传到 worker 线程。

`/actuator` 的暴露白名单为 `health,info,prometheus`；健康探针含 MySQL、Elasticsearch、MinIO，配置了 Qdrant / Neo4j 时各自增加一项。指标端点需要额外引入 micrometer 的 Prometheus registry 才会真正出现，当前依赖里没有它，所以实际可用的是 `health` 与 `info`。

## 文档导航

跨仓文档统一放在 `kb-rag-deploy/docs/`：

| 文档 | 内容 |
|---|---|
| `ARCHITECTURE.md` | 四仓总体架构；§3 是本服务的模块、端口、检索链路、索引管线、异步与数据模型 |
| `FLOWS.md` | 端到端流程时序（上传入库、检索、发布、评测、导入） |
| `M1~M9-CONTRACTS.md` | 各里程碑的开发契约与「实现期修订」——实现与契约的偏离都记在这里 |
| `openapi/kb-server.yaml` | 本服务的 API 契约 |
| `openapi/kb-parser.yaml` | parser 服务的 API 契约 |
| `backup-restore.md` | 备份与恢复演练步骤、RPO/RTO |
| `知识库需求文档.md` | 需求唯一事实源 |

## 贡献与安全

- 贡献流程与自查清单见 [CONTRIBUTING.md](CONTRIBUTING.md)
- 漏洞报告渠道见 [SECURITY.md](SECURITY.md)，**请勿通过公开 Issue 报告安全漏洞**
- 第三方依赖与许可声明见 [NOTICE](NOTICE)
- 变更记录见 [CHANGELOG.md](CHANGELOG.md)

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。
