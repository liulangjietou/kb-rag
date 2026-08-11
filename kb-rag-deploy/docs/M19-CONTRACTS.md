# M19 开发契约（企业级记忆库 · 增量于 M1-M18 契约）

> 需求依据：对标阿里云百炼「记忆库」。外部智能体应用（Agent / 机器人 / 工作流）需要为最终用户维护**跨会话的长期记忆**：对话经 LLM 抽取成记忆片段与结构化画像，后续会话按语义召回拼进提示词。本期在 kb-rag-server 内新增 memory 域，智能体通过 **Memory Key（`kb-mk-*`）** 调用开放 API，控制台新增「记忆库」一级菜单承载管理与调试。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带错误码、lombok、CollectionUtils 判空、无魔法值、fast-fail 只在 Controller、不主动 commit）。

## 0. 范围与边界

- **本期做**：①记忆库/记忆片段规则/画像规则/记忆节点/用户画像/Memory Key 六实体的管理 API 与控制台页面；②开放 API 六端点（Add/Search/List/Update/Delete/GetUserProfile），语义与百炼 AddMemory 等一一对应；③LLM 抽取（片段 + 画像）、auto_update 记忆演化（PRO 版抽取对旧记忆窗口内合并去重）、过期策略（按规则天数，查询期过滤）；④检索：向量 + BM25 混合，可选意图识别 / 查询改写 / 重排三开关；⑤Memory Key 独立鉴权过滤器链（签发/禁用/轮换/删除，QPS 令牌桶）。
- **本期不做**：短期记忆（原始对话轮次按 session 存取——调用方自维护会话上下文，属有意边界）；过期节点物理清扫任务（查询期过滤已保证不可见，列表页管理视角刻意可见）；记忆节点的引擎双写补偿任务（MySQL 是事实源，ES 副本随写随建，失败面见 §2.2）；多实例分布式限流（沿用 M4c 进程内令牌桶的单实例口径）。
- **隔离红线（本期核心不变式）**：三层隔离**都是查询谓词而不是约定**——①**租户**（M16 行级围栏，见 §1.4，V21 补课）；②一把 Memory Key 只绑定一个记忆库（应用级隔离），请求无需也无法指定 library_id；③库内按 `user_id`（记忆实体）隔离。每条语句都带 `library_id` 过滤、实体级语句再加 `user_id`；交集之外的节点对调用方等于不存在，所以越权一律 **404 而不是 403**。
  三层各管一段，不可互相替代：租户层管"哪个组织的库"（只对管理端成立），Key 绑定管"哪个应用的库"，`user_id` 管"哪个终端用户的记忆"。
- **兼容红线**：纯新增——V20 六张新表 + 两个权限码、新 Controller/过滤器/端口，存量端点与行为零变化；V21 补租户列同样零迁移（存量库由列 DEFAULT 落入默认租户，单租户部署行为不变）；`WebMvcConfig.PUBLIC_PATHS` 增 `/api/v1/memory/**`（该面鉴权由 MemoryKeyAuthFilter 承担，不走管理台拦截器）；无新增环境变量与配置键（嵌入/重排/对话模型全部复用既有 Provider 配置，零 Key 降级语义见 §3.4）。

## 1. 数据模型（Flyway V20，6 张新表 + 权限种子）

| 表 | 要点 |
|---|---|
| `t_kb_memory_library` | 记忆库（`ml*`）；`tenant_id`（V21 补，见 §1.4）；同名校验在服务层（逻辑删除下唯一索引会挡住重建），随租户列落地后同名判定收缩为**租户内**唯一 —— 两个租户各建一个「客服记忆库」是正常业务，全局唯一会让后建的那个租户建不出来 |
| `t_kb_memory_fragment_rule` | 记忆片段规则（`mfr*`）；`instruction_type`（DEFAULT 内置指令 / CUSTOM 自定义，CUSTOM 时 `instruction` 必填）、`auto_update`（1 时抽取合并更新旧记忆而非只追加）、`expire_days`（7/30/180，NULL 永不过期——存天数不存枚举串，写入时算 `expire_at`，语义在一列闭合）、`extract_version`（PRO 带旧记忆合并去重 / LITE 单次直抽）、`builtin`（建库预置「默认项目」规则，可编辑不可删除）；每库上限 50 条由服务层守 |
| `t_kb_memory_profile_rule` | 画像规则（`mpr*`）；`fields` 整体存 JSON 数组 `[{name,description,initial_value}]`（上限 50 个）——字段只随规则整体编辑读取，没有按字段查询的入口，不拆子表 |
| `t_kb_memory_node` | 记忆节点（`mn*`）；`source`（EXTRACTED / CUSTOM）、`meta_data`（调用方 JSON 原样存取）、`expire_at`（过期不再被检索）；唯一高频查询路径是（库，实体）翻页，索引 `idx_library_user` |
| `t_kb_memory_profile` | 用户画像；`attributes` 为已提取字段的 JSON 对象，未提取字段不落行、读取时回落规则 `initial_value`；`uk_rule_user` 唯一键——一实体一规则一份画像，抽取结果按此 upsert 合并 |
| `t_kb_memory_app_key` | Memory Key（行 ID `mak*`，明文 `kb-mk-*`）；与 `t_kb_api_key` 同一决策：只存 SHA-256 摘要 + 展示前缀，明文仅签发响应回传一次；`qps_limit` 令牌桶上限、`status` 禁用即刻生效、`last_used_at` 异步更新 |

权限种子：`memory:read`（看库/规则/记忆/调试检索）与 `memory:write`（建删库、改规则、管 Key）插入 `t_kb_permission`（module=MEMORY），并授予 `role_superadmin000` 与 `role_kbadmin00000`（沿 V16 授权口径）。

### 1.4 租户隔离（Flyway V21，M19 后修复）

**缺陷**：V20 建六张表时漏了 M16 的租户层。权限码只回答"这个账号能不能碰记忆库"，回答不了"能碰哪些"，于是多租户部署下任何租户持 `memory:read` 的账号能列出全部署的记忆库，持 `memory:write` 能改删其他租户的库、规则、记忆与 Memory Key。开放端不受影响（Key 绑库天然隔离），受影响的是管理端 23 个端点。

**补法与 M16 §1.1 取舍①同构**：

1. **只有 `t_kb_memory_library` 加 `tenant_id`**（VARCHAR(64) NOT NULL DEFAULT `'tnt_default0000000'` + `idx_tenant`，存量行由列 DEFAULT 划入默认租户、升级零迁移）。它是 memory 域的根聚合表，五张从属表（片段规则 / 画像规则 / 节点 / 画像 / Key）经 `library_id` 归属租户 —— 六张表全加列不叫隔离叫散弹枪，从属查询永远先过根表的租户行过滤。
2. **`KbTenantLineHandler.FENCED_TABLES` 增 `t_kb_memory_library`**，根表由 MyBatis-Plus 行级围栏自动拼租户条件（列表、详情、同名校验、建库 INSERT 的 tenant_id 注入全部随之生效，与 `t_kb_knowledge_base` 完全同构）。
3. **`MemoryLibraryGuard`：管理端带 `libraryId` 的 21 个入口先解析库**。这一条才是关键，单加列 + 进围栏是不够的：从属表不带 `tenant_id`，按 `rule_id` / `node_id` / `key_id` 直接寻址的入口（改删片段规则、改删画像规则、删节点、Key 的启停/轮换/删除）压根不查根表，围栏对它们形同虚设。守卫是一个独立 bean 而不是 `MemoryAdminService` 的方法 —— `MemoryAppKeyService` 需要同一个检查且是前者的依赖，反向边就是循环；同 `KbScopeGuard` 的形状与理由。检查放服务层不放 Controller：Controller 里的守卫只能护住有人记得加的那几条路径。
   剩下 2 个入口（库列表 `GET /`、建库 `POST /`）没有 `libraryId`，由围栏本体直接覆盖 —— 列表靠 SELECT 拼租户条件，建库靠 `TenantLineInnerInterceptor` 往 INSERT 补 `tenant_id`（服务层从不 `setTenantId`，与 `KnowledgeBaseService` 同构）。**这两条是全域唯一没有第二道防线的路径**：给 `MemoryLibraryMapper` 写一条绕开围栏的自定义 SQL、或挂 `@InterceptorIgnore`，它们的隔离就直接没了。
4. **开放端语义原样保留**：`MemoryKeyAuthFilter` 那条链上没有控制台主体，`ignoreTable` 整条跳过。这不是顺带的，是必须的 —— 那条线程上拼租户条件会把 Key 自己的库过滤掉，接口直接全灭。`MemoryAppKeyService.authenticate` 因此刻意不过守卫。

**受影响的语义**：记忆库同名校验从全局唯一收缩为租户内唯一（见 §1 表格）。其余行为零变化。

## 2. 端口与实现（六边形）

### 2.1 端口 `MemoryStore`（kb-domain `domain.port`）
- `upsert(MemoryDoc)` / `delete(nodeId)` / `deleteByRule(libraryId, ruleId)` / `deleteByLibrary(libraryId)` / `search(MemorySearchQuery)`；`MemoryDoc` 含 nodeId/libraryId/ruleId/userId/content/embedding/expireAt。

### 2.2 实现 `EsMemoryStore`（kb-infrastructure `search.es`）
- 单物理索引 `kb_memory_nodes_v1` 所有记忆库共用——隔离靠 filter 不靠索引边界（记忆节点体量远小于文档分片，不值得按库建索引）。
- **vector mapping 懒加载**：索引建立时不带向量字段，首个带 embedding 的写入到达时按其维度 putMapping——嵌入维度由 Provider 声明，建索引时未必已配置 Key。
- 检索：有向量走 kNN（cosine 映射到 [0,1]）+ BM25 并联；无向量（零 Key / provider 失败）**降级 BM25 单路**（BM25 分归一化 `s/(s+1)`）。所有查询强制注入 `library_id` + `user_id` filter 与过期过滤（`expire_at` 缺失或大于当前时刻），可选 `rule_id` filter。
- 已接受偏离：节点写 MySQL 后 ES 失败无补偿任务（不复用 `t_kb_chunk_index_sync`）——add 刻意不用事务（见 §3.3），单次调用最多丢尾部且调用方可见错误可重试；update/delete 后的 ES 失败留待后续里程碑补对账。

## 3. kb-rag-server

### 3.1 鉴权（`MemoryKeyAuthFilter`，第三条独立鉴权链）
- servlet 过滤器（`Ordered.HIGHEST_PRECEDENCE + 10`），`shouldNotFilter` 只放行非 `/api/v1/memory/` 前缀——与管理台拦截器、`ApiKeyAuthFilter` 三面互不干扰，理由与 M4c 拆分开放 API 过滤器相同：凭据形态、失败面、限流口径都不同。
- 认证：`Authorization: Bearer kb-mk-*` → SHA-256 摘要查 `t_kb_memory_app_key`；缺失/格式错 401 `INVALID_API_KEY`，禁用 401 `API_KEY_DISABLED`。限流复用 `ApiRateLimiter`（桶按 key_id 区分，两个 Key 家族的 ID 前缀不会撞），超限 429 `RATE_LIMITED`。认证通过后 `last_used_at` 异步 touch（尽力而为）。
- 过滤器在 `@RestControllerAdvice` 之外，同 M4c 自写统一错误信封。

### 3.2 开放 API（`MemoryOpenApiController` → `MemoryApiService`，6 端点）
| 能力 | 端点 | 语义要点 |
|---|---|---|
| AddMemory | `POST /api/v1/memory/add` | `messages` 与 `custom_content` 至少传其一；custom 直写（source=CUSTOM），messages 走片段抽取；`fragment_rule_id` 缺省用库内 builtin 规则；`profile_rule_id` 需伴随 messages，同步抽画像 |
| SearchMemory | `POST /api/v1/memory/search` | 可选 `intent_recognition`（判定无需召回直接返回空，profiles 照常返回）/ `rewrite` / `rerank` / `similarity_threshold`（只在 rerank 开启时生效）；`max_results` 1-100 默认 10 |
| ListMemory | `GET /api/v1/memory/memory_nodes` | （库，实体）分页倒序；**过期节点包含在内**——列表是管理视角，管理必须看见检索已看不见的东西 |
| UpdateMemory | `PATCH /api/v1/memory/memory_nodes/{nodeId}` | 替换 content（重嵌入刷新 ES 副本），meta_data 传了才改 |
| DeleteMemory | `DELETE /api/v1/memory/memory_nodes/{nodeId}` | 逻辑删行 + 删 ES 副本 |
| GetUserProfile | `GET /api/v1/memory/profiles` | 每条画像规则一项，未提取字段回落 `initial_value` |

### 3.3 AddMemory 抽取与演化（`MemoryExtractionService` + `MemoryPromptAssembler` + `MemoryExtractionParser`）
- **add 刻意不是一个事务**：抽取的 LLM 调用夹在写入之间，跨 LLM 往返持连接会在中等负载下耗干连接池。每个节点写入自身原子、ES 副本紧随其后，失败最多丢一次调用的尾部（调用方可见错误、可重试）。
- PRO 抽取 + `auto_update=1` 时：加载该（库，规则，实体）最近 50 条未过期旧记忆（`OLD_MEMORY_WINDOW`）随 prompt 下发，模型可对窗口内节点发出 UPDATE 指令（语义重复的旧记忆被覆盖，事件回传 `UPDATE`）；解析器只放行目标在窗口内的 UPDATE，找不到目标的指令不可能出现。LITE / `auto_update=0` 只追加。
- 画像抽取：按规则 `fields` 定义抽取 `{字段名:值}`，按（规则，实体）唯一键 merge 进 `t_kb_memory_profile`。
- 抽取/意图识别/改写全部走既有 `ChatProvider`：未配模型时抽取报错由信封透出，意图识别/改写**降级为直查/原 query**——缺模型只削弱效果，绝不失败检索。

### 3.4 SearchMemory 链路（`MemoryApiService.search`）
- 顺序：画像加载 → 意图识别（可选，veto 即返回空节点集）→ 改写（可选）→ `MemoryStore.search`（rerank 开启时候选 ×3、上限 100）→ rerank（可选，未配 provider 降级召回序；`similarity_threshold` 作用于 rerank 分）→ **回 MySQL 事实源 hydrate**（分数序保持，行已删则静默跳过）→ 截断 `max_results`。
- 嵌入失败/零 Key：写入与检索都降级 BM25 单路（`embedOf` 返回 null），写入绝不因嵌入失败而失败。

### 3.5 管理 API（`MemoryLibraryController` → `MemoryAdminService`/`MemoryProfileService`/`MemoryAppKeyService`，23 端点）
- 基路径 `/api/v1/memory-libraries`，全部 `@RequiresPermission(MEMORY_READ/WRITE)`，写操作 `@AuditedOperation(module=MEMORY)`。
- **带 `libraryId` 的 21 个入口一律先经 `MemoryLibraryGuard.requireLibrary(libraryId)`**（§1.4），路径上的 `libraryId` 是唯一的归属事实源；库不在本租户 → 404，调用在触到任何从属语句之前就结束。另 2 个（库列表、建库）无 `libraryId`，由行级围栏直接覆盖。
- 库 CRUD（5）：建库自动预置内置「默认项目」片段规则；删库级联清 Key/规则/节点/画像 + `deleteByLibrary` 清 ES（刻意非事务，与 add 同理）。
- 片段规则（4）/ 画像规则（4）：每库各限 50 条；builtin 规则可编辑不可删除；删片段规则级联删其节点与 ES 副本；画像行物理删除（软删行会占住规则×实体唯一键）。
- 记忆数据（4）：`GET /{id}/entities`（实体分页）、`GET /{id}/nodes`（节点分页，含过期）、`DELETE /{id}/nodes/{nodeId}`、`GET /{id}/profiles`。
- 检索调试（1）：`POST /{id}/search-debug`——控制台以管理台身份复用开放 API 的 search 语义。
- Memory Key（5）：list / create（明文仅此一次）/ status 启停 / rotate 轮换（旧密钥即刻失效）/ delete。

## 4. 依赖与配置

- **无新增 Maven 依赖、无新增环境变量与配置键**：LLM 三类调用复用 `ChatProvider`/`EmbeddingProvider`/`RerankProvider` 及其零 Key 装置；限流复用 `ApiRateLimiter`；ES 复用既有 client。

## 5. kb-rag-deploy（收尾）

- **OpenAPI kb-server.yaml 升至 `0.19.0-m19`**：新增 `memory-library`（管理端 23 路径）与 `memory-open-api`（开放端 6 路径）两个 tag 及全部 schema。
- CHANGELOG 新增 M19 条目（server/web/deploy 三仓）；`FLOWS.md` 增第 14 节记忆库两张流程图；`ARCHITECTURE.md` 增量修订（表数/Flyway 版本/memory 域/端口表/第三鉴权链）。
- 调用方文档：主仓 `docs/记忆库接入指南.md`（接口详解、典型接入伪代码、错误码速查、最佳实践）。

## 6. kb-rag-web

- 新增一级菜单「记忆库」（`/memory`，`BulbOutlined`，`memory:read` 可见，位于「应用中心」与「评测中心」之间）。
- `pages/memory/MemoryLibraryListPage.tsx`：库列表（关键词搜索/分页/新建/编辑/删除）。
- `pages/memory/MemoryLibraryDetailPage.tsx` 五 Tab：片段规则（`FragmentRulesTab`，builtin 标签、指令类型/auto_update/过期/抽取版本表单）、画像规则（`ProfileRulesTab`，字段列表编辑器）、记忆数据（`EntitiesTab`，实体 → 节点下钻、节点删除、画像查看）、检索调试（`SearchDebugTab`，三开关 + 阈值 + 分数展示）、Memory Key（`MemoryKeysTab`，创建明文一次性弹窗 + 复制、启停/轮换/删除）。
- `api/memory.ts` 23 个函数与管理端 23 端点一一对应；`auth/permissions.ts` 增 `MEMORY_READ`/`MEMORY_WRITE`；`types.ts` 增 memory 系列类型。

## 7. 单测（已交付，随分支）

- `MemoryAdminServiceTest` / `MemoryApiServiceTest`（kb-app）：库/规则 CRUD 上限与级联、add 双入口与 auto_update 演化、search 意图 veto/降级/hydrate 跳过已删行、越权 404。
- domain 纯函数测试：`MemoryExtractionParser`（UPDATE 目标窗口校验、非法输出跳过）、`MemoryKeyFactory`（前缀/摘要/展示形态）、`MemoryPromptAssembler`。
- 租户隔离（V21 随修补入，§1.4）：`MemoryAdminServiceTest` 两例覆盖库不在本租户时读写入口全数 404 且**从属表一条语句都不发**（`selectOne` 全部 `never()`）；`MemoryAppKeyServiceTest` 两例覆盖 Key 五个管理入口同样被拒、且 `authenticate` 不经守卫（开放端语义不被改坏）；`KbTenantLineHandlerTest` 钉住 `t_kb_memory_library` 进围栏、五张从属表不进、无控制台主体时整条跳过。
- 全量 `mvn -B -ntp verify` 1134 测试通过；web `npm run lint` 新增代码零告警。

## 8. 验收

1. 控制台建库 → 详情页签发 Memory Key → `curl` 带 `Bearer kb-mk-*` 调 `/api/v1/memory/add`（messages）→ 返回抽取节点；再 `search` 同实体命中、换 `user_id` 不命中；换另一库的 Key 访问该节点 → 404。
2. 片段规则开 `auto_update` + PRO：两次 add 语义重复内容 → 第二次事件为 `UPDATE`、节点总数不增。
3. 规则 `expire_days=7` 写入的节点改库表把 `expire_at` 拨到过去 → search 不再召回、控制台节点列表仍可见。
4. 零 Key 部署：add 直写（custom_content）成功、search 走 BM25 单路仍有结果；配 Key 后新写入带向量、混合检索生效。
5. Key 禁用后调用 → 401 `API_KEY_DISABLED`；轮换后旧密钥立即 401；把 `qps_limit` 设 1 连打 → 429 `RATE_LIMITED`。
6. 无 `memory:read` 的角色登录 → 菜单不可见、直贴 `/memory` 原地 403；`memory:write` 缺失时列表页只读。
7. 租户隔离（§1.4）：建租户 B 的管理员账号（持 `memory:read`+`memory:write`）→ 登录后记忆库列表看不到租户 A 的库；拿租户 A 的 `libraryId` 直贴详情页 404；带上 A 的 `ruleId`/`nodeId`/`memoryKeyId` 直调改删接口同样 404。升级既有单租户部署：全部存量库落入默认租户，控制台行为与升级前无差。开放端回归：A 的 Memory Key 调 add/search 照常成功（那条链不拼租户条件）。
