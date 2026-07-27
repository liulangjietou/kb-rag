# M1 开发契约（v1.0 · 由需求文档 v1.8 派生）

> 本文件是 M1 各仓库实现的**唯一共同契约**。与需求文档冲突时以需求文档为准并回报修订。
> 需求文档：/Users/zhangfuqiang/AI/知识库需求文档.md（v1.8）

## 0. 全局约定

- 端口：kb-rag-server **20000**、kb-rag-parser **20001**、kb-rag-web dev **20002**（proxy /api 与 /actuator → 20000）
- JDK 17（本机 JAVA_HOME=/Users/zhangfuqiang/Library/Java/JavaVirtualMachines/corretto-17.0.16/Contents/Home）、Spring Boot 3.3.x、MyBatis-Plus 3.5.x、Flyway；Python 3.11+/FastAPI；React18+TS+Vite+AntD5
- **每个类/模块必须标注作者**：Java 类级 Javadoc 末尾加 `@author owlzhangfq@gmail.com`；Python 模块 docstring 末尾加 `Author: owlzhangfq@gmail.com`。**新建类同样适用，无例外**
- **代码注释与 Javadoc/docstring 一律英文**（D15）；日志仅 info/error、英文、error 带错误码占位符，如 `log.error("parse document failed, errorCode={}, docId={}", ErrorCode.PARSE_FAILED, docId, e)`
- Java：lombok、CollectionUtils 判空、无魔法值（常量/枚举）、fast-fail 防御式编程全链路只做一处（Controller 入参层）
- **红线**：严禁复制 /Users/zhangfuqiang/engineer/AIGC/LLMentor 的任何代码片段（该项目非开源，本项目 Apache-2.0），只允许自己实现
- 每仓库根目录：LICENSE(Apache-2.0)、README.md（中文）、CHANGELOG.md、.gitignore；deploy 仓另有 SECURITY.md、CONTRIBUTING.md、NOTICE（含 MinerU 使用声明预留）
- 不要执行 git commit（由主会话审查后统一提交）

## 1. 环境变量（.env / .env.example，deploy 仓维护模板）

```
MYSQL_HOST=127.0.0.1  MYSQL_PORT=13306  MYSQL_DB=kb_rag  MYSQL_USER=kbrag  MYSQL_PASSWORD=<gen>
ES_URI=http://127.0.0.1:9200
QDRANT_URI=http://127.0.0.1:6333           # lite 模式可空
MINIO_ENDPOINT=http://127.0.0.1:9000  MINIO_ACCESS_KEY=..  MINIO_SECRET_KEY=..  MINIO_BUCKET=kb-files
VECTOR_ENGINE=es                            # es|qdrant（D16；lite 默认 es）
DASHSCOPE_API_KEY=                          # 可空=零 Key 模式
EMBEDDING_PROVIDER=dashscope  EMBEDDING_MODEL=text-embedding-v4  EMBEDDING_DIM=1024
PARSER_BASE_URL=http://127.0.0.1:20001
```

## 2. MySQL DDL（Flyway V1__baseline.sql，kb-rag-server 内）

通用列（每表必带）：`id BIGINT AUTO_INCREMENT PK`、`created_at`、`updated_at`、`lock_version INT DEFAULT 0`、`deleted TINYINT DEFAULT 0`。业务主键用 `xx_id VARCHAR(64)`（前缀+雪花/UUID）。utf8mb4。为补偿扫表建状态索引。

M1 建表（其余表随 M2/M4 里程碑加 V2+ 迁移）：

| 表 | 关键列 |
|---|---|
| t_kb_knowledge_base | kb_id UK, name, description, index_config JSON, current_config_fingerprint VARCHAR(64) |
| t_kb_document | doc_id UK, kb_id IDX, file_name, file_ext, file_size, current_version_id, process_status(UPLOADED/PARSING/PARSE_FAILED/PARSED/INDEXING/INDEXED/INDEX_FAILED) IDX, config_stale TINYINT, fail_reason VARCHAR(1024) |
| t_kb_document_version | version_id UK, doc_id IDX, version VARCHAR(16) (major.minor, 初始 1.0, UK(doc_id,version)), minio_object, parsed_object, content_hash VARCHAR(64) IDX, parse_fingerprint, chunk_fingerprint, embedding_version, status(BUILDING/BUILD_FAILED/READY/ACTIVE/ARCHIVED) IDX, changelog；UK(doc_id, status='ACTIVE') 用「唯一激活」以 active 标志列+唯一索引实现 |
| t_kb_chunk | chunk_id UK, kb_id IDX, doc_id IDX, document_version_id IDX, content MEDIUMTEXT, chunk_text_hash VARCHAR(64) IDX, parent_id, seq, enabled TINYINT, embedding_status(PENDING/DONE/FAILED/SKIPPED) IDX, metadata JSON |
| t_kb_chunk_index_sync | chunk_id, physical_index_name, engine(qdrant/es), status(PENDING/SYNCED/FAILED) IDX, retry_count；UK(chunk_id, physical_index_name) |
| t_kb_index_registry | kb_id IDX, engine, physical_index_name UK, alias_name, is_current TINYINT, embedding_provider, embedding_model, embedding_version, snapshot_version, schema_version, status(BUILDING/ACTIVE/PENDING_CLEANUP), task_id |
| t_kb_task | task_id UK, task_type(PARSE/INDEX/REBUILD/CLEANUP), biz_id IDX, status(PENDING/RUNNING/SUCCESS/FAILED) IDX, retry_count, fail_reason, progress INT |
| t_kb_admin_user | username UK, password_hash(BCrypt), must_change_password TINYINT, last_login_at |
| t_kb_system_config | config_key UK, config_value TEXT, description |
| t_kb_login_audit | username, ip, success TINYINT, reason, created_at IDX |

启动初始化：无管理员时生成 admin + 随机密码（info 日志打印一次）+ must_change_password=1。

## 3. 索引命名与别名（§4.3）

- 物理名：`kb_{kbId}_{嵌入版本段}_{快照段}`；M1 快照段固定 `v1`
- 嵌入版本段：完整模式 ES=`bm25`；lite ES=嵌入版本(如 `tev4`)；Qdrant=嵌入版本；**零 Key=`none`（仅建全文 ES 索引，不建向量索引/字段）**
- 别名：`kb_{kbId}_{engine}`，读写一律走别名；注册于 t_kb_index_registry
- ES mapping 固定字段（§6 引擎侧字段约定）：`chunk_id(keyword)`, `kb_id`, `doc_id`, `document_version_id`, `parent_id`, `chunk_type(keyword: text|image|chat_log)`, `enabled(boolean)`, `tag_ids(keyword[])`, `session_id`, `sender`, `msg_time(long)`, `chunk_seq(integer)`, `content(text, ik_max_word 若装了 ik，否则 standard 并 TODO 注明)`, `vector(dense_vector, dims=EMBEDDING_DIM, cosine)`（仅 lite+有 Key 时包含）
- Qdrant collection 同字段集（Cosine 距离，HNSW 索引，可过滤字段建 payload 索引）

## 4. kb-rag-server 结构（Maven 多模块）

parent `kb-rag-server`：`kb-common`（Result/ErrorCode/JsonUtil/异常）、`kb-domain`（实体+Mapper+领域服务）、`kb-infrastructure`（ES/Qdrant/MinIO/Provider/parser 客户端实现）、`kb-app`（管线编排、检索服务）、`kb-api`（Controller+DTO+启动类+Flyway 资源）。依赖方向 api→app→domain←infrastructure（infrastructure 实现 domain 接口，api 组装）。

核心接口（M1 定型，签名可微调但语义不变）：

```java
public interface EmbeddingProvider {           // kb-domain
    String providerName();  String model();  int dimension();
    List<float[]> embed(List<String> texts);   // batch, throws ProviderException(classified)
    HealthStatus healthCheck();
}
// RerankProvider/ChatProvider/VisionProvider 同风格，M1 只需接口 + 空实现占位（M2 落地）

public interface VectorStore {                 // kb-domain；ES 与 Qdrant 双实现
    void ensureIndex(IndexSpec spec);          // 幂等建索引+别名
    void upsert(String alias, List<ChunkRecord> records);
    void delete(String alias, List<String> chunkIds);
    List<ScoredChunk> search(String alias, VectorQuery q);   // 分数统一映射到标准 cosine [0,1]（§4.4 跨引擎统一：ES 走 score*2-1 还原再 (x+1)/2）
}
public interface FulltextStore { /* upsert/delete/searchBm25(alias, text, filter, topK) */ }
```

零 Key 判定：EmbeddingProvider 未配置 → ModelStatus.embeddingConfigured=false → 索引管线跳过嵌入（chunk.embedding_status=SKIPPED），检索走 BM25 单路并返回 degraded=[vector_route_unavailable]。

索引管线（M1 固定参数）：上传→MinIO→建 doc+version(1.0,BUILDING)→异步任务：调 parser→按长度切分（默认 600 token 估算/重叠 100，token 估算=中文字符×1+其他按 4 字符 1 token 的简化实现，出 TokenEstimator 接口）→写 t_kb_chunk（含 chunk_text_hash=归一化正文 SHA-256）→写 ES（+有 Key 时嵌入→写向量索引，t_kb_chunk_index_sync 按物理索引登记）→version 置 READY→ACTIVE、doc 置 INDEXED。失败置对应状态+fail_reason，可重试（幂等：按 version 重建先删旧 chunk）。

## 5. REST API（管理台内部 API，M1；对外 API Key 网关是 M4c）

统一响应：成功 `{code:"OK", message:"success", data:..., request_id}`；错误 `{code, message, request_id}`。request_id 全链路透传（生成于入口 filter，放 MDC）。
错误码（M1 子集）：`INVALID_PARAM(400)/UNAUTHORIZED(401)/NOT_FOUND(404)/PARSE_FAILED(500)/UPSTREAM_MODEL_ERROR(502)/INTERNAL_ERROR(500)`。

- `POST /api/v1/auth/login` {username,password} → {token, must_change_password}；失败记 t_kb_login_audit；5 次锁 15 分钟
- `POST /api/v1/auth/change-password`、`GET /api/v1/auth/me`
- 鉴权：自定义 Header `Authorization: Bearer <token>`（服务端 token 表或内存缓存，有效期 24h），除 login/actuator 外全部拦截
- `POST /api/v1/kb` {name,description} / `GET /api/v1/kb` / `GET|DELETE /api/v1/kb/{kbId}`
- `POST /api/v1/kb/{kbId}/documents`（multipart，校验扩展名+magic number+≤100MB）→ 异步入管线
- `GET /api/v1/kb/{kbId}/documents?process_status=&page=`
- `POST /api/v1/documents/{docId}/reindex`
- `GET /api/v1/documents/{docId}/chunks?page=`
- `POST /api/v1/kb/{kbId}/search` {query, recall_top_k=50, top_n=5} → {nodes:[RetrievalNode], request_id, degraded:[]}
  - RetrievalNode：`{doc_id, document_version_id, chunk_id, chunk_type, content, score, score_type(cosine|bm25_rank), retrieval_source(vector|bm25), metadata, image_urls:[], preview_url:null}`
  - 有 Key：向量+BM25 双路（lite 单引擎两次独立请求）→ RRF(k=60) 融合 → top_n（M1 无 rerank，score_type 标注清楚；阈值 M2）
  - 零 Key：BM25 单路，degraded=[vector_route_unavailable]
  - 强制过滤：document_version_id ∈ 激活版本 + enabled=true（引擎侧 filter）
- `GET /api/v1/system/model-status` → {embedding_configured, vector_engine, provider, model}
- `GET /actuator/health`（含 MySQL/ES/MinIO/Qdrant(配置时) 探活）

## 6. kb-rag-parser API

- `POST /api/v1/parse`：multipart `file` + form `file_ext`；支持 pdf(pymupdf)/docx(python-docx)/txt/md/xlsx(openpyxl)/csv；返回 `{code:"OK", data:{markdown, pages:[{page_no,text}], images:[]}, message, request_id}`；失败 code=PARSE_FAILED + message
- `GET /health` → {status:"UP"}
- 安全（§4.2 解析安全约束）：defusedxml 处理一切 XML；禁止出站网络（不实现任何 URL 拉取）；zip 解包路径校验+总量/文件数上限；文件大小上限 100MB；worker 超时 300s
- 结构：app/main.py、app/parsers/{pdf,docx,text,excel}.py 策略注册表、tests/ 用 pytest 覆盖每格式一个样例

## 7. kb-rag-web（前端基础框架，M1）

- Vite+React18+TS+AntD5+react-router+axios（拦截器带 token、统一错误提示、request_id 展示）
- 页面：登录（含首登强制改密）、知识库列表（建/删/空态）、知识库详情（上传+文档列表带状态轮询+分片抽屉查看）、检索调试（query+recall_top_k+top_n，结果卡片显示 score/score_type/retrieval_source/degraded 标签）
- 零 Key 处理：进入时拉 model-status，未配置则顶部 banner 提示 + 相关配置项置灰文案
- 布局：左侧菜单（知识库/检索调试/系统设置占位）

## 8. kb-rag-deploy

- `docker-compose.yml`（full：mysql8/es8.11+ik 说明/qdrant(单容器自带存储)/minio/redis 可选/含 healthcheck+固定镜像 tag）
- `docker-compose.lite.yml`（mysql/es/minio，8GB 档）
- `.env.example`、`scripts/backup.sh` 骨架、`README.md`（快速启动：lite 优先、零 Key 路径与 DashScope 路径两条明路）、`docs/`（本契约、OpenAPI 骨架 openapi/kb-server.yaml 与 parser.yaml——按 §5/§6 写 M1 端点）
- ES 中文分词：M1 允许 standard 分词器起步，README 注明 ik 安装步骤（M2 落地词典）

## 9. M1 验收（照抄需求 §10，实现完成后主会话逐条验）

1. 配置 Key：上传 pdf → 界面看到分片 → search 返回非空且 node 含 score/chunk_id
2. 零 Key：/actuator/health 全绿；上传 pdf 后 search 走 BM25 返回非空、degraded 含 vector_route_unavailable；界面置灰提示
