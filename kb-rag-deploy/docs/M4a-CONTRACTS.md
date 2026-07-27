# M4a 开发契约（文档版本与分片标注 · 增量于 M1/M2/M3 契约）

> 需求依据：知识库需求文档 §4.1（文档级版本管理）、§4.5（标注功能）、§10-M4a。M1-M3 已交付并合并，各仓 main 为基线。
> 全局约定沿用 M1-CONTRACTS §0（端口 20000/20001/20002、英文注释、**每个类必须带 `@author owlzhangfq@gmail.com`**、日志仅 info/error 英文带错误码占位符、lombok、CollectionUtils 判空、无魔法值、fast-fail 一处、LLMentor 代码红线、**不要 git commit / 不要切分支**）。
> 前端约定：枚举展示一律走 `metaOf`，禁止直接索引映射表（白屏事故约定）。

## 0. 已有基线（不要重复实现，直接复用）

- `t_kb_document_version` 表、`DocumentVersionStatus`（BUILDING/BUILD_FAILED/READY/ACTIVE/ARCHIVED）、`DocumentVersionActivator.activate`
- `VersionFingerprintFactory`：parse/chunk/embedding 六元组指纹已可计算
- `ChunkIndexWriter`（M3）：chunk → 双引擎写入与 `t_kb_chunk_index_sync` 登记；`EngineChunkCleaner`：引擎侧删除（after-commit 语义）
- `IndexPipelineService`：parse → 图片 → 清洗 → 切分 → 嵌入 → 双写；`rebuild` 已支持文档内 chunk 原子替换
- **`t_kb_annotation` 尚未建表**（M1 未含），M4a 建 Flyway **V4**

## 1. 文档级版本管理（kb-rag-server）

### 1.1 重复上传生成新版本
- `POST /api/v1/kb/{kbId}/documents` 现有逻辑扩展：以 `(kb_id, file_name)` 判定是否为同一逻辑文档（聊天记录走 M3 的 `source_key`，本条不改）
  - 不存在 → 建文档 + 版本 `1.0`（现状不变）
  - 已存在 → 建**新版本**，版本号按需求 §4.1 规则：`content_hash` 变化 → major+1 且 minor 归零；`content_hash` 不变而 parse/chunk/embedding 任一指纹变化 → minor+1；hash 与三项指纹全同 → **不建新版本**，返回既有版本并在响应标注 `duplicated=true`
  - 新版本构建期间**旧激活版本继续服务**（现有 chunk 不动，检索仍按激活版本过滤）；构建成功后走 `DocumentVersionActivator` 切换，原激活版本**退位回 READY**（需求 §4.1 状态机，非 ARCHIVED）
- **六元组复用**：新版本与历史版本比对四要素（content_hash / parse 指纹 / chunk 指纹 / embedding 版本），命中则复用对应阶段产物——最小实现：`content_hash` + parse 指纹相同则复用 MinIO 中的 `parsed.json` 不重调 parser；chunk 指纹也相同且 embedding 版本相同则直接复制上一版 chunk 行（含向量同步记录）而不重嵌入。复用命中写 info 日志。

### 1.2 版本管理 API
| 端点 | 说明 |
|---|---|
| `GET /api/v1/documents/{docId}/versions` | 版本列表（倒序）：`{version_id, version, status, content_hash, created_at, changelog, active, chunk_count, rollback_mode:INSTANT\|REBUILD}` |
| `POST /api/v1/documents/{docId}/versions/{versionId}/activate` | 切换激活版本；`rollback_mode=INSTANT`（目标为 READY，分片仍在）→ 同步原子切换并立即返回；`REBUILD`（目标为 ARCHIVED，分片已清理）→ 建 REBUILD 任务从解析产物重建，返回 `{task_id}`，完成后自动切换 |
| `GET /api/v1/documents/{docId}/versions/{versionId}/activate-impact` | 切换前影响预检（供确认页）：`{stale_annotation_count, affected_eval_case_count:0, rollback_mode, needs_rebuild}` —— 评测 case 计数 M4a 恒返回 0（评测集属 M4b），字段先占位保证前端不用改 |
- `rollback_mode` 判定：目标版本 status=READY 且其 chunk 行仍存在 → INSTANT；status=ARCHIVED 或 chunk 已清零 → REBUILD

### 1.3 保留策略与归档
- 参数 `doc.version.retain-count`（默认 **3**，取值 1-20，进 KbProperties 与 application.yml）
- 激活切换成功后触发异步清理：该文档**非激活版本**按 created_at 倒序保留前 N 个为 READY，超出的置 ARCHIVED 并删除其 chunk 行 + 引擎文档 + `t_kb_chunk_index_sync` 记录（复用 `EngineChunkCleaner`），**保留 MinIO 原件与 parsed.json**（这是 REBUILD 回退的依据）
- **归档保护占位**：需求 §4.1 要求"被未下线应用版本快照引用的版本禁止归档"，应用版本属 M4c——M4a 在清理前调用一个 `VersionPinChecker` 接口（M4a 实现为恒返回空集的默认实现，M4c 接入真实快照引用），保证 M4c 接入时不用改清理逻辑

### 1.4 内容哈希去重提示
- 上传时若 `(kb_id, content_hash)` 在**其他文档**下已存在 → 响应 `data.duplicate_of_doc_id` 提示重复（需求 §4.1：仅提示、不共享物理分片，仍照常入库）

## 2. 分片标注（kb-rag-server）

### 2.1 四种操作
统一走"MySQL 事实源先行 → 重嵌入 → 双引擎同步"管线（需求 §4.5，复用 M3 的 `ChunkIndexWriter`/`EngineChunkCleaner`）：

| 端点 | 语义 |
|---|---|
| `PUT /api/v1/chunks/{chunkId}` | 编辑正文：原地更新 content 与 `chunk_text_hash`，重嵌入，双引擎覆盖写 |
| `POST /api/v1/chunks/{chunkId}/toggle` | 启/禁用：`{enabled}`；仅改 enabled 并同步引擎字段（**不重嵌入**，正文未变） |
| `POST /api/v1/chunks/merge` | 合并：`{chunk_ids:[...]}`（同文档同版本、seq 连续、同 parent_id）→ 生成一个新 chunk（正文按 seq 顺序拼接，seq 取最小值），旧 chunk 软删 + 引擎删除 |
| `POST /api/v1/chunks/{chunkId}/split` | 拆分：`{split_offsets:[int...]}`（字符偏移，升序、落在正文内）→ 生成 N+1 个新 chunk（seq 用最小值起的小数序或重排该文档 seq），原 chunk 软删 + 引擎删除 |
- **父片的合并/拆分**：级联重切其子片并重排序号（需求 §4.5）
- 每次操作写一行 `t_kb_annotation`（见 §2.4），操作幂等键为 chunk_id + 操作类型 + 内容哈希，重复提交不重复建记录
- 校验（fast-fail 于 Controller 层）：跨文档/跨版本、seq 不连续、offset 越界、合并少于 2 片、拆分点为空 → `INVALID_PARAM`

### 2.2 父子分片下的禁用语义（需求 §4.5 一期口径）
- 禁用子片 → 该子片不参与召回（引擎侧 enabled 过滤已有）
- 父片因其他子片命中而返回时：**整段父片正常返回**，但 `metadata.disabled_child_ids` 标注其中被禁用的子片
- KB 级开关 `index_config.hide_parent_with_disabled_child`（**默认 false**）：打开后父片若含任何被禁用子片则该父片不返回（需要严格合规的场景用）
- 禁用父片 → 级联禁用其全部子片
- （二期：按子片偏移精确剔除文本段，M4a 不做）

### 2.3 标注与文档版本的关系（需求 §4.5）
- `t_kb_annotation` 绑定 `document_version_id`，新版本**不自动继承**
- 新版本构建完成后：对存在人工标注的文档，`GET /api/v1/documents/{docId}/annotations/pending-review` 返回旧版本标注清单（含原文摘录、操作类型、是否已在新版本重做）
- **禁用类标注按 `chunk_text_hash` 完全相同自动继承**（开关 `index_config.inherit_disable_annotation`，**默认 true**，精确匹配、不做相似度匹配——需求 v1.6 已明确删除相似度辅助迁移）
- 激活切换确认页的 `stale_annotation_count` 即"旧版本有标注但新版本未继承/未重做"的条数（§1.2 影响预检字段）

### 2.4 数据模型（Flyway V4）
| 表 | 列 |
|---|---|
| t_kb_annotation | `annotation_id` UK、`kb_id` IDX、`doc_id` IDX、`document_version_id` IDX、`chunk_id`（操作目标，合并/拆分记首个）、`annotation_type`(EDIT/TOGGLE/MERGE/SPLIT)、`payload` JSON（合并的来源 id 列表、拆分偏移、编辑前后摘录、启用状态）、`chunk_text_hash`（继承判定用，IDX）、`inherit_status`(NOT_INHERITED/AUTO_INHERITED/REDONE)、`operator`（M4a 恒为 admin）+ 通用列 |
- `t_kb_chunk` 无需加列（parent_id / enabled / chunk_text_hash 均已存在）
- `index_config` 增两个布尔：`hide_parent_with_disabled_child`(默认 false)、`inherit_disable_annotation`(默认 true)；两者**不参与** parse/chunk 指纹（不改变分片产物，不应触发 config_stale）

### 2.5 新增单测（必须）
版本号递增三分支（hash 变/指纹变/全同不建版）、六元组复用命中与未命中、rollback_mode 判定（READY vs ARCHIVED）、保留策略选出待归档集合、合并校验（跨版本/seq 不连续/少于 2 片）、拆分偏移校验（越界/未升序/空）、合并与拆分的 seq 与 parent 关系正确性、禁用不触发重嵌入而编辑触发、父片禁用级联子片、`chunk_text_hash` 相同的禁用标注自动继承、`hide_parent_with_disabled_child` 开关对返回的影响

## 3. kb-rag-web 增量
- **知识库详情 → 文档行**新增"版本"入口，打开**版本管理抽屉**：版本列表（版本号/状态 Tag/分片数/创建时间/变更说明/当前激活标记）、每行"激活"按钮；点击先调 `activate-impact`，弹确认框展示 `rollback_mode`（即时切换 / 需重建）与 `stale_annotation_count` 警告，确认后执行；REBUILD 模式显示任务进度（轮询文档状态）
- **分片详情抽屉升级为标注工作台**：每个分片卡片提供编辑（行内文本域 + 保存）、启/禁用开关（禁用态灰显）、勾选多片后"合并"按钮、单片"拆分"按钮（在文本域内点击位置插入拆分标记，或输入偏移列表）；父片显示 `disabled_child_ids` 提示
- **索引配置抽屉**增两个开关：`hide_parent_with_disabled_child`、`inherit_disable_annotation`
- **待复核提示**：文档存在 pending-review 标注时，版本管理抽屉顶部 Alert「旧版本有 N 处人工标注未在新版本重做」并可展开清单
- 类型层：`DocumentVersion`、`RollbackMode`、`ActivateImpact`、`Annotation`、`AnnotationType`、`InheritStatus`；新增枚举的展示走 `metaOf`

## 4. kb-rag-deploy 增量
- `docs/openapi/kb-server.yaml` 同步 M4a 全部端点与 schema
- `.env.example` 增 `DOC_VERSION_RETAIN_COUNT=3`（对应 `doc.version.retain-count`）
- `CHANGELOG.md` 记 M4a
- **同一 PR 内按实际实现回补需求文档**（铁律②）：若实现对 §4.1/§4.5 有偏离，在需求文档变更记录中升版说明；无偏离则只在 M4a 契约末尾追加"实现期修订"小节说明"无偏离"

## 5. 验收清单（实现完成后主会话执行）
1. 同名文件二次上传（内容改动）→ 生成 major+1 新版本，旧版本退位为 READY，检索只命中新版本内容
2. 同名文件二次上传（内容完全相同、配置未变）→ 不建新版本、响应标注 duplicated
3. 版本列表显示 rollback_mode；对 READY 版本点激活 → 即时切换，检索内容回到旧版
4. 保留策略：连续上传 5 个版本 → 非激活版本仅保留 3 个 READY，其余 ARCHIVED 且 chunk 清零；对 ARCHIVED 版本激活 → 走 REBUILD 任务并最终切换成功
5. 分片编辑 → MySQL、ES 内容一致（用 ES 直查确认，这是 M3 修复过的同类风险点）
6. 分片禁用 → 检索不再命中该片；父子模式下父片仍返回但带 disabled_child_ids；打开 `hide_parent_with_disabled_child` 后该父片不返回
7. 合并两片 → 新片正文为顺序拼接、旧片引擎侧已删除（ES 直查确认无残留）
8. 拆分一片 → 生成 N+1 片、seq 与 parent 关系正确、引擎侧旧片已删
9. 文档升新版本 → 旧版禁用类标注按 chunk_text_hash 自动继承；其余标注出现在 pending-review 清单且 `stale_annotation_count` 计数正确
10. 零 Key 模式下上述全部可用（编辑后 embedding_status=SKIPPED，不阻塞）

> **注意**：用户的 DASHSCOPE_API_KEY 当前失效，重嵌入的真实向量链路无法验收，验收 5/7/8 以 ES 全文侧内容一致性为准，向量侧以 `t_kb_chunk_index_sync` 状态与零 Key 的 SKIPPED 语义验证。

## 6. 实现期修订（主会话审查与 E2E 验收后回补，与代码一致）

### 6.1 四项偏离（已接受）
1. **chunk 行复用仍会重嵌入**（§1.1）。契约要求复用向量同步记录，但两个引擎 port 都是只写投影、无"读回向量"能力，MySQL 也不存向量。实现为：复用 `parsed.json` + 复制 chunk 行（真正省掉 parse 与 split），配置了 Provider 时向量重算；零 Key 下完全零成本。为这一个场景给两个引擎加向量读回接口，代价高于单文档嵌入费用。
2. **两个引擎的 `enabled` 标记均原地镜像**（§2.1）。ES 侧（含 lite 模式，即当前默认部署）走 partial update 精确生效；向量侧通过 payload 原地更新同样精确生效，既不触碰向量、也不需要重新嵌入，因此"不重嵌入"的承诺得以保持。**正确性不依赖它**：检索链路已加固（见 6.2），即便引擎侧标记一时陈旧，禁用片最多浪费一个召回名额，不会泄漏。
3. **语义校验落在应用服务入口而非 Controller**（§2.1）。"同文档/同版本/seq 连续"是对数据库行的断言、"offset 越界"需要库里的正文长度，Controller 拿不到。做法：Controller 用 bean validation 拦请求形状（body 缺失、数组为空），语义校验作为单一闸门放在每个操作入口——仍是全链路一处，无重复防御。
4. **父片启用也级联子片**（§2.2）。契约只规定"禁用父片级联禁用子片"。实现为对称：只降不升会让重新启用的父片永久不可召回且毫无提示。需要单独排除的子片再单独禁用。

### 6.2 实现期发现并修复的一个真实隐患
`RetrievalService.loadChunks` 原本在 SQL 里过滤 `enabled=1`，于是"行被禁用"与"行不存在"在引擎命中侧完全同形，孤儿自愈逻辑会把一个**合法的禁用分片**从两个引擎里删掉——之后重新启用它将永久召回不到，直到下次重建。现已分离：缺失行照旧自愈删除，禁用行只从排序中剔除并记 info。这也是偏离 2 能安全成立的前提。E2E 已验证：禁用后不召回、重新启用后立即可召回。

### 6.3 E2E 验收中修正的一处前后端缺口
`GET /documents/{docId}/annotations/pending-review` 首版响应缺 `inherit_status`（前端要显示继承状态 Tag），另缺 `kb_id/doc_id/chunk_text_hash/operator`。已补齐为"表列全集 + 派生的 version 与 excerpt"。

### 6.4 遗留（不阻塞 M4a 验收）
- PARSED 级复用只复用 `parsed.json`，图片资产行仍按新版本重跑 VLM；解析指纹相同意味着文本代理必然一致，可进一步复制 `t_kb_image_asset` 行
- `InheritStatus.REDONE` 语义为"被新版本同类操作取代"；若日后需要区分"自动继承"与"被取代"两种终态，需在 §2.4 增枚举值
