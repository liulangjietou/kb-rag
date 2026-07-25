# kb-rag-server

企业级 RAG 知识库系统的 Java 主服务。负责知识库与文档的生命周期管理、索引管线编排、检索融合，以及全部大模型调用（嵌入 / 重排 / 对话 / 视觉四类能力统一走 Provider 抽象）。

文档解析由 Python 服务 `kb-rag-parser` 承担，本服务通过 HTTP 调用；管理台为 `kb-rag-web`；一键起环境与跨仓文档在 `kb-rag-deploy`。

## 模块划分

```
kb-rag-server            # parent，统一依赖版本
├── kb-common            # Result / ErrorCode / JsonUtil / 异常 / request_id 上下文
├── kb-domain            # 实体 + Mapper + 领域服务（切分、token 估算、哈希、RRF、索引命名）+ 出站端口接口
├── kb-infrastructure    # 端口实现：Elasticsearch、Milvus、MinIO、DashScope、parser 客户端
├── kb-app               # 应用服务：鉴权、知识库、文档、索引管线、检索
└── kb-api               # Controller + DTO + 过滤器 + 健康探针 + Flyway 脚本 + 启动类
```

依赖方向为 `api → app → domain ← infrastructure`：infrastructure 实现 domain 定义的端口接口，api 负责组装。领域层不认识任何中间件 SDK。

## 环境要求

- JDK 17
- Maven 3.6+
- MySQL 8、Elasticsearch 8.x、MinIO（必需）
- Milvus 2.4+（仅完整模式需要，轻量模式留空 `MILVUS_URI` 即可）

## 两种部署形态

| 形态 | `VECTOR_ENGINE` | 向量路 | 全文路 | 说明 |
|---|---|---|---|---|
| 轻量模式（默认） | `es` | Elasticsearch `dense_vector` kNN | 同一个 Elasticsearch 索引 | 最小依赖集，8GB 可跑 |
| 完整模式 | `milvus` | Milvus collection | 独立的 Elasticsearch BM25 索引 | 换嵌入模型不会连带重建全文索引 |

两种形态下向量分都会被换算为标准 cosine 再线性映射到 `[0,1]`，因此同一个相似度阈值在两种形态下语义一致。

## 零 Key 模式

不配置 `DASHSCOPE_API_KEY` 时服务照常启动，全链路可用：

- 索引管线跳过嵌入，分片 `embedding_status=SKIPPED`
- 只建全文索引，物理索引名的嵌入版本段取固定占位值 `none`
- 检索退化为 BM25 单路，响应 `degraded` 数组包含 `vector_route_unavailable`
- `GET /api/v1/system/model-status` 返回 `embedding_configured=false`，管理台据此置灰依赖模型的功能

后续配置嵌入模型时走「建新物理索引 + 全量嵌入 + 别名原子切换」升级，不是原地改索引。

## 配置

全部配置项通过环境变量注入，`application.yml` 只做映射。除数据库口令与 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` 外都带可用的本地默认值——对象存储凭据没有默认值是刻意的，缺失时启动阶段直接报错并指明要设哪两个变量，不会等到上传时才失败。核心变量：

```bash
MYSQL_HOST=127.0.0.1  MYSQL_PORT=3306  MYSQL_DB=kb_rag  MYSQL_USER=kbrag  MYSQL_PASSWORD=
ES_URI=http://127.0.0.1:9200
MILVUS_URI=                                  # 轻量模式留空
MINIO_ENDPOINT=http://127.0.0.1:9000  MINIO_ACCESS_KEY=<必填>  MINIO_SECRET_KEY=<必填>  MINIO_BUCKET=kb-files
VECTOR_ENGINE=es                             # es | milvus
DASHSCOPE_API_KEY=                           # 留空即零 Key 模式
EMBEDDING_PROVIDER=dashscope  EMBEDDING_MODEL=text-embedding-v4  EMBEDDING_DIM=1024
PARSER_BASE_URL=http://127.0.0.1:8001
```

嵌入调用走 OpenAI 兼容端点（`EMBEDDING_BASE_URL` 默认 `https://dashscope.aliyuncs.com/compatible-mode/v1`），改这一个变量即可切到 Azure OpenAI、Ollama 或 vLLM。

密钥只从环境变量读取，不入代码也不入配置文件。

## 构建与运行

```bash
export JAVA_HOME=/path/to/jdk17
mvn -DskipTests package
java -jar kb-api/target/kb-rag-server.jar
```

启动时 Flyway 自动执行迁移。数据库中没有管理员账号时会创建 `admin` 并把随机密码打印到启动日志（只打印一次），首次登录强制改密。

跑单元测试：

```bash
mvn test
```

## 接口清单（M1）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/login` | 登录，返回 token 与是否需要改密 |
| POST | `/api/v1/auth/change-password` | 改密，改后已签发 token 全部失效 |
| GET | `/api/v1/auth/me` | 当前账号信息 |
| POST | `/api/v1/auth/logout` | 主动登出 |
| POST | `/api/v1/kb` | 建知识库（同步建索引与别名） |
| GET | `/api/v1/kb` | 知识库列表 |
| GET | `/api/v1/kb/{kbId}` | 知识库详情 |
| DELETE | `/api/v1/kb/{kbId}` | 删除知识库（软删） |
| POST | `/api/v1/kb/{kbId}/documents` | 上传文档（multipart），异步入管线 |
| GET | `/api/v1/kb/{kbId}/documents` | 文档列表，支持 `process_status` 与分页 |
| POST | `/api/v1/documents/{docId}/reindex` | 重跑索引管线 |
| GET | `/api/v1/documents/{docId}/chunks` | 分片列表（激活版本），分页 |
| POST | `/api/v1/kb/{kbId}/search` | 检索调试 |
| GET | `/api/v1/system/model-status` | 模型配置状态 |
| GET | `/actuator/health` | 健康检查 |

除 `/api/v1/auth/login` 与 `/actuator/**` 外，全部接口需要 `Authorization: Bearer <token>`。

统一响应体：成功 `{"code":"OK","message":"success","data":...,"request_id":"..."}`，失败 `{"code":"...","message":"...","request_id":"..."}`。`request_id` 在入口过滤器生成（可由 `X-Request-Id` 请求头指定），写入日志 MDC，并透传给 parser 服务。

## 索引命名

物理索引三段命名 `kb_{知识库ID}_{嵌入版本段}_{快照段}`，读写一律走别名 `kb_{知识库ID}_{engine}`，映射关系以 `t_kb_index_registry` 为准。

- 嵌入版本段：零 Key 取 `none`；完整模式的 Elasticsearch 索引取 `bm25`；其余取嵌入模型缩写（`text-embedding-v4` → `tev4`）
- 快照段：M1 固定 `v1`

## 关于中文分词

`content` 字段默认使用 `ik_max_word`。若 Elasticsearch 未安装 ik 插件，建索引时会自动回退到 `standard` 并打一条 info 日志。ik 插件安装步骤见 `kb-rag-deploy` 的 README；词典管理落在后续里程碑。

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。
