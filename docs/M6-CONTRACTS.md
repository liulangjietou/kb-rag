# M6 开发契约（索引快照发布 · 增量于 M1-M5 契约）

> 需求依据（唯一事实源 docs/知识库需求文档.md，**实现前必须精读**）：§4.7 版本快照第二步（发布时对关联库物理索引建不可变快照、**快照创建时点晚于门禁双跑、早于正式版生效**、回滚可恢复历史知识状态、接受存储成本翻倍）、§4.7 版本可见集（快照固化 document_version_id 集合写入 t_kb_app_version，检索强制过滤按调用上下文取值）、§4.4（可见集取值：经应用版本调用取快照固化集合，管理台调试取当前激活）、§4.1/§4.7 归档保护（被引用的 document_version 禁止归档）、§4.3 索引三段命名与别名组件（快照=建新物理索引+registry 注册，同一套原子操作；**完整模式 ES 省略嵌入段命名 kb_{id}_bm25_{快照段}**）、§4.7 M6 双跑口径（双跑在候选即将固化的那份索引上执行）、§10-M6 验收（回滚后检索恢复历史状态**且召回非空**；备份-删库-恢复-检索演练；10 万分片压测 P95 不劣化超 20%）。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带错误码、lombok、无魔法值、fast-fail 一处、不 commit/不切分支）；web 枚举展示走 metaOf。

## 0. 核心设计定版（实现前先读，偏离须申报）

1. **快照原语**：`FulltextStore`/`VectorStore` 端口各增 `snapshotIndex(sourceIndex, targetIndex)`。ES 双实现走 `_clone`（源 `index.blocks.write=true` → clone → 源解锁、目标解锁；段级硬链接，毫秒级），Milvus 实现为批量读写拷贝（同步执行，超时 `kb.app.snapshot-timeout-ms` 默认 300000）。零 Key 验收仅覆盖 lite/ES；Milvus 真实快照待 full 模式部署后补验。
2. **命名与注册**：快照物理索引 `kb_{kbId}_{嵌入段}_s{seq}`（seq 为**库级**快照自增序列，从 registry 现有行推导 max+1）；full 模式 ES BM25 为 `kb_{kbId}_bm25_s{seq}`。快照索引**不挂别名**，按物理名直查；registry 记一行（status=生效，schema_version 同源索引）。live 索引与别名完全不动。
3. **发布流程扩展**（改 M4c release，八状态机**不变**）：门禁通过/force 之后、状态切 RELEASED 之前，同步执行：对 kb_refs 每库每引擎建快照 + 固化 `visible_version_ids`（各库当前激活 document_version 集合，按库分组）+ 写 `index_snapshots`。**任一库快照失败→发布中止**，版本停留在原状态（GATE_PASSED/GATE_LOG_ONLY）可重试，已建的本次快照索引回滚删除（不留半程）。双跑（在此之前）一律走 live 别名——"门禁所测索引与发布所用索引同一份"由克隆的不可变性保证。
4. **检索调用上下文**（RetrievalService 扩展，复用 M5 多库编排，禁止复制链路）：`RetrievalCommand` 增 per-kb 的 `indexOverride` 与 `visibleVersionIdsOverride`。取值三分支：
   - 经 **RELEASED** 版本调用（对外 search/chat；rollback 重新 release 的历史版本同理）→ 该版本 `index_snapshots` 的物理索引 + `visible_version_ids` 固化集合；
   - 经 **TESTING** 版本灰度调用、chat-preview、管理台调试、评测 → live 别名 + 当前激活集合（现状不变；TESTING 无快照，快照只在 release 时创建）;
   - **旧 RELEASED**（M6 前发布，无快照数据）→ 回退 live 别名 + 当前激活（历史数据形态，不是故障，**不记 degraded**）。
5. **快照缺失降级**：RELEASED 调用发现快照索引不存在（如被误删）→ 回退 live 别名 + 当前激活集合，degraded += `snapshot_index_missing`，error 日志带错误码。需求文档 §4.8 degraded 枚举**同 PR 增补该值**（deploy 收尾，升 v1.12）。
6. **快照路径关闭孤儿自愈**：孤儿/禁用自愈、双写补偿、config_stale 重建一律只作用于 live 别名。快照索引召回的 chunk 若 MySQL 行已不存在（标注合并/拆分或版本清理所删）→ **直接丢弃出排序 + info 日志，绝不触发引擎删除**——自愈按 live 语义删快照数据是跨索引误伤（本条为正确性红线，单测必须覆盖）。
7. **禁用广播**：分片禁用/启用是全局质量止血，除 live 别名外同步广播到该库 registry 中所有生效快照索引（ES partial update 便宜；Milvus 沿 M4a 决策不镜像、靠检索后 MySQL enabled 过滤兜底——快照路径此兜底仍生效）。内容性操作（编辑重嵌入/合并/拆分/删除）不碰快照。
8. **归档保护（VersionPinChecker 落地）**：新增 `AppVersionPinChecker` 替换 `NoPinVersionPinChecker`：document_version 被**任意未删除应用版本**（含 SUPERSEDED，只要其 visible_version_ids 尚未随快照清理而清空）引用即 pinned，`VersionRetentionService` 跳过并 info 日志。SUPERSEDED 也算：其快照虽含独立索引数据，但 chunk 事实源仍在 MySQL，归档清理会破坏回滚承诺。
9. **快照清理**：@Scheduled 任务（复用既有调度惯例）：每应用保留最近 `kb.app.snapshot-retain-count`（默认 3）个 **SUPERSEDED** 版本的快照，更旧的：删物理索引（每引擎）→ registry 置待清理 → 清空该版本 `index_snapshots` 与 `visible_version_ids`（由此解除 pin）。RELEASED 的快照永不清理。
10. **Flyway V7**：`t_kb_app_version` 加两列——`visible_version_ids` JSON（`{kb_id:[document_version_id,...]}`）、`index_snapshots` JSON（`[{kb_id, engine, physical_index_name}]`）。无新表（registry 复用 M1 表）。

## 1. kb-rag-server（opus）
- §0 全部条款；发布/回滚/清理归 kb-app appcenter 与 index 包，快照原语归 infrastructure 各 Store 实现
- 新配置键（application.yml 接环境占位符，KbProperties 承载）：`kb.app.snapshot-retain-count=3`（APP_SNAPSHOT_RETAIN_COUNT）、`kb.app.snapshot-timeout-ms=300000`（APP_SNAPSHOT_TIMEOUT_MS）
- AppVersionResponse 增 `index_snapshots`、`visible_version_kb_count`/每库版本数摘要（web 展示用，字段命名可在实现时定版但须回报）；DocumentVersionResponse 增 `pinned`（boolean，被应用版本引用中）与 `pinned_by`（引用它的 app_version_id 列表，管理端提示用）
- `RetrievalService.visibleVersionIds` 的 TODO(M4) 缓存**本期做**：按库 Caffeine 缓存 + 版本激活切换时失效（10 万分片压测前置条件；快照路径不走缓存，固化集合直接来自快照列）

### 单测（必须，精确断言）
快照命名序列（s1→s2、full 模式 bm25 段）；release 顺序（门禁后快照、快照失败停留原状态且已建索引被回滚删除、成功才 RELEASED）；调用上下文三分支 + 快照缺失降级（degraded=snapshot_index_missing 且结果仍出自 live）；旧 RELEASED 兼容不记 degraded；AppVersionPinChecker（引用即 pin、SUPERSEDED 未清理仍 pin、清理后解除）；清理任务保留 3 个且 RELEASED 永不清理；禁用广播到全部生效快照索引；快照路径 absent 行丢弃不触发自愈删除；可见集缓存激活切换失效

## 2. kb-rag-web（sonnet）
- 版本列表/详情：展开行增"索引快照"块（每库物理索引名、可见集文档版本数）；M6 前旧版本显示"无索引快照，调用走实时索引"提示（走 metaOf 惯例的空态文案，不硬编码判断散落）
- 文档版本列表：pinned 标记（Tag + tooltip 列出引用的应用版本）。归档为服务端自动行为、web 无手动入口（M4a 契约 §1.3），故仅做展示；pinned 版本由服务端 VersionPinChecker 拦截清理，无 UI 侧禁用项
- degraded 标签表（statusMeta.ts）增 `snapshot_index_missing`
- 类型层同步 index_snapshots/visible 摘要/pinned 字段

## 3. kb-rag-deploy（主会话收尾）
- compose（lite+full）：ES 挂快照/备份卷；新增 `scripts/backup.sh`（mysqldump + ES 数据导出 + MinIO mc mirror，产物带时间戳目录）与 `scripts/restore.sh`；`docs/backup-restore.md` 含 RPO/RTO 说明与演练步骤
- `scripts/seed-bench.sh`（或 python）：零 Key 灌入 ≥10 万分片（直写 MySQL + ES bulk，跳过嵌入），供压测复用
- OpenAPI 0.7.0-m6（新字段）、CHANGELOG、.env.example 两个新变量、需求文档 v1.12（degraded 枚举 + §0.6/§0.7 两条款回写 §4.3/§4.4）、契约 §5 回补

## 4. 验收（主会话，零 Key 域）
① 发布 V1 → 快照 s1 建立（ES 索引存在、registry 行、两列固化）；上传新文档版本并激活 → 对外 search（V1）结果**不含**新内容、管理台调试**含**新内容——快照隔离实证
② 再发 V2（s2）→ rollback V1 → 对外检索恢复 V1 历史状态且召回非空（需求 §10-M6 主验收）
③ 归档保护：对被 pin 的 document_version 触发保留清理 → 被跳过；清理 SUPERSEDED 旧快照后 pin 解除
④ 误删快照索引 → RELEASED 调用降级 live + degraded=snapshot_index_missing
⑤ 旧 RELEASED（M5 期发布的 SQL 构造版本）兼容调用，不记 degraded
⑥ 备份-删库-恢复-检索可用演练一次（backup.sh/restore.sh）
⑦ seed 10 万分片 → M2 同口径 benchmark，P95 劣化 ≤20%，数据记入契约
> Key 失效限制：涉真实嵌入的快照场景（向量路快照检索）待 Key 恢复补验；零 Key 域快照索引同样克隆 dense_vector 字段结构，不影响结构验证。

## 5. 实现期修订（完工后回补）

**主会话中途裁决**：web 侧"归档/清理入口对 pinned 版本禁用"一句系契约笔误——归档自 M4a 起即纯服务端自动行为、web 无手动入口，已改为仅做 pinned 标记展示（§2 原文已修）。

**server 申报偏离/增补六条，主会话裁决全部接受**：①port 增 `dropIndex`/`indexExists`（§0.3 回滚删索引、§0.9 清理、§0.5 缺失探测三条款的必要原语）；②`VersionPinChecker` 主原语改 `pinnedBy(docId)`（返回 app_version_id 映射），`pinnedVersionIds` 变默认方法派生——pinned 与 pinned_by 同源一次查询，不可能漂移；③新增契约外配置键四个：`RETRIEVAL_VISIBLE_VERSION_CACHE_TTL_MINUTES=5`、`RETRIEVAL_VISIBLE_VERSION_CACHE_MAX_SIZE=1000`、`APP_SNAPSHOT_CLEANUP_CRON=0 15 4 * * *`、`APP_SNAPSHOT_CLEANUP_ENABLED=true`（沿用既有缓存/调度键式惯例）；④快照兼容读归 `AppVersion` 实体（充血模型，DTO/检索绑定/清理三方共用同一解析）；⑤`MilvusVectorStore` 改手写构造器注入超时配置；⑥测试辅助 `MybatisLambdaCache` 解决纯 Mockito 下 LambdaUpdateWrapper 元数据缺失。实现要点：Milvus 拷贝用 queryIterator（避 offset 窗口截尾）；ES `_clone` 的源锁在 finally 必解（快照失败只赔发布不冻结知识库）；清理顺序=删索引→registry 待清理→清空两列（先清列会开出"pin 已解、快照仍在"的窗口）；降级判定置于可见集空短路之前（避免降级信息被吞）。

**响应字段定版**：`AppVersionResponse.index_snapshots=[{kb_id,engine,physical_index_name}]`（无快照=[]）、`visible_version_kb_count=[{kb_id,version_count}]`（不透出原始 id）、`DocumentVersionResponse.pinned/pinned_by`。

**需求文档 v1.12 同步**：degraded 枚举补 `snapshot_index_missing`；§4.4 测试版可见集口径修正（v1.6 原文"正式版/测试版取快照固化集合"与"快照仅发布时创建"自相矛盾，测试版实际取当前激活集合——实现期发现的需求内部矛盾，按实现语义定版）；§4.3 补快照索引运维语义条款（自愈边界+禁用广播+保留清理）。

**验收结果（2026-07-26，零 Key 域，20010 临时实例）**：§4 七项全过——①V1 发布产生 `kb_{id}_none_s1`（ES 实索引+registry 行+两列固化，visible_version_kb_count=[{kb,1}]），新文档版本激活后对外 search（V1 快照）不含新内容、管理台调试含新内容（快照隔离实证；附注：重复上传后新版本自动激活属 M4a 既有语义，验收脚本显式激活被正确拒绝）；②V2 发布 s2 后 rollback V1，检索恢复历史状态且召回非空；③旧 document_version pinned=true 且 pinned_by 指向应用版本；④误删 s1 后 RELEASED 调用 degraded=[snapshot_index_missing,…] 且结果出自实时索引；⑤M5 期旧格式 RELEASED 兼容调用不记 snapshot degraded；⑥备份-删库-恢复演练（backup.sh 三段 ok → DROP DATABASE+删全部 kb_* 索引 → restore.sh 恢复 301 行 chunk/15 索引 → 检索命中非空）；⑦seed 10 万分片（9.1s 灌入）后同口径压测：100 分片基线 P50=19.9/P95=33.7/P99=128.8ms → 10 万分片 P50=18.3/P95=39.0/P99=159.8ms，**P95 劣化 15.9% ≤ 20%**，200/200 全 2xx。单测 606 项（新增 53）全过。Key 恢复后补验：向量路快照检索、Milvus 快照（需 full 模式）。
