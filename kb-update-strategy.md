# kb-rag 知识库日常更新策略

> 问题：知识库搭建好后，每天还在新增/修改文档。每次都全量重建索引太慢；但只更新单个文档或某类文档，又担心 chunk 划分不一致、索引质量下降。基于当前 `kb-rag` 项目实现，给出可落地的更新方案。

---

## 一、核心原则

1. **日常增量更新，不要全量重建。**  
   只有 embedding 模型或向量维度发生变更时，才需要重建物理索引。

2. **划分不一致的本质是「配置不统一」或「元数据/引擎不一致」，不是局部更新本身的问题。**  
   只要同一知识库使用同一套切分/清洗/embedding 配置，新增文档生成的 chunk 与老文档在规则上就是一致的。

3. **用版本化 + alias 隔离 + 同步补偿兜底，保证更新过程可回滚、最终一致。**

---

## 二、当前项目已有的更新能力

| 场景 | 入口/类 | 行为 |
|------|---------|------|
| 单文档上传/更新 | `DocumentController.upload` → `DocumentService.upload` → `IndexPipelineService.execute` | 生成新的 `DocumentVersion`，异步解析、切分、嵌入、写入引擎 |
| 单文档重跑 | `DocumentController.reindex` | 重新提交当前文档版本到流水线 |
| 批量重跑 | `KnowledgeBaseController.batchReindexDocuments` | 批量重新索引 |
| 配置变更后重建 | `KnowledgeBaseController.rebuild` → `RebuildService` → `IndexPipelineService.rebuild` | 标记 `config_stale=1` 的文档，写新 chunk 后删除旧 chunk |
| 物理索引全量重建 | `IndexAliasManager` 新建索引 → bulk 写入 → alias 切换 | 仅在 embedding 模型/维度变更时使用 |
| 引擎同步补偿 | `IndexSyncCompensationService` | 定时扫描 `t_kb_chunk_index_sync`，修复 PENDING/FAILED |
| 清理索引 | `EngineChunkCleaner` / `IndexCleanupService` | 按文档或按知识库清理引擎索引 |

关键设计：

- `KbIndexConfig` 配置在**知识库维度**，`SplitterRouter` 统一路由到 `fixed_length`、`heading`、`parent_child`、`llm_semantic` 等策略。
- `VersionFingerprint` 记录解析、切分、清洗指纹，配置相同才复用历史 chunk。
- `submitAfterCommit` 保证 MySQL 事务提交后才把任务交给异步 worker，避免 worker 读到未提交数据。

---

## 三、局部更新会不会导致索引划分不一致？

### 不会不一致的情况

同一知识库、同一套 `KbIndexConfig` 下：

- 新增文档按当前统一规则切分；
- 老文档 chunk 保持原样；
- 检索时通过 `ActiveVersionResolver` 过滤当前激活版本；
- chunk 粒度、overlap、清洗规则一致。

### 会不一致的情况

| 情况 | 后果 | 项目中的处理方式 |
|------|------|------------------|
| 中途修改知识库切分策略（如 `fixed_length` → `parent_child`） | 老 chunk 与新 chunk 边界/粒度混杂 | 触发 `rebuild`，给所有文档打 `config_stale` 标记，批量重新切分 |
| 不同文档使用不同清洗规则或 embedding 模型 | 向量空间、chunk 内容不一致 | `VersionFingerprint` 保证配置不同则重新走流程，不混用 |
| 绕过系统直接操作 ES/Qdrant | MySQL 与引擎状态不一致 | `IndexSyncCompensationService` 定时扫描补偿 |

---

## 四、日常更新推荐实践

### 1. 按文档维度增量上传

每天新增或修改的文档，直接调用上传接口走单文档流水线：

- 项目会基于内容哈希判断是否重复；
- 重复文件通过 `VersionArtifactReuser` 复用历史解析产物和 chunk，避免重复 embedding；
- 只有真正内容变化或配置变化时才重新计算。

### 2. 用「版本激活」做原子发布

每次上传生成新版本后，先不切换 `current_version_id`：

- 等整条流水线完成、索引写入成功后再激活；
- 用户检索时要么看到完整旧版本，要么看到完整新版本，不会查询到半成品；
- 如果新文档有问题，可快速回滚到上一版本。

### 3. 切分策略保持知识库级别统一

- 尽量不要给单个文档或某类文档单独配置切分参数；
- 如果业务上确实需要不同粒度（如法规按条款切、手册按章节切），建议**拆分为多个知识库**，每个知识库配一种切分策略，检索时按场景路由；
- 避免把不同规则的 chunk 塞进同一个索引。

### 4. 配置变更后显式触发 rebuild

当修改了切分策略、清洗规则、overlap 大小等配置时：

- 通过 `KnowledgeBaseController.rebuild()` 触发配置重建；
- 项目会标记 `config_stale` 并批量处理，比手动一个个重新上传更可靠。

### 5. 依赖同步补偿机制兜底

- `IndexSyncCompensationService` 会扫描 `t_kb_chunk_index_sync` 的 PENDING/FAILED 状态；
- 日常更新只要保证 MySQL 事务与异步 worker 提交顺序正确即可，剩余不一致由补偿任务自愈。

### 6. 定期做一致性巡检，而非定期全量重建

建议每周跑一次一致性检查：

- 对比 `t_kb_chunk` 中 active 的 chunk 数量与 ES/Qdrant 中实际文档数量；
- 发现缺失或多余时，使用 `EngineChunkCleaner` / `IndexCleanupService` 做定向修复；
- 不要无脑全量重建。

---

## 五、更新方式选择流程

```text
今天需要更新知识库？
│
├─ 只是新增/修改了几份文档
│   └─ 单文档上传/更新，走版本化流水线
│
├─ 改了知识库的切分、清洗、embedding 配置
│   └─ 触发 rebuild，让系统批量重跑 stale 文档
│
├─ 换了 embedding 模型或向量维度变了
│   └─ 全量重建物理索引 + alias 切换
│
└─ 发现检索结果和预期对不上
    └─ 先查 t_kb_chunk_index_sync 是否有 FAILED/PENDING
       ├─ 有 → 等补偿或手动局部重跑
       └─ 无 → 再决定是否局部重跑或全量重建
```

---

## 六、总结

「局部更新导致索引划分不一致」本质上不是局部更新的问题，而是**配置统一性**和**元数据/引擎一致性**的问题。

当前 `kb-rag` 项目通过以下机制已经能较好地解决：

- 知识库级别统一配置
- 文档版本化
- 物理索引 alias 隔离
- `config_stale` 批量 rebuild
- `IndexSyncCompensationService` 同步补偿

**日常操作建议：**

- 优先单文档增量上传；
- 保持配置稳定；
- 配置变更时显式触发 rebuild；
- 偶尔做一致性巡检；
- 全量重建只留给 embedding 模型变更这类必须重建向量的场景。
