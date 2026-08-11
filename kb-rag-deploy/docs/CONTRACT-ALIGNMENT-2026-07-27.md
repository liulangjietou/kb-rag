# 前后端契约对齐扫描（2026-07-27）

Author: owlzhangfq@gmail.com

一期 M1–M7 与二期 M8/M9 全部合并后，真实使用两天暴露了 8 处 web↔server 的字段形状/语义错位。
八处逐一看下来，成因是同一个：web 由代理并行开发时，按 `M*-CONTRACTS.md` 的散文描述推断请求/响应
形状，写下 `ASSUMPTION` 注释，此后从未与 server 定版核对。本次做全量扫描，把每个端点的两侧形状
摆到一起比对，并对运行实例（127.0.0.1:20000）只读实测取证。

## 0. 最重要的一条结论

**`docs/openapi/kb-server.yaml` 早已定版，且绝大多数 schema 与 server 实现一致——web 从未据它校验过。**

被 web 猜错的地方，OpenAPI 里基本都写对了：`GraphTask` 的 `type`/`error_message`、`ApiKey` 的
"prefix 即完整展示串"、`app_scope` 的"空数组表示授权全部应用"、`ToggleChunkResult`/
`SplitChunkResult` 这两个独特的返回形状——一条不落地记着，而 web 侧对应位置写的是 `task_type`/
`fail_reason`、`prefix + last4`、`=== null`、`KbChunk[]`。

**因此后续约定：web 侧新增/修改任何接口封装，以 `docs/openapi/kb-server.yaml` 为准，而不是契约
散文。** 散文描述的是意图，OpenAPI 描述的是形状。

反过来，OpenAPI 自身也有 6 处欠账（见 §3），本次一并修正——web 的部分错误正是照着这些错误条目
抄的（如 `DocumentChunk` 的 kb_id/doc_id、`KnowledgeBase` 的 updated_at）。

## 1. 功能性错位（用户可见，已修）

修向裁决一律为**以 server 已合并形状为准改 web**；本次扫描未发现 server 侧缺陷。

| # | 端点 | 字段 | server 侧 | web 侧 | 影响面 |
|---|------|------|-----------|--------|--------|
| 1 | `PUT /kb/{kbId}/index-config` | `chat_aggregation.window_overlap` | `ChatAggregationParams` 三字段，按整份对象替换 | 类型只有两字段 | **每次保存索引配置都把 M8 的重叠滑窗静默重置为 0**，且指纹变化连带触发全库重建 |
| 2 | `GET /kb/{kbId}/graph/summary` | `latest_task.type` / `.error_message` / `.progress` | `type`、`error_message`、`progress` | 读 `task_type`、`fail_reason`，无 progress | **图谱抽取失败原因永不显示**。实例上确有 FAILED 任务（"graph extraction requires a configured chat model"）被这样吞掉 |
| 3 | `GET /api-keys` | `last4` | 不存在——`prefix` 本身已是 `kb-sk-58e086…5a4a`，表里只存哈希 | 声明 `last4: string` 必填并三处渲染 | 列表/审计筛选/调试页均显示 `kb-sk-58e086…5a4a****undefined` |
| 4 | `GET /api-keys` | `app_scope` 空语义 | `scopeOf` 把 null 列映射为 `[]`，**空数组=授权全部应用** | ApiDebugTab 判断 `=== null` | 对未限定应用的 Key 误报"预计 403 APP_ACCESS_DENIED" |
| 5 | `POST /apps/{id}/versions` | `config.chat_model`、`config.gate` | `AppConfigSnapshot` 含两者，按整份 body 固化快照 | 类型与表单均无 | **"改个检索参数再建版"会把上个版本的生成模型与门禁阈值清成默认值** |
| 6 | `POST /auth/logout` | 整个端点 | 存在，`TokenStore.revoke` 真正吊销 | 从未调用，只清本地 token | 登出后 JWT 到期前仍然有效 |

第 1、5 两条同源：**server 对这两个配置对象都是整份替换，web 少一个键就等于把它清零。**
这类"部分更新语义"的错位比字段名写错更隐蔽——不报错，只是悄悄改掉了用户配置。

实测取证：第 2 条的真实 `latest_task` JSON、第 3/4 条的真实 `prefix`/`app_scope` 取值、第 6 条用
一个临时会话验证 revoke 后 `/auth/me` 返回 401，均已确认。

## 2. 形状/语义错位（类型撒谎，已修）

- **分片三个写操作各返回不同形状**：`merge` 返回单个 chunk、`split` 返回 `{chunks}`、
  `toggle` 返回 `{changed_chunk_ids}`——web 一律标成 `KbChunk[]`。调用点都只 await 不取值，
  故无运行时故障，但类型是假的。
- **`KbChunk` 的 kb_id/doc_id 根本不存在**：服务端不重复下发路由已决定的信息。
- **`disabled_child_ids` 两处位置不同**：分片列表是顶层字段，检索响应在 `metadata` 内。web 此前
  认定"服务端没这个字段"，改为客户端从当前页派生，落下"父子片分到不同页就漏报"的已知缺陷——
  而服务端一直按整个文档版本算好了给。
- **幽灵 `updated_at`**：服务端全局 `default-property-inclusion: non_null`，只有 SourceMapping /
  IkDictEntry / App / AppVersion 真的暴露该列；web 在 8 个类型上声明了它。
- **被丢弃的响应字段**：上传态的 `duplicated`/`version`（三分支判定结果）、chat-imports 的
  `skipped`（跳过的语音消息）、门禁的 `gate_reason_message`（拦截原因）、审计的
  `app_id`/`endpoint`/`override_keys`/`error_code`、model-status 的 `dimension`、IkDict 的
  `remark`、app 的 `released_version`、preview 的 `doc_id`/`process_status`、activate 的
  `rollback_mode` 等。其中前三项属于"信息本该给用户却被吞掉"，已顺带接入界面。
- **30 余处 `ASSUMPTION` 注释**逐条与 server 核对后改写为已验证结论。绝大多数猜对了，
  `gate_run_ids` 的 `[candidate, baseline]` 顺序也经 `ReleaseGateService` 确认成立。

## 3. OpenAPI 自身的欠账（已修，版本升 0.10.1-m9）

| schema | 问题 |
|--------|------|
| `DocumentChunk` | 多了服务端不下发的 kb_id/doc_id；缺 chunk_type/chunk_text_hash/disabled_child_ids |
| `Document` | 缺 created_at 与仅上传响应返回的 version_id/version/duplicated/duplicate_of_doc_id |
| `KnowledgeBase`/`EvalCase`/`EvalDataset` | 多了并不返回的 updated_at |
| `IkDictEntry` | 多了并不存在的自增 id；缺 remark |
| `ChatImportPreviewData` | 缺 skipped |
| `DocumentPreview`/`PreviewImage` | 缺 doc_id/process_status 与 page_no/kind/status |
| `IndexConfig` | 缺 split_strategy 与 embedding_model（两者服务端都返回）；新增 `SplitStrategy` 枚举 |

同时修订 `M4c-CONTRACTS.md` 的 "列表(prefix+末4位)" ——这句措辞被读成两个字段拼接，正是错位 3
的直接诱因，现改为明确"展示串就是单个 prefix 字段，不存在独立末四位字段"。

## 4. 能力缺口（4 项，已于同批次补齐）

不属于契约错位，是 server 早已提供、web 一直够不着的能力。四项已全部实现（kb-rag-web
`feat/close-capability-gaps`）。

### 4.1 切分策略无界面 → 已加选择器

**先纠正本报告初版的一处失实：`split_strategy` 并非"六种取值"。** server 的 `SplitStrategy`
枚举实际只有两个成员：

| code | 含义 | 前置条件 |
|------|------|----------|
| `fixed_length` | 定长切分（默认） | 无 |
| `llm_semantic` | LLM 语义切分 | **需已配置对话模型**，否则 INVALID_PARAM |

需求文档描述的六种切分方式只落地了这两种；`parent_child` 是正交的独立开关，不属于该枚举。
界面按枚举给两个选项（不做自由输入——非法值会被 `requireSplitStrategyUsable` 拦成
INVALID_PARAM），未配置对话模型时 `llm_semantic` 禁选。

> **2026-08-11 后记（本报告为 07-27 快照，正文不改，此处标注现状）**：本节两处结论已被推翻。
> ①枚举现有五个成员——M14 交付了 separator/heading/page 三个策略却漏登记进枚举，于是本节描述的
> `requireSplitStrategyUsable` 恰好成了拦路的那道闸：实现类与前端表单都在，配置就是保存不进去，
> 三个策略整整一批是死代码；②`parent_child` 仍不属于该枚举，但**并不正交**——两级切分是定长策略与
> 自身组合，不按 `split_strategy` 取策略，故开启父子分片时该字段仅允许 `fixed_length`。
> 详见 `M14-CONTRACTS.md` §4（v1.1 修订条）与需求文档 v1.19 变更记录。

### 4.2 生成模型 / 门禁阈值无编辑入口 → 已加

`chat_model` 为选填输入，留空即跟随服务端默认（经 `ChatProviderFactory` 按名解析）；`gate` 为
开关 + `min_hit_rate`/`min_recall` 两个 0-1 阈值。**绝对阈值只在首次发布时作为放行依据**——已有
正式版时门禁走同语料双跑，与此处阈值无关，界面上已写明这一点。

两者进入表单后，此前为防丢失而做的"从上个版本原样透传"即被取代：快照的每个分支现在都由表单
往返，语义比隐式携带更清楚。

### 4.3 两个够不着的端点 → 已接入

- `DELETE /documents/{docId}`：文档列表加删除按钮，二次确认写明连同**全部版本与分片**（含两个
  引擎中的副本）一并删除且不可恢复。
- `POST /retrieval-feedback`（需求 §4.5 结果好/坏反馈）：结果卡片加点赞/点踩。**注意这是服务端
  有意的只记日志端点**（M4b-CONTRACTS.md §2 与偏离 §8：payload 无 dataset_id，BAD 无处安放且本期
  无消费方），GOOD 的真正落地路径是既有的"收进评测集"。界面提示已写明，避免误以为点赞会生成
  case。

### 4.4 index-config 响应被丢弃 → 已使用

PUT 返回的 `{stale_documents, fingerprint}` 现在用于提示"N 篇文档标记为待重建"。该计数只有服务端
算得出，客户端要复现就得重新列举并比对全部文档。
