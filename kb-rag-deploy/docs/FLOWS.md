# kb-rag 流程图文档


> 版本：v1.8（基线与 `ARCHITECTURE.md` v2.6 相同 = M1-M23 及其后修复的状态；v1.7 基线为 M22）
> 日期：2026-08-14
> 作者：RichardFyoung / Claude
>
> 图使用 Mermaid 绘制（GitHub / 主流 IDE 原生渲染）。每张图标注对应的核心类与契约出处，与代码不一致时以代码为准并须在同一 PR 内修订本文档（项目铁律②）。

---

## 1. 文档上传与索引管线

对应：`DocumentService` / `IndexPipelineService` / `ChunkIndexWriter`（契约 M1 §4、M3 §3）。

```mermaid
sequenceDiagram
    autonumber
    participant W as kb-rag-web
    participant S as kb-server(DocumentService)
    participant M as MinIO
    participant DB as MySQL
    participant P as IndexPipelineService(@Async)
    participant PA as kb-parser
    participant V as VisionProvider
    participant E as ES / Qdrant

    W->>S: POST /kb/{kbId}/documents (multipart)
    S->>S: UploadValidator 校验(扩展名/大小/magic number)
    S->>M: 原件落 MinIO
    S->>DB: 事务写 t_kb_document + t_kb_document_version
    Note over S: afterCommit 才提交异步管线<br/>(避免 worker 读不到未提交行)
    S-->>W: 返回 doc_id (process_status=UPLOADED)
    S--)P: submit(versionId) [事务提交后]
    P->>P: 指纹判定(VersionFingerprintFactory)<br/>全匹配则 VersionArtifactReuser 复用 chunk 跳过解析切分
    P->>PA: POST /api/v1/parse (X-Request-Id 透传)
    PA-->>P: markdown + pages(text/markdown 切片, scanned/ocr_source) + images + warnings
    P->>P: 清洗/脱敏 (DocumentCleaner/TextDesensitizer)
    P->>V: 扫描页/内嵌图 → 文本代理 (零 Key 跳过)
    P->>P: 占位符插回原位 → SplitterRouter 切分<br/>(按页切分改由 PageSplitter 按页区间下刀, 吃同一份清洗后正文)<br/>(父子开启时两级切分且仅限定长策略, 子片偏移随切分产出 V10)
    P->>P: ChunkEmbedder 嵌入 (零 Key 全部 SKIPPED)
    P->>DB: chunk 写事实源
    P->>DB: t_kb_chunk_index_sync 先写 PENDING
    P->>E: 双写引擎 (别名寻址)
    P->>DB: 同步行置 SYNCED / 失败留 FAILED 待补偿
    P->>DB: 版本置 READY → DocumentVersionActivator 激活<br/>document.process_status=INDEXED
```

### 1.1 文档处理状态机（`process_status`，与 `config_stale` 正交）

```mermaid
stateDiagram-v2
    [*] --> UPLOADED: 上传完成
    UPLOADED --> PARSING: 管线启动
    PARSING --> PARSE_FAILED: 解析失败(可重试)
    PARSING --> PENDING_CONFIRM: 知识库开启解析预览
    PARSING --> PARSED: 解析完成
    PENDING_CONFIRM --> PARSED: 确认(可先改清洗规则重解析)
    PARSED --> INDEXING: 切分/嵌入/双写
    INDEXING --> INDEX_FAILED: 构建失败(可重试)
    INDEXING --> INDEXED: 构建完成
    PARSE_FAILED --> PARSING: 重试
    INDEX_FAILED --> INDEXING: 重试
```

---

## 2. 文档版本状态机与回退

对应：`DocumentVersionPlanner` / `DocumentVersionActivator` / `VersionRetentionService` / `AppVersionPinChecker`（需求 §4.1，契约 M4a）。

```mermaid
stateDiagram-v2
    [*] --> BUILDING: 重传/重建生成新版本<br/>(content_hash 变→major+1, 指纹变→minor+1)
    BUILDING --> BUILD_FAILED: 失败(不影响线上激活版)
    BUILDING --> READY: 构建完成
    READY --> ACTIVE: 激活切换(原子, 读侧即时生效)
    ACTIVE --> READY: 新版本激活时退位<br/>(chunk 保留, 支持秒级回退)
    READY --> ARCHIVED: 超出保留窗口(默认3)异步清理 chunk<br/>被应用版本快照引用(pin)时禁止归档
    ARCHIVED --> BUILDING: 回退归档版 = REBUILD 任务<br/>(从解析产物重建, 复用指纹缓存)
```

要点：激活关系以 `t_kb_document.current_version_id` 为准（同一文档至多一条 active，DB 唯一约束）；检索强制过滤"版本可见集"，切换激活版本即切换过滤值，无需重建索引。

---

## 3. 检索链路（含全部降级点）

对应：`RetrievalService` 及其协作者（需求 §4.4，契约 M2/M5/M6/M7/M9）。

```mermaid
flowchart TD
    Q[query + messages + images?] --> IMG{带图片 query? M9}
    IMG -- 否 --> RW
    IMG -- 是 --> IMG1[ImageQueryService 校验张数/大小<br/>逐张 VLM 转文本, 前缀拼接到 query 尾部]
    IMG1 -- 成功 --> RW
    IMG1 -- 零Key/超时/失败 --> DG0[degraded: image_understanding_unavailable<br/>忽略全部图片纯文本继续<br/>纯图无文本→INVALID_PARAM 报错] --> RW
    RW{改写开启?}
    RW -- 否 --> RT
    RW -- 是 --> RW1[RewriteService LLM 改写<br/>超时800ms]
    RW1 -- 成功 --> RT
    RW1 -- 超时/失败/未配模型 --> DG1[degraded: query_rewrite_*<br/>用原始 query] --> RT
    RT{多库路由开启且多库?}
    RT -- 否 --> CTX
    RT -- 是 --> RT1[RoutingService LLM 选库<br/>白名单交集裁决]
    RT1 -- 命中 --> CTX
    RT1 -- 未命中/失败 --> DG2[degraded: route_fallback_all<br/>检索全部关联库] --> CTX
    CTX[RetrievalIndexContextResolver<br/>RELEASED→快照索引+固化可见集<br/>其他→实时别名+激活集合<br/>快照缺失→degraded: snapshot_index_missing 回退实时]
    CTX --> R1[向量路召回 recall_top_k<br/>零 Key→degraded: vector_route_unavailable]
    CTX --> R2[BM25 路召回 recall_top_k]
    CTX --> R3[图路召回 M7<br/>切词→Neo4j fulltext→N跳→溯源chunk<br/>Neo4j 不可达→degraded: graph_route_unavailable<br/>快照上下文直接关闭不记降级]
    R1 & R2 & R3 --> F[库内融合 FusionRouter<br/>RRF k=60 / 加权min-max归一化<br/>开图路强制RRF]
    F --> XKB{多库?}
    XKB -- 是 --> X1[跨库 RRF 只用名次<br/>KbQuotaAllocator 按权重分 rerank 配额]
    XKB -- 否 --> ND
    X1 --> ND[NearDuplicateWindowMerger<br/>聊天重叠窗口归并]
    ND --> RR{rerank 开启?}
    RR -- 是 --> RR1[RerankService 候选上限50<br/>父子联动 max50, top_n×5 硬上限100<br/>超时1.5s]
    RR1 -- 成功 --> PC
    RR1 -- 超时/失败/未配模型 --> DG3[degraded: rerank_*<br/>降级粗排分] --> PC
    RR -- 否 --> PC
    PC[父子归并 ParentChildMerger<br/>子片粒度→按 parent_id 归并, 父片得分取子片 max<br/>M9: 禁用子片按偏移倒序精确剔除+省略标记+redacted_child_count<br/>任一禁用子片偏移为 null→整片回退]
    PC --> TH[阈值过滤 ScoreThresholdPolicy<br/>rerank分>标准cosine分<br/>BM25单路失效→degraded: threshold_inactive]
    TH --> TN[top_n 截断 → nodes + degraded + score_type]
```

**强制不变式**（不出现在参数面板、不可关闭）：各路召回在引擎侧过滤 `document_version_id ∈ 可见集` 且 `enabled=1`；图路回溯 chunk 后回 MySQL 事实源二次复核同一谓词。

---

## 4. 双写一致性与补偿

对应：`ChunkIndexWriter` / `IndexSyncCompensationService` / `EngineChunkCleaner`（契约 M2 §2）。

```mermaid
sequenceDiagram
    autonumber
    participant P as 索引管线/标注管线
    participant DB as MySQL(事实源)
    participant SY as t_kb_chunk_index_sync
    participant E as 引擎(ES/Qdrant)
    participant C as 补偿任务(30s fixedDelay)

    P->>DB: chunk 先写事实源
    P->>SY: 写 PENDING (chunk × 物理索引)
    P->>E: upsert
    alt 成功
        P->>SY: 置 SYNCED
    else 失败/进程中断
        Note over SY: 留 FAILED 或超时 PENDING
        C->>SY: 扫描 FAILED + 超时 PENDING (batch 500)
        C->>DB: 按 MySQL 当前值读回
        C->>E: 按物理索引分组幂等重放 upsert
        C->>SY: 置 SYNCED / retry_count+1(上限告警)
    end
    Note over P,E: 删除方向: 新文档事务内写、旧文档删除挂 afterCommit<br/>宁留短暂重复、不留补偿看不见的空洞
    Note over E: 检索时命中事实源已删分片 → EngineChunkCleaner 反向清理<br/>(快照索引路径关闭自愈, 防跨索引误删)
```

---

## 5. 索引重建与嵌入模型切换

对应：`RebuildService` / `IndexAliasManager` / `EsIndexAdmin`（需求 §4.3，v1.9 定版）。

```mermaid
flowchart TD
    subgraph A[场景一: 切分/清洗配置变更 同索引内替换]
        A1[配置变更 → 知识库 current_config_fingerprint 更新] --> A2[存量文档 config_stale=true<br/>界面提示 N 篇文档使用旧配置]
        A2 --> A3[按新配置重建: 逐文档原子替换 chunk<br/>先写新片再删旧片, 宁可短暂重复不出现空洞]
    end
    subgraph B[场景二: 嵌入切换 / lite→full / schema 变更 新索引+别名切换]
        B1[建新物理索引 kb_kbId_新嵌入段_vN<br/>登记为第二写入目标] --> B2[双目标写入期: 新增/变更分片自动写两处]
        B2 --> B3[全量回填历史分片<br/>幂等 upsert + updated_at 水位二次校验]
        B3 --> B4{新目标无 PENDING/FAILED?}
        B4 -- 是 --> B5[updateAliases 原子切换 + is_write_index]
        B5 --> B6[下线旧目标, 清理其同步记录]
        B4 -- 否 --> B3
    end
```

---

## 6. 应用发布：状态机、门禁双跑与索引快照

对应：`AppVersionService` / `ReleaseGateService` / `AppReleaseSnapshotService` / `IndexSnapshotService`（需求 §4.7，契约 M4c/M6）。

### 6.1 应用版本状态机

```mermaid
stateDiagram-v2
    [*] --> DRAFT: 创建草稿(保存即校验 kb_refs)
    DRAFT --> TESTING: 发布测试版(API 可显式 app_version 灰度调用)
    TESTING --> GATING: 发起正式发布(绑定评测集时)
    TESTING --> RELEASED: 未绑定评测集直接发布(记"未经门禁")
    GATING --> GATE_PASSED: 门禁通过
    GATING --> GATE_BLOCKED: 门禁拦截(可强制放行留痕)
    GATING --> GATE_LOG_ONLY: 样本/Judge/运行异常(需人工确认)
    GATE_PASSED --> RELEASED: 冻结快照后生效
    GATE_BLOCKED --> RELEASED: force 放行(留痕)
    GATE_LOG_ONLY --> RELEASED: force 放行(留痕)
    RELEASED --> SUPERSEDED: 新版本发布/回滚时退位
    SUPERSEDED --> RELEASED: 回滚 = 历史版本重新置为正式版
    note right of RELEASED
        单应用唯一 RELEASED
        (released_slot 生成列唯一约束)
        SUPERSEDED 不可被 API 调用
    end note
```

### 6.2 门禁双跑与快照冻结时序

```mermaid
sequenceDiagram
    autonumber
    participant U as 管理台
    participant AV as AppVersionService
    participant G as ReleaseGateService(GATE_EXECUTOR)
    participant EV as EvalRunService(EVAL_EXECUTOR)
    participant AG as AnswerGenerationService
    participant AJ as FinalAnswerJudgeService
    participant J as 双层 Gate Judge(纯函数)
    participant SN as AppReleaseSnapshotService
    participant E as ES/Qdrant

    U->>AV: release(versionId)
    AV->>AV: transition → GATING
    AV--)G: 异步启动门禁
    G->>EV: 提交 run A(候选配置) + run B(当前正式版配置)
    Note over EV: 同语料同时刻双跑, 离线档(超时10s)<br/>与 GATE 池分离防自等待死锁
    opt 候选版本显式开启 answer_gate(M21)
        EV->>AG: 每个可判 case 复用生产 Prompt 生成最终答案
        AG->>AJ: 参考答案 + 生成答案 + 召回段落
        AJ-->>EV: 五维评分 + refusal_correct<br/>(失败保留 null, 不记 0 分)
    end
    EV-->>G: 两份检索指标 + 可选答案指标
    G->>J: 两类 Recomputer 分别在双方共判 case 交集重算<br/>检索容差 ε=max(2pp,1/N)<br/>答案分容差默认 0.2, 答/拒=max(2pp,1/N)
    alt 通过 / force 放行
        J-->>AV: GATE_PASSED
        AV->>SN: 冻结发布快照
        SN->>E: 每引擎建快照索引 kb_kbId_嵌入段_sN<br/>(ES _clone 毫秒级 / Qdrant scroll 游标拷贝, 不挂别名)
        SN->>AV: 同时固化 index_snapshots + visible_version_ids
        AV->>AV: transition → RELEASED, 原正式版 → SUPERSEDED
    else 拦截
        J-->>AV: GATE_BLOCKED(报告含检索 + 答案双跑对比)
    else 样本不足/含降级/待复核超阈/Judge 失败
        J-->>AV: 仅记录不拦截(人工确认发布留痕)
    end
```

要点：快照创建晚于门禁双跑、早于 RELEASED 生效，保证"门禁所测索引 = 发布后所用索引"；`SUPERSEDED` 未清理时仍 pin 其可见集版本（回滚承诺）；快照按保留数（默认 3）由凌晨任务清理，RELEASED 永不清理。

---

## 7. 对外 API 调用链（search / chat）

对应：`ApiKeyAuthFilter` / `KnowledgeApiService` / `ApiAuditService`（需求 §4.8，契约 M4c）。

```mermaid
sequenceDiagram
    autonumber
    participant A as 智能体应用
    participant F as ApiKeyAuthFilter(独立过滤器链)
    participant K as KnowledgeApiService
    participant R as RetrievalService
    participant C as ChatProvider(版本快照模型)
    participant AU as ApiAuditService(@Async)

    A->>F: POST /api/v1/knowledge/search|chat<br/>Authorization: Bearer kb-sk-***
    F->>F: SHA-256 哈希比对 + app_scope 越权检查(403 记审计)
    F->>F: 令牌桶限流(超限 429 + Retry-After, 记审计; 401 不落审计)
    F->>K: 放行(request_id 已入 MDC)
    K->>K: app_version 路由(缺省→RELEASED, 显式→可调测试版 target_stage=beta)
    K->>K: RequestOverridePolicy 白名单校验<br/>(top_n/score_threshold/metadata_filter/max_content_length, 越界即 INVALID_PARAM)
    K->>R: 版本快照配置执行检索
    alt search
        K-->>A: nodes + request_id + degraded[]
    else chat (stream=true)
        K->>C: 生成(prompt 用分隔符包裹资料原文, 防注入)
        K-->>A: SSE: message_delta* → references(RetrievalNode) → done(含 degraded)
    end
    K--)AU: 异步落审计(query 摘要无条件脱敏, 命中文档, 耗时, 降级, target_stage)
    Note over AU: 180 天保留, 凌晨任务归档 MinIO 后分批物理删除
```

---

## 8. 聊天记录两步式导入

对应：`ChatImportService` / `ChatSessionMatcher` / `ChatWindowAggregator`（需求 §4.2，契约 M3/M8）。

```mermaid
sequenceDiagram
    autonumber
    participant W as 导入向导(web)
    participant S as ChatImportService
    participant PA as kb-parser
    participant DB as MySQL

    W->>S: 第一步 preview(file, 映射档案ID)
    S->>DB: 读 t_kb_source_mapping 取 profile_yaml
    S->>PA: POST /api/v1/parse/chat (profile_yaml 随请求下发)
    PA-->>S: sessions[] + skipped(语音/视频剔除统计)
    S->>S: ChatSessionMatcher 按"来源渠道+session_id"匹配库内既有聊天文档<br/>(缺 session_id 回退"会话名+首条时间戳")
    S-->>W: 匹配结果列表(新建/生成新版本) + upload_token(绑定 kbId 防跨库重放)
    W->>S: 第二步 confirm(upload_token)
    S->>DB: 已存在会话→该文档新版本(NEW_VERSION, 全量替换可回退)<br/>新会话→新文档; 多会话按会话拆分多文档
    S--)S: 走统一索引管线: 窗口聚合(时长/条数先到者关窗,<br/>window_overlap 滑动起点) → [time] sender: content 渲染<br/>→ 脱敏(默认开) → 切分嵌入双写<br/>会话/发送人/时间进 metadata 供 metadata_filter 过滤
```

---

## 9. 评测运行流程

对应：`EvalRunService` / `EvalHitJudge` / `EvalMetricsCalculator`（需求 §4.6，契约 M4b）。

```mermaid
flowchart TD
    S[提交评测: 数据集 × 配置矩阵 一次最多6个run] --> EST[estimate 费用护栏<br/>预估调用次数与费用]
    EST --> R[EvalRunService @Async EVAL_EXECUTOR<br/>固化 dataset_revision + 语料指纹<br/>激活版本集/嵌入版本/词典版本]
    R --> C[逐 case 走真实检索链路<br/>OfflineExecutionContext 离线档: 超时放宽10s<br/>降级 case 自动重试2次, 不计生产监控]
    C --> H{锚定类型}
    H -- span 级 --> H1[EvalHitJudge: 归一化字符重叠率<br/>分母固定为 span, 聚合覆盖并集≥阈值即命中<br/>父子开启时在命中子片集合上计算]
    H -- 文档级(图片) --> H2[Top-K 出现该文档任一衍生分片即命中<br/>按 image_urls 识别, 分组展示不混算]
    H1 & H2 --> M[EvalMetricsCalculator<br/>Recall@K / Precision@K / HitRate / MRR / NDCG@K<br/>Wilson 95% CI 仅展示不参与门禁<br/>单轮/多轮分组; evidence_stale 单列不计未命中]
    M --> J{LLM-as-judge 开启?}
    J -- 是 --> J1[固定版本化 prompt, temperature=0<br/>judge 独立于生成模型, 不参与门禁]
    J -- 否 --> RP
    J1 --> RP[报告: 指标对比表 + case 下钻 hit/hit_rank<br/>+ 降级明细 + compare 可比性判定]
```

---

## 10. 图路：抽取与检索（M7）

对应：`GraphExtractionService` / `GraphRetrievalService`（需求 §4.9/v1.13，契约 M7）。

```mermaid
flowchart TD
    subgraph EX[抽取管线 写侧, LLM]
        E1[版本激活/手动触发 @Async] --> E2[逐分片 LLM 实体关系抽取<br/>输出强校验, 非法跳过计 skipped_count]
        E2 --> E3[Neo4j upsert: Entity + REL<br/>+ MENTIONED_IN 溯源边 含 version_id]
        E3 --> E4[版本切换/删除 → 级联清理图数据<br/>Neo4j 为派生存储, 可整体重建]
    end
    subgraph RT[检索路 读侧, 零 LLM]
        R1[query 轻量切词] --> R2[Neo4j fulltext cjk 实体匹配]
        R2 --> R3[沿关系扩展 ≤max_hops 默认2]
        R3 --> R4[溯源边回 chunk_id<br/>关联度 = 归一化匹配分 / 1+跳数]
        R4 --> R5[回 MySQL 事实源二次过滤<br/>版本可见集 + enabled, 防图路击穿版本隔离]
        R5 --> R6[作为第三路进库内 RRF<br/>该库强制 RRF 融合]
    end
    EX -.派生数据.-> RT
```

边界：`NEO4J_URI` 留空 = `DisabledGraphStore` 整体禁用零影响；快照上下文（RELEASED 调用）图路直接关闭、不记降级；Neo4j 不可达时 `degraded: graph_route_unavailable`，其余召回路正常。

---

## 11. 备份与恢复

对应：`scripts/backup.sh` / `restore.sh`（需求 §5，`backup-restore.md` 有真实演练记录）。

```mermaid
flowchart LR
    subgraph B[备份 cron 每日, RPO≤24h]
        B1[mysqldump --single-transaction 全量] --> B4[manifest.json 汇总]
        B2[ES _snapshot 增量 → backup/es-repo] --> B4
        B3[mc mirror MinIO bucket] --> B4
        B4 --> B5[按 BACKUP_KEEP_COUNT 滚动清理]
    end
    subgraph R[恢复 固定顺序, fast-fail]
        R1[1. MySQL 导入] --> R2[2. ES 先删 kb_* 再 _restore]
        R2 --> R3[3. MinIO mc mirror --remove]
        R3 --> R4[4. 必要时从事实源重建索引]
    end
    B -.产物.-> R
```

---

## 12. 零 Key 降级总览

各功能在未配置模型 Key 时的行为（需求 §5 零 Key 启动路径的实现口径）：

| 功能 | 零 Key 行为 |
|---|---|
| 检索 | BM25 单路，`degraded: vector_route_unavailable`；阈值失效透出 `threshold_inactive` |
| 嵌入 | `embedding_status=SKIPPED`，索引名嵌入段 `none`；配 Key 后走"启用向量检索"入口升级 |
| 切分 | 智能/LLM 语义切分回落按长度切分 |
| 改写/路由/rerank | 显式开启但未配模型 → `*_unavailable` 降级，链路继续 |
| VLM 图片理解 | 跳过文本代理（或由 parser 本地 PaddleOCR 兜底回填） |
| 图片 query（M9） | 忽略全部图片纯文本检索，`degraded: image_understanding_unavailable`；纯图片且无文本 query 返回 INVALID_PARAM |
| 问答生成 / judge / 图抽取 | 界面置灰 + 引导配置入口 |

---

## 13. 标注跨版本迁移建议（M9）

对应：`AnnotationMigrationAdvisor` / `ChunkAnnotationService`（需求 §4.5 二期项⑥转正，契约 M9 §0.4/0.5）。

```mermaid
flowchart LR
    A[文档新版本激活] --> B[标注不自动继承<br/>禁用类按 chunk_text_hash 精确继承]
    B --> C[pending-review 待复核清单<br/>懒计算 suggestions: 归一化 3-gram Dice 对称相似度<br/>阈值0.35 / top3 / 候选限同文档激活版本 / 短文本不推荐]
    C --> D[人工逐条 migrate 到目标分片<br/>仅禁用/编辑两类, 幂等, 强制同文档<br/>刻意不做自动与批量迁移]
```

---

## 14. 记忆库：写入抽取与检索（M19）

对应：`MemoryKeyAuthFilter` / `MemoryApiService` / `MemoryExtractionService` / `EsMemoryStore`（契约 M19 §3/§5/§6）。

> 下面两张图画的都是**开放端**（`Bearer kb-mk-*`）。那条链上没有控制台主体，租户围栏整条跳过，隔离由 Key 绑定的唯一记忆库 + `user_id` 两层查询谓词完成 —— 这是刻意的，拼租户条件反而会把 Key 自己的库过滤掉。
> **管理端**（`/api/v1/memory-libraries`，控制台主体在线程上）多一层租户围栏：`t_kb_memory_library` 带 `tenant_id` 进 `FENCED_TABLES`，带 `libraryId` 的 21 个入口一律先经 `MemoryLibraryGuard` 解析库再碰从属表 —— 从属表不带租户列，跳过这一步等于没有围栏；库列表与建库两个入口无 `libraryId`，由围栏本体直接覆盖（契约 M19 §1.4，Flyway V21）。

### 14.1 AddMemory 写入与抽取时序

```mermaid
sequenceDiagram
    autonumber
    participant A as 智能体应用
    participant F as MemoryKeyAuthFilter(第三条独立过滤器链)
    participant M as MemoryApiService
    participant X as MemoryExtractionService
    participant L as ChatProvider(LLM)
    participant DB as MySQL(t_kb_memory_node)
    participant ES as EsMemoryStore(kb_memory_nodes_v1)

    A->>F: POST /api/v1/memory/add<br/>Authorization: Bearer kb-mk-***
    F->>F: SHA-256 哈希查 t_kb_memory_app_key(401)<br/>+ ApiRateLimiter 限流(429)
    F--)F: last_used_at 异步 touch
    F->>M: 放行(库上下文已绑定 Key 所属 library_id)
    M->>M: messages/custom_content 二选一校验<br/>+ resolveFragmentRule(rule_id 缺省→库默认规则)
    alt custom_content 直写
        M->>DB: insertNode(CUSTOM, 原文不经 LLM)
        M->>ES: index() 写检索副本
    else messages 抽取(规则 PRO 档)
        M->>X: extractAndApply(messages, rule, user_id)
        X->>DB: auto_update 开启时加载该用户最近<br/>OLD_MEMORY_WINDOW=50 条旧记忆
        X->>L: extractFragments(对话+旧记忆, 版本化 prompt)
        L-->>X: fragments[] (ADD / UPDATE+目标ID)
        loop 每个 fragment
            X->>DB: ADD→insertNode / UPDATE→仅允许改窗口内目标节点
            X->>ES: index(): embedOf 失败置 null<br/>该节点降级 BM25 可召回
        end
        opt 规则绑定 profile_rule_id
            X->>L: extractProfile(画像字段抽取)
            X->>DB: 按 uk_rule_user 幂等 upsert t_kb_memory_profile
        end
    end
    M-->>A: memory_ids + request_id
    Note over M,ES: add 刻意非事务: 逐节点独立落库+写副本,<br/>单节点失败不回滚已写入节点(ES 可从 MySQL 重建)
```

### 14.2 SearchMemory 检索链路

```mermaid
flowchart TD
    Q[POST /api/v1/memory/search<br/>Bearer kb-mk-*** + user_id + query] --> P[加载该用户画像 profiles<br/>t_kb_memory_profile]
    P --> IT{intent_recognition 开启?}
    IT -- 是且 LLM 判定与记忆无关 --> VETO[veto: 返回空节点集 + profiles<br/>不再走检索]
    IT -- 否/相关 --> RW{query_rewrite 开启?}
    RW -- 是 --> RW1[LLM 改写 query<br/>失败降级原 query]
    RW -- 否 --> SE
    RW1 --> SE[MemoryStore.search<br/>强制 filter: library_id + user_id + 未过期<br/>rerank 开启时候选 = max_results×3 上限100]
    SE --> EG{嵌入 Key 已配置?}
    EG -- 是 --> H[kNN + BM25 并联召回]
    EG -- 否 --> B[BM25 单路 degraded]
    H & B --> RR{rerank provider 已配置?}
    RR -- 是 --> RR1[重排 + similarity_threshold 过滤<br/>阈值只作用于 rerank 分]
    RR -- 否 --> RR2[降级按召回序, 阈值不生效]
    RR1 & RR2 --> HY[回 MySQL hydrate 事实源<br/>保序, 已删节点静默跳过]
    HY --> T[截断 max_results<br/>返回 nodes + profiles + request_id]
```

边界：跨库/跨用户数据不可见由检索强制 filter 与管理端两层隔离查询谓词双保险（他库资源一律 404 不泄露存在性）；节点过期只在查询期过滤，物理清理由凌晨任务完成。

---

## 15. MCP 双协议调用链（M20 / M22）

对应：`McpOriginValidationFilter` / `McpServerEngine` / `KnowledgeMcpController` / `MemoryMcpController` / `McpArgumentBinder`（契约 M22）。

两个端点共用同一引擎：`POST /api/v1/knowledge/mcp`（`Bearer kb-sk-*`，工具 knowledge_search / knowledge_chat）与 `POST /api/v1/memory/mcp`（`Bearer kb-mk-*`，memory_* 六工具）。MCP 是 REST 之外的第二种 transport——请求进 Controller 前先过 Origin，再进入与 REST 完全相同的鉴权/限流/审计链。

### 15.1 现代发现与旧版握手共存时序

```mermaid
sequenceDiagram
    autonumber
    participant A as MCP 客户端(Claude Desktop/Cursor/自研 Agent)
    participant O as McpOriginValidationFilter
    participant F as ApiKeyAuthFilter / MemoryKeyAuthFilter<br/>(与 REST 完全同一条身份链)
    participant C as KnowledgeMcpController / MemoryMcpController
    participant E as McpServerEngine(无状态, 每 Controller 一实例)
    participant S as KnowledgeApiService / MemoryApiService<br/>(REST 孪生的同一服务层)

    A->>O: POST /api/v1/{knowledge|memory}/mcp<br/>Authorization + 可选 Origin
    alt Origin 存在且不在白名单
        O-->>A: 403 无 body
    else Origin 合法或不存在
        O->>F: 放行
    end
    Note over F: 鉴权/app_scope/库绑定/令牌桶限流/审计<br/>失败 → 401/429 信封同 REST
    F->>C: 放行(principal 挂 request attribute)
    C->>E: handle(body, transportHeaders, executor 绑定 principal)
    alt 现代 server/discover
        Note over E: 校验 _meta + Version/Method 头<br/>每请求独立，不读取历史协商状态
        E-->>A: 200 supportedVersions + capabilities.tools<br/>+ resultType/serverInfo + TTL/cacheScope
    else 旧版 initialize
        E-->>A: 200 protocolVersion 协商(2025-03-26 / 2024-11-05)<br/>+ capabilities.tools + serverInfo
    else notifications/* (无 id)
        E-->>A: 202 无 body
    else ping
        E-->>A: 200 空对象 result
    else tools/list
        E-->>A: 200 工具目录；现代版附 resultType/serverInfo<br/>+ ttlMs=300000/cacheScope=public，名称稳定排序
    else tools/call
        E->>E: 工具名在目录中? arguments 为对象?<br/>否 → JSON-RPC error -32602
        E->>C: executor.execute(toolName, arguments)
        C->>C: McpArgumentBinder: treeToValue + jakarta Validator<br/>显式校验(tree 转换不触发 bean validation)
        C->>S: 调 REST 孪生的同一服务方法<br/>(记忆库: 库来自 principal, arguments 无法指定 library_id)
        alt 业务成功
            S-->>E: 响应 DTO(同 REST data 结构)
            E-->>A: 200 result: content[0].text(JSON 文本)<br/>+ structuredContent + isError:false<br/>现代版另含 resultType/serverInfo
        else 业务失败(BizException)
            S-->>E: 错误码 + 消息
            E-->>A: 200 result: isError:true<br/>content[0].text = "错误码: 消息"(工具结果平面)
        end
    end
```

### 15.2 双时代 HTTP 与失败平面裁决

```mermaid
flowchart TD
    Q[POST MCP] --> O{Origin 存在且非法?}
    O -- 是 --> O403[HTTP 403, 空 body]
    O -- 否 --> A[Key 鉴权/限流]
    A -- 失败 --> AR[HTTP 401/429, REST 信封]
    A -- 通过 --> P{现代标记存在?<br/>header / _meta / server/discover}
    P -- 现代 --> H{_meta 与三类标准头一致?<br/>版本受支持?}
    H -- 头错 --> H400[HTTP 400, -32020]
    H -- 版本错 --> V400[HTTP 400, -32022 + supported]
    H -- 通过 --> MM{现代方法已实现?}
    MM -- 否 --> M404[HTTP 404, -32601]
    MM -- 是 --> MT{工具与参数形态合法?}
    MT -- 否 --> P400[HTTP 400, -32602]
    MT -- 是 --> X[执行工具]
    P -- 旧版 --> J{body 是合法单 JSON-RPC 对象?}
    J -- 否 --> E1[HTTP 200, -32700/-32600]
    J -- 是 --> ID{有 id?}
    ID -- 无 id 且是 notifications/* --> N[202 无 body]
    ID -- 无 id 且非通知 --> E2[HTTP 200, -32600]
    ID -- 有 id --> M{旧版方法在表中?<br/>initialize/ping/tools/list/tools/call}
    M -- 否 --> E3[JSON-RPC error -32601]
    M -- 是 --> T{tools/call: 工具在目录?<br/>arguments 是对象?}
    T -- 否 --> E4[JSON-RPC error -32602]
    T -- 是 --> X
    X -- 正常返回 --> OK[200 result: isError:false<br/>content text + structuredContent 同源双形态]
    X -- BizException 业务失败 --> BE[200 result: isError:true<br/>text = 错误码: 消息<br/>参数不对/资源不存在/stream:true 等<br/>Agent 可读反馈, 自行修正后重试]
    E1 & E2 & E3 & E4 -.旧版协议违规平面.-> PL[客户端/框架处理<br/>HTTP 200]
    H400 & V400 & M404 & P400 -.现代协议违规平面.-> PL
    OK & BE -.工具结果平面.-> ML[调用方模型处理]
```

要点：协议错误仍由客户端框架修正，工具业务失败仍由模型读取 `isError: true` 后自纠；M22 只让现代协议的 HTTP 状态表达 transport 事实，不改变业务失败平面。`knowledge_chat` 的 `stream:true` 仍是 INVALID_PARAM 工具结果，流式生成继续走 REST SSE。

---

## 16. Confluence Cloud 增量同步（M23）

对应：`ExtSourceController` / `ExtSourceService` / `ConnectorRouter` / `ConfluenceCloudConnector` / `DocumentService`（契约 M23）。

```mermaid
flowchart TD
    R[登记 source_type=confluence<br/>Site URL + Space Key + email + API Token] --> V[validateConfig<br/>HTTPS 根地址、字段语义 fast-fail]
    V --> A[异步首同步 / 手动同步 / 定时同步]
    A --> S[GET spaces?keys=...<br/>Space Key → Space ID]
    S --> L[GET spaces/id/pages<br/>status=current，cursor 分页<br/>最多 cap + 1]
    L --> C{条数超过 cap?}
    C -- 是 --> P[仅处理前 cap 条<br/>源状态至少 PARTIAL<br/>禁止消失判定]
    C -- 否 --> F[完整列表<br/>允许标记未见旧 item 为 SKIPPED]
    P & F --> E{pageId:version<br/>等于 item.etag?}
    E -- 是 --> U[item = UNCHANGED<br/>不请求正文]
    E -- 否 --> T{绑定文档在回收站?}
    T -- 是 --> K[item = SKIPPED<br/>不写新版本]
    T -- 否 --> G[GET pages/pageId<br/>body-format=storage]
    G --> O[title 转义 + storage body<br/>物化 confluence/pageId.html]
    O --> D[DocumentService.upload<br/>普通版本/治理/解析/索引链路]
    D --> I[item = SUCCESS<br/>etag = pageId:version]
    G -- 单页异常 --> X[item = FAILED<br/>其余页面继续]
    U & K & I & X --> Z[汇总源 SUCCESS / PARTIAL / FAILED]
```

安全边界：JDK HttpClient 禁止自动重定向，body `_links.next` 与 HTTP Link header 都必须保持登记 Site URL 的同 origin 后才携带 Basic Token；列表/正文响应有体积上限。列表结构不完整、page 缺 id/version 或 cursor 循环直接判本轮失败，不能把上游异常翻译成“页面已删除”。
