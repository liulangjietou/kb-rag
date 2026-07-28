# M11 开发契约（内容治理 · 增量于 M1-M10 契约）

> 需求依据：知识库需求文档 §4.2（文档生命周期——本期补齐"入库≠可检索"的治理层：审核发布、有效期、回收站）。当前无用户体系，审核操作不记录"谁"，完整审计属权限体系后期（与 M10 同口径）；本期先把状态机、时间窗与可恢复删除做正确。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带错误码、lombok、CollectionUtils 判空、无魔法值、fast-fail 只在 Controller、不主动 commit）；web 枚举展示走 metaOf。

## 0. 范围与边界

- **本期做**：①文档审核发布（KB 级开关 `review_required`，DRAFT→PENDING_REVIEW→PUBLISHED/REJECTED 状态机，未发布不进检索）；②文档有效期（`effective_at`/`expires_at` 时间窗，窗外自动退出检索）；③回收站（删除改为可恢复的移入回收站，超期定时彻底清除，另设立即彻底删除端点）。
- **本期不做**：审核人身份与审批流（无用户体系）；chunk 级治理（文档级足够，chunk 已有 enabled 开关）；已发布应用版本快照的治理回溯（快照冻结语料是 M4c 的既定语义，见 §2.1）。
- **核心机制（一个卡点）**：治理三态全部收敛为 `ActiveVersionResolver` 的行过滤——在线检索的可见版本集只从这里出，一行 `trashed=0 AND publish_status='PUBLISHED' AND (effective_at IS NULL OR effective_at<=now) AND (expires_at IS NULL OR expires_at>now)` 即完成门控。**不写任何状态进 ES/Qdrant**：状态变更只翻 DB 行 + invalidate 缓存，无索引重建。有效期的时间性穿越（无人操作、窗口自然到/过期）由可见集缓存 TTL（5 分钟）兜底生效，属可接受延迟。
- **兼容红线**：
  - V13 存量文档默认 `publish_status='PUBLISHED'`、无有效期、不在回收站——升级后语料检索行为零变化；
  - `review_required` 默认 0，新上传自动 PUBLISHED——旧"上传即可检索"流程不变；
  - `DELETE /api/v1/documents/{docId}` URL 与响应不变，语义从"不可逆硬删"升级为"移入回收站（可恢复）"；原硬删行为由新端点 `DELETE /documents/{docId}/purge` 承接；
  - 已发布应用版本的快照上下文不经过 ActiveVersionResolver（M4c 冻结语义），治理变更不影响已发布快照的应答——这是特性不是缺陷（发布物答门禁测过的语料）。

## 1. 数据模型（Flyway V13，纯 ALTER 无新表）

| 表 | 变更 |
|---|---|
| t_kb_document | 增 `publish_status` VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED'（DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED）、`review_note` VARCHAR(512) NULL（最近一次驳回理由）、`effective_at` DATETIME NULL、`expires_at` DATETIME NULL（NULL=不设界）、`trashed` TINYINT NOT NULL DEFAULT 0、`trashed_at` DATETIME NULL；索引 `idx_kb_publish(kb_id, publish_status)`、`idx_kb_trashed(kb_id, trashed)`、`idx_trashed_at(trashed_at)`（清理扫描列） |
| t_kb_knowledge_base | 增 `review_required` TINYINT NOT NULL DEFAULT 0 |

- 不用 @TableLogic 的 `deleted` 做回收站：逻辑删行对所有 MP 查询不可见，"回收站列表"将无从查起；`trashed` 是业务态不是删除态。
- 回收站不清引擎数据：chunk/ES/Qdrant 原样保留，靠可见版本集把它挡在检索外——恢复才能是瞬时翻标志；引擎清理只发生在彻底删除（purge）。

## 2. kb-rag-server

### 2.1 检索门控（ActiveVersionResolver，唯一卡点）
- `load(kbId)` 的查询追加治理谓词（见 §0）；`snapshotBound` 上下文（已发布版本）不受影响
- 所有治理变更（审核状态、有效期、回收站进出、彻底删除）成功后 `invalidate(kbId)`，操作者在调试页立即可见效果；时间窗自然穿越靠 TTL 兜底

### 2.2 文档治理（DocumentGovernanceService，kb-app 新包 governance）
- 审核状态机：`DRAFT|REJECTED → PENDING_REVIEW → PUBLISHED|REJECTED`；PUBLISHED 为终态不可回退（下架用有效期或回收站，不走审核回退）；非法迁移 → INVALID_PARAM
  - `POST /api/v1/documents/{docId}/submit-review`：提交审核
  - `POST /api/v1/documents/{docId}/approve`：通过 → PUBLISHED，清空 review_note
  - `POST /api/v1/documents/{docId}/reject`：`{note}`（必填）→ REJECTED，回填 review_note
- 上传落点（DocumentService）：新建文档时按所属 KB 的 `review_required` 决定初始态（0→PUBLISHED，1→DRAFT）；**追加新版本不重置发布态**（审核的是文档准入，不是每个版本——版本内容治理由 M4a 版本机制覆盖）
- 有效期：`PUT /api/v1/documents/{docId}/validity`：`{effective_at, expires_at}` 均可空（空=清除该界）；两者都给时要求 `effective_at < expires_at`；允许设置过去的 expires_at（运营者要的就是立即下架）
- 回收站：
  - `DELETE /api/v1/documents/{docId}`：`trashed=1` + `trashed_at=now`（幂等：已在回收站 → INVALID_PARAM）
  - `GET /api/v1/kb/{kbId}/trash?page=&size=`：回收站分页列表，trashed_at 最新优先（分页默认/上限 20/200）
  - `POST /api/v1/documents/{docId}/restore`：翻回 `trashed=0`、清 trashed_at（仅回收站内可恢复）
  - `DELETE /api/v1/documents/{docId}/purge`：彻底删除，复用原 DocumentService 硬删链路（chunk+引擎副本+版本+标注+文档行）；仅回收站内可 purge（两段式防误删）
  - 定时清理：`@Scheduled` 每日 purge `trashed_at` 早于 `kb.governance.trash-retention-days` 的文档，单批 ≤ `trash-purge-batch-size` 个文档（purge 含引擎删除，重操作按文档数限批）；失败仅 error 日志不重抛
- KB 开关：`PUT /api/v1/kb/{kbId}/governance`：`{review_required}`；GET /kb 响应增 `review_required`
- 存量查询隔离：文档列表（DocumentService.list）与上传归并查找（findLogicalDocument）排除 `trashed=1`——同名文件在回收站时新上传建新文档，不给回收站里的文档追加版本

### 2.3 配置键（application.yml 接环境占位符，KbProperties.Governance 承载）
- `kb.governance.trash-retention-days=30`（TRASH_RETENTION_DAYS）
- `kb.governance.trash-purge-batch-size=100`（TRASH_PURGE_BATCH_SIZE）
- `kb.governance.trash-purge-cron=0 10 4 * * *`（TRASH_PURGE_CRON）
- `kb.governance.trash-purge-enabled=true`（TRASH_PURGE_ENABLED）

### 2.4 单测（必须，精确断言）
- 状态机：全部合法迁移 + 全部非法迁移（PUBLISHED 不可再审、PENDING 不可重复提交、reject 必带 note）；review_required=0/1 上传初始态；追加版本不重置发布态
- 有效期：区间校验（effective>=expires 拒）、单边清除、设置后 invalidate 被调用
- 回收站：trash 幂等拒绝、restore 仅回收站内、purge 仅回收站内且委托硬删链路、列表只出 trashed 行
- 定时清理：保留期内不删、批大小边界、disabled 短路
- 可见集：ActiveVersionResolver 过滤谓词生效（trashed/未发布/窗外文档的 version 不出现在可见集）

## 3. kb-rag-web

- 文档列表（KbDetailPage documents tab）：
  - 新列：发布状态 Tag（PUBLISH_STATUS_META 走 metaOf；驳回态 Tooltip 展示 review_note）、有效期（未设不显示；已过期/未生效给 warning 色提示）
  - 行操作按状态机出现：提交审核（DRAFT/REJECTED）/ 通过·驳回（PENDING_REVIEW，驳回弹窗必填理由）/ 设置有效期（弹窗，两个 DatePicker 可清空）
  - 删除按钮文案与确认语改为"移入回收站（可在回收站恢复，N 天后自动彻底删除）"
- 知识库详情新增 tab **回收站**：列表（文件名/移入时间/原状态）+ 行操作"恢复"/"彻底删除"（danger 确认）
- KB 治理开关：documents tab 工具栏 Switch"新文档需审核后发布"（PUT governance）
- api 层：document.ts 增 submit-review/approve/reject/validity/restore/purge/trash 列表与 governance 开关；types.ts 增 PublishStatus，KbDocument 增 publish_status/review_note/effective_at/expires_at/trashed_at，KnowledgeBase 增 review_required

## 4. kb-rag-deploy（收尾）

- OpenAPI kb-server.yaml：文档 schema 增治理字段 + 新增 8 个端点；版本升 0.12.0-m11
- .env.example 增 TRASH_RETENTION_DAYS/TRASH_PURGE_BATCH_SIZE/TRASH_PURGE_CRON/TRASH_PURGE_ENABLED；CHANGELOG 记录（含 DELETE 语义变更的醒目说明）

## 5. 验收

1. review_required=0 上传 → 直接可检索；开关打开后上传 → DRAFT 且检索不到 → 提交审核并通过 → 可检索；驳回 → 列表可见理由
2. 给已发布文档设 expires_at=过去 → 5 分钟内（或触发 invalidate 的任意治理操作后）检索不到；清除有效期恢复
3. 删除文档 → 检索不到但回收站可见 → 恢复 → 立即可检索（无重建）；彻底删除 → 引擎/chunk/版本全清
4. 存量库升级后（V13 默认值）检索结果与升级前一致
5. `mvn -B -ntp verify` 全绿
