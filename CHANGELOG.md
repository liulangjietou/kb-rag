# 变更记录

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。
标注 `[schema]` 的条目包含数据库迁移脚本，升级时会自动执行 Flyway。

## [未发布]

### 新增（M2）

- `[schema]` Flyway `V2__ik_dict_and_retrieval_config.sql`：新增 `t_kb_ik_dict`（词条 UK，EXT/STOP，可停用），`t_kb_knowledge_base` 增 `retrieval_config` JSON 列
- Query 改写：DashScope OpenAI 兼容 `chat/completions` 落地 `ChatProvider`；800ms 硬超时降级、Caffeine 缓存（key 含多轮会话）、多轮指代消解；改写结果只当检索词用，单行化 + 长度截断作为 Prompt 注入防护；超时与失败分别标注 `query_rewrite_timeout` / `query_rewrite_error`
- 重排：DashScope 原生 `text-rerank` 端点落地 `RerankProvider`；候选 ≤50、1.5s 硬超时，超时与失败分别标注 `rerank_timeout` / `rerank_error`
- 融合升级：新增 `weighted` 模式（每路候选集内 min-max 归一化，`w_vec` 可调，BM25 权重取补），`rrf_k` 可配；`FusionStrategy` + `FusionRouter` 组合替代分支
- 阈值语义定型：只作用于跨查询可比的分数（重排分 > 归一化 cosine），BM25 单路时失效并返回 `threshold_inactive`；`score_type` 扩展 `rerank | fused_rrf | fused_weighted`
- 父子分片：两级切分复用既有定长策略，引擎只索引子片、父片正文只存 MySQL；检索后按 `parent_id` 归并（max 聚合），候选按「归并后父片数达标或子片数达上限」换算
- search API 扩展：`score_threshold`、`fusion{mode,w_vec,rrf_k}`、`rerank_enabled`、`rewrite_enabled`、`messages`、`metadata_filter`；响应增 `applied` 信息条与各路原始分 / 归一化分 / 融合分 / 重排分
- `metadata_filter` 引擎侧下推：Elasticsearch bool filter 与 Milvus expr 双实现；索引管线把 `chunk.metadata` 的固定键写入引擎字段
- 双写补偿：`@Scheduled` 扫 `t_kb_chunk_index_sync`，按物理索引分组重推，重试上限后放弃并打错误日志；文档 / 知识库删除同步清理引擎；检索命中但事实源缺失时异步自愈删除
- 配置变更与重建：`PUT /api/v1/kb/{kbId}/index-config` 重算指纹并刷新 `config_stale`，`POST /api/v1/kb/{kbId}/rebuild` 在同一物理索引内先写新片再删旧片
- ik 词典：管理 CRUD + 启停 API，`/internal/dict/ik/{ext|stop}.txt` 免登录热更新通道（`Last-Modified` / `ETag`，未变更返回 304）
- `GET /api/v1/system/model-status` 增加重排与对话模型的配置状态
- 端口调整：应用默认端口 8080 → 20000，parser 默认地址 → `http://127.0.0.1:20001`

### 新增（M1）

- `[schema]` Flyway `V1__baseline.sql`：建立 10 张基线表（知识库、文档、文档版本、分片、分片×物理索引同步状态、索引注册表、异步任务、管理员、系统设置、登录审计）
- 启动初始化：数据库无管理员时创建 admin 账号，随机密码打印一次并强制首登改密
- 登录鉴权：BCrypt 校验 + Bearer Token（默认 24h）+ 基于登录审计表的防爆破锁定（默认 5 次锁 15 分钟）
- 知识库 CRUD，创建时同步建物理索引、绑定别名并登记 `t_kb_index_registry`
- 文档上传：扩展名 + 文件头（magic number）+ 大小三重校验，原件存 MinIO 私有桶
- 异步索引管线：调用 parser 解析 → 按长度切分（默认 600 token / 重叠 100）→ 分片落库（含 `chunk_text_hash`）→ 写全文索引 →（有 Key 时）嵌入并写向量索引 → 按物理索引登记同步状态 → 版本激活
- 检索 API：有 Key 走向量 + BM25 双路 RRF（k=60）融合，零 Key 走 BM25 单路并返回 `degraded=[vector_route_unavailable]`；两路均在引擎侧强制过滤激活版本与启用状态
- Provider 抽象：嵌入（DashScope，OpenAI 兼容 HTTP 端点）落地，重排 / 对话 / 视觉三类接口与未配置占位实现就位
- 引擎抽象：`VectorStore` 双实现（Elasticsearch dense_vector 与 Milvus），`FulltextStore` Elasticsearch 实现；向量分统一换算为标准 cosine 后线性映射到 `[0,1]`
- `GET /api/v1/system/model-status`：向管理台透出是否配置嵌入模型与当前向量引擎
- `GET /actuator/health`：含 MySQL、Elasticsearch、MinIO 探活，配置 Milvus 时增加 Milvus 探活
- 统一响应包装、错误码枚举与 `request_id` 全链路透传（入口 filter 生成，写入 MDC 并透传至 parser）
