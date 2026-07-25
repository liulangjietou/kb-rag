# M2 开发契约（v1.0 · 增量于 M1-CONTRACTS.md，冲突以本文件为准）

> 需求依据：知识库需求文档 v1.8 §4.3/§4.4/§10-M2。M1 已交付基线见各仓 HEAD。

## 1. 检索链路升级（kb-rag-server）

M2 完整链路（固定次序）：
`Query 改写(可开关) → 双路召回(子片粒度) → 库内融合(RRF|加权) → rerank(可开关,候选≤50) → 父子归并(开启时) → 阈值过滤 → top_n`

### 1.1 Query 改写
- ChatProvider 落地 DashScope OpenAI 兼容 chat/completions（模型 qwen-plus，`CHAT_MODEL` 配置）；固定英文 system prompt（改写为检索友好 query，输出仅改写结果，不解释）；messages 非空时做多轮指代消解改写
- 超时 800ms（`kb.retrieval.rewrite-timeout-ms`）；超时/失败降级用原 query，degraded += `query_rewrite_timeout`；改写输出仅作检索词（Prompt 注入防护 §4.4④）
- Caffeine 缓存：key=sha256(query+messages)，TTL 10min，max 10_000
- 零 Key/未配置 chat 模型：改写自动关闭（不加 degraded——功能未启用不算降级；仅显式开启改写却无模型时加）

### 1.2 Rerank
- RerankProvider 落地 DashScope 原生端点 `POST /api/v1/services/rerank/text-rerank/text-rerank`（模型 `RERANK_MODEL` 默认 gte-rerank）；返回 0-1 归一化 relevance_score
- 候选上限 50（融合后按粗排序截断，父子开启时按 §1.4 换算）；超时 1.5s；超时/失败降级为融合结果排序，degraded += `rerank_timeout`|`rerank_error`
- rerank 分是唯一绝对分：score_type=`rerank`

### 1.3 融合与阈值
- `fusion.mode = rrf|weighted`（默认 rrf）；weighted：每路候选集内 min-max 归一化，融合分 = w_vec·norm(vec)+w_bm25·norm(bm25)，w 和为 1（入参只传 w_vec）；RRF k 可配默认 60
- `score_threshold`(0.01-1.0，可空=不过滤)：作用于 rerank 分；rerank 关/降级时作用于向量标准 cosine[0,1] 分；BM25 单路时不生效，degraded += `threshold_inactive`；响应 nodes[].score_type 必须反映实际作用分数
- score_type 枚举扩展：`rerank | cosine | bm25_rank | fused_rrf | fused_weighted`

### 1.4 父子分片
- 知识库 index_config 增 `parent_child: {enabled, parent_max_tokens=1200, child_max_tokens=400, child_overlap=50}`；两级切分：现有按长度策略先切父片，父片内再切子片；父 chunk 行 parent_id=null，子行 parent_id=父 chunk_id；**引擎只索引子片**，父片正文只在 MySQL
- 检索：召回/融合/rerank 全在子片；之后按 parent_id 归并（父片分=命中子片最高分 max），阈值与 top_n 作用于父片列表；node.content=父片文本，metadata 含 child_ids、每子片各路分
- 候选换算：按子片粗排分依次纳入直至"归并后父片数 ≥ max(top_n*3,20)"或子片数=50
- 未开启父子：行为与 M1 相同（单级=子片即返回单位）

### 1.5 search API 入参扩展（管理台调试用，全部可选、即时生效）
```
score_threshold, fusion:{mode,w_vec,rrf_k}, rerank_enabled(默认 true 当模型配置),
rewrite_enabled(默认 false), messages, metadata_filter:{tag_ids[], session_id, sender,
msg_time_from, msg_time_to}
```
- metadata_filter 引擎侧映射：ES bool filter / Milvus expr（字段即 M1 契约引擎固定字段集）；索引管线须把 chunk.metadata 中这些键写入引擎字段
- 响应 nodes[].metadata 增：`norm_vector_score/norm_bm25_score/fused_score/rerank_score`（存在时）；顶层增 `applied:{rewrite_used_query, fusion_mode, threshold_applied_on}` 供调试页展示

## 2. 双写一致性与补偿（server）
- `@Scheduled`（fixedDelay 30s，可配）扫 t_kb_chunk_index_sync：status=FAILED 或 PENDING 且 updated_at 超 5min → 按 physical_index_name 分组重推（重嵌入仅当该索引需向量且原向量缺失）；retry_count≥5 停止并 error 日志；单批 ≤500
- 删除文档/知识库时同步删引擎内 chunk（M1 已软删 MySQL；M2 补引擎删除 + sync 行清理）
- 检索 toNodes 时发现引擎命中但 MySQL 缺失/禁用 → 记 info 并异步下发该 chunk 的引擎删除（自愈）

## 3. ik 词典（server + deploy）
- Flyway V2：`t_kb_ik_dict`(word UK, dict_type=EXT|STOP, status=ENABLED/DISABLED, 通用列)；V2 一并加 `t_kb_knowledge_base.retrieval_config JSON`（存 KB 级检索默认参数）
- 管理 API：GET/POST/DELETE `/api/v1/dict/ik`（分页/新增/删除，鉴权同管理 API）
- **热更新通道**：GET `/internal/dict/ik/{ext|stop}.txt`——纯文本一行一词，响应头带 `Last-Modified` 与 `ETag`（ik remote_ext_dict 协议）；此端点免登录（ik 无法携带凭证），仅回词表无敏感信息，路径在 AuthInterceptor 白名单并注释说明
- deploy：`es-ik/Dockerfile`（elasticsearch:8.11.4 + `elasticsearch-plugin install analysis-ik` 官方发布包 URL，构建参数化版本）+ `elasticsearch-ik.yml` compose override（build 该镜像并挂 ik 配置 `remote_ext_dict: http://host.docker.internal:20000/internal/dict/ik/ext.txt`，stop 同理）；README 写明可选启用步骤与 macOS host 访问说明
- server 建索引时 analyzer 探测已存在（M1 fallback 逻辑保留）

## 4. 配置不一致与按新配置重建（server + web）
- KB 索引配置更新 API：PUT `/api/v1/kb/{kbId}/index-config`（分片参数/父子配置）→ 重算 current_config_fingerprint → `UPDATE t_kb_document SET config_stale=1` （活跃版本 chunk_fingerprint ≠ 新指纹的文档）
- POST `/api/v1/kb/{kbId}/rebuild`（body 可选 doc_ids，缺省=全部 config_stale 文档）：每文档 REBUILD 任务——从 MinIO 解析产物（无则重调 parser）按新配置重切/重嵌入，**同一物理索引内原子替换该版本 chunk**（先写新 chunk 再删旧，引擎同步走既有双写）；完成置 config_stale=0
- 【偏离需求记录】需求 §4.3 的"建新物理索引+别名切换"重建流程留给嵌入模型切换场景（M4+）；切分配置变更不改引擎 schema，文档内替换即可，成本低一个量级——此偏离写入最终报告
- web：知识库详情顶部"N 篇文档使用旧配置"提示条 + 重建按钮 + 任务进度

## 5. web 调试页升级
- 参数面板分组：改写(开关)、召回(recall_top_k)、融合(mode/w_vec/rrf_k)、重排(开关)、过滤(score_threshold + metadata_filter 编辑器：标签/会话/发送人/时间范围)、返回(top_n)
- 结果卡片：各路原始分+归一化分、fused 分、rerank 分、阈值作用类型 Tag、父子模式下展开命中子片明细
- 顶部 applied 信息条（实际使用的改写后 query、fusion 模式、阈值作用分数）
- 系统设置新增"ik 词典"tab：词表分页/新增/删除/类型切换
- 知识库详情：索引配置编辑（含父子分片开关与长度）、config_stale 提示与重建

## 6. deploy 增量
- `scripts/benchmark.sh`：对指定 KB 跑 200 次检索（并发 5，query 从文件轮换），输出 P50/P95/P99；README 记录压测口径（M2 验收：基础链路 P95<2s）
- OpenAPI kb-server.yaml 同步 M2 入参/出参
- es-ik 见 §3

## 7. 验收清单（实现完成后主会话执行）
1. 调试页调参（fusion 模式/权重/阈值/rerank 开关）各路得分变化可对比（配 Key）
2. 零 Key：阈值在 BM25 单路自动失效且 degraded 标注
3. 新增 ik 词条后专有词 BM25 召回改善可复现（启用 es-ik 时）
4. metadata_filter：按 session_id+时间范围过滤生效（用注入 metadata 的测试文档）
5. 改配置→config_stale 提示→重建→提示消失、新分片生效
6. benchmark.sh 200 次 P95 达标
- 单测新增（必须）：加权归一化融合、阈值作用分选择、父子归并与候选换算、改写降级、rerank 降级、补偿扫描分组
