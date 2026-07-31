# Changelog

本项目的版本记录遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 约定，按里程碑分节记录（对应 `kb-rag-deploy/docs/M1~M15-CONTRACTS.md`）。

## [Unreleased]

### Added

**M19 · 记忆库控制台**（`docs/M19-CONTRACTS.md`）
- 新增一级菜单「记忆库」（`/memory`，`memory:read` 可见，位于「应用中心」与「评测中心」之间）：库列表页（`pages/memory/MemoryLibraryListPage.tsx`，关键词搜索/分页/新建/编辑/删除）。
- 库详情页（`MemoryLibraryDetailPage.tsx`）五 Tab：片段规则（builtin 标签、指令类型/自动更新/过期天数/抽取版本表单）、画像规则（字段列表编辑器：name/description/initial_value，上限 50）、记忆数据（实体 → 节点下钻、节点删除、画像查看）、检索调试（意图识别/改写/重排三开关 + 相似度阈值 + 分数展示）、Memory Key（创建明文一次性弹窗 + 复制、启停/轮换/删除，轮换提示旧密钥即刻失效）。
- `api/memory.ts` 23 个函数与管理端 23 端点一一对应；`auth/permissions.ts` 增 `MEMORY_READ` / `MEMORY_WRITE`；`types.ts` 增记忆库全套类型（`MemoryLibrary` / `MemoryFragmentRule` / `MemoryProfileRule` / `MemoryEntity` / `MemoryNode` / Memory Key 等）。

**M18 · 网页导入站点凭据（抓取需登录的站点）**
- 系统设置新增「站点凭据」Tab（`pages/settings/components/WebCredentialTab.tsx`）：按 host 配置抓取凭据，列表（认证类型标签、用户名/头名、secret 恒显星号、启停 Switch）+ 新建/编辑/删除。BASIC（用户名+密码）与 HEADER（任意请求头，覆盖 Bearer 与 Cookie）两种类型，表单按类型切换字段。
- secret 只进不出：接口不回传，编辑弹窗不回填、留空即保持原值，停启用不需要重新输入密码；host 与认证类型建后置灰不可改（换站点/换方式=删了重建）。
- `types.ts` 增 `WebAuthType` / `WebCredentialEntry` / `CreateWebCredentialRequest` / `UpdateWebCredentialRequest`；新增 `api/webCredential.ts` 四个端点封装。

**M17 · 网页导入 JS 渲染抓取**（`docs/M17-CONTRACTS.md`）
- 网页导入 Tab 登记表单新增「JS 渲染」 Switch（默认关，`render_js` 随 register 传入），带说明——开启后用无头浏览器渲染再抓取，适用于内容靠脚本加载的页面（如 Javadoc 框架页），更慢更耗资源。
- 来源列表新增「JS 渲染」列：行内 Switch 直接走 `PUT /web-sources/{id}`（传 `render_js`），与「定时同步」Switch 同交互模式；`updateWebSource` 由单传 `sync_enabled` 扩展为传 `UpdateWebSourceRequest`（`sync_enabled` / `render_js` 两可选字段，只改传入的那个）。
- `types.ts`：`WebSourceEntry` 增 `render_js: boolean`，`RegisterWebSourceRequest` 增可选 `render_js`，新增 `UpdateWebSourceRequest` 接口。

**M16 · 企业化：多租户 / 文档级权限 / SSO 三协议 / 操作审计**（`docs/M16-CONTRACTS.md`）
- 登录页单点登录扩展：按 `GET /auth/sso/providers` 渲染 OIDC / SAML / CAS 三个跳转按钮（整页重定向到后端免认证入口）；回调落地页解析 URL fragment（`#sso_token` 入会话、`#sso_error` 展示中文失败原因），解析后立即清理 fragment 避免令牌留在地址栏历史。
- 新增「租户管理」页（`pages/settings/TenantManagePage.tsx`，仅 `tenant:manage` 可见）：租户列表（内置标签 / 状态）、新建（code + 名称，code 建后不可改）、改名、启用停用（停用二次确认，提示该租户全部账号将无法登录）；无删除入口（后端就没有该端点）。
- 用户管理页租户维度：列表展示所属租户列，新建账号可选租户，新增「移户」操作（提示仅移动账号本身——其创建的知识库仍留在原租户；后端会清空其角色并吊销会话，需重新登录后在新租户内重新授角色）；来源标签新增 OIDC / SAML / CAS 三种。
- 文档可见性抽屉（`pages/kb/components/VisibilityDrawer.tsx`，`doc:review` 可见）：文档列表行操作入口，INHERIT / RESTRICTED 单选 + 受限时的授权角色多选；列表增受限标记列。
- 新增「操作审计」页（`pages/settings/OperationAuditPage.tsx`，仅 `audit:read` 可见）：倒序分页列表（模块 / 操作人 / 目标 id / 时间窗筛选）与详情抽屉（detail JSON 展示）；纯只读，无任何写操作入口。
- 反馈管理 Tab 增渠道筛选（控制台 / 开放接口）与渠道、终端用户标识列，开放渠道反馈同样可转评测集。

**M15 · 权限体系与单点登录**（`docs/M15-CONTRACTS.md`）
- 登录页双方式：「账号密码」与「单点登录」两个 Tab，后者根据 `GET /auth/sso-available` 决定是否渲染——未接入目录的部署看到的仍是原来那个单表单登录页。
- `AuthContext` 携带权限码、角色与可见库范围（来自 `/auth/me`），导航菜单与路由按权限收口：菜单隐藏未授权入口，直接贴地址进未授权页面渲染原地 403（不跳登录页）。
- 新增「用户管理」页：分页列表（关键词 / 状态 / 来源筛选，展示角色名与来源标签）、新建本地账号、改显示名/邮箱、启用停用、分配角色（多选全量提交）、重置口令、删除（二次确认）；针对自己的停用/改角色/删除按钮禁用。
- 新增「角色管理」页：角色列表与编辑抽屉——权限目录按模块分组的勾选网格 + 数据范围（全部库 / 指定库多选）；内置角色不给删除入口。
- 写操作按钮级收口：各业务页的新建 / 编辑 / 删除 / 审核 / 发布按钮按对应权限码隐藏。仅为减少无效点击，服务端依然每次都校。

**M12 · 网页导入**（`docs/M12-CONTRACTS.md`）
- 知识库详情新增「网页导入」Tab：URL 登记（登记即抓）、来源列表（最近同步四态 SUCCESS/UNCHANGED/SKIPPED/FAILED 标签 + 失败原因）、手动同步、定时同步开关、移除登记（二次确认，提示不删已导入文档）。
- 注：M13 为纯后端运维指标（Prometheus），无前端改动。

**M11 · 内容治理**（`docs/M11-CONTRACTS.md`）
- 知识库详情文档列表新增发布状态 / 有效期列与审核操作（提审 / 通过 / 驳回，驳回理由录入）、有效期设置 / 清除弹窗；知识库级「上传需审核」开关（governance）。
- 新增「回收站」Tab：已删文档列表、还原、彻底删除（二次确认）；文档删除按钮语义同步改为「移入回收站」。

**M10 · 检索反馈与洞察**（`docs/M10-CONTRACTS.md`）
- 知识库详情新增「反馈管理」Tab：反馈列表（有用 / 无用 + 原因）、转评测集（选择目标评测集）、忽略操作。
- 新增「检索洞察」Tab：明细分页（脱敏摘要 / 命中数 / 降级标记）与内容缺口报表（零命中率、Top 未命中 query 分组）。

**M9 · 标注迁移建议与图片检索输入**（`docs/M9-CONTRACTS.md`）
- 待复核工作台：pending 行展开显示相似度迁移建议（`suggestions`：分数 + 内容预览 + 「迁移到此分片」确认框）。
- 问答调试页与 API 调试页新增贴图/选图入力（`components/ImagePicker.tsx`，转 base64，张数/大小前置校验提示，权威校验在后端）。
- `degraded` 标签表新增 `image_understanding_unavailable`；父子分片检索结果新增 `redacted_child_count`（禁用子片精确剔除时）提示行。

**M8 · 导入映射与四格式聊天导入**（`docs/M8-CONTRACTS.md`）
- 系统设置新增「导入映射」tab：映射列表、新建、编辑 YAML 文本域、复制内置模板、删除自定义映射。
- 聊天记录导入向导：`profile` 下拉改为读映射列表，新增 TXT/HTML 两种格式项（原有 CSV/Excel 基础上）。
- 检索调试页结果卡片按既有 metadata 明细惯例展示近重复归并的 `merged_window_chunk_ids`。

**M7 · 知识图谱**（`docs/M7-CONTRACTS.md`）
- 知识库详情新增「知识图谱」tab：开关（`fusion=weighted` 时互斥提示）、抽取任务进度轮询、统计卡、实体列表（搜索 + 分页 + 来源分片数）、实体来源分片下钻抽屉。
- `GraphVisualization.tsx`：自实现 SVG 力导向图（Fruchterman-Reingold，50 节点上限，零外部依赖，刻意不引入 `@antv/g6`）。
- 检索调试页结果卡片新增图路明细行（关联度/跳数/命中实体）；`degraded` 标签表新增 `graph_route_unavailable`；索引配置编辑开启 `graph_enabled` 时对 `weighted` 融合选项置灰并提示原因。

**M6 · 索引快照与 pinned 标记**（`docs/M6-CONTRACTS.md`）
- 应用版本列表/详情展开行新增「索引快照」块（各库物理索引名、可见集文档版本数），M6 前旧版本展示「无索引快照，调用走实时索引」空态文案。
- 文档版本列表新增 pinned 标记（Tag + tooltip 列出引用它的应用版本），仅展示、无手动归档入口（归档为服务端自动行为）。
- `degraded` 标签表新增 `snapshot_index_missing`。

**M5 · 多知识库路由**（`docs/M5-CONTRACTS.md`）
- 应用配置编辑：单库选择改为多库列表（1..15，每行库选择 + 权重输入，支持增删行）；新增「知识库路由」开关与路由 Prompt 文本域。
- 应用版本详情/列表展示 `kb_refs` 摘要；检索结果卡片与 `applied` 信息条展示 `routed_kb_ids` 与命中节点所属库名。
- 新增 `utils/kbRefs.ts` 作为 `kb_refs`/旧版单 `kb_id` 快照的读侧归一化唯一入口（`resolveKbRefs`/`kbNameOf`）。

**M4c · 应用发布与开放 API**（`docs/M4c-CONTRACTS.md`）
- 新增顶级菜单「应用中心」：应用列表/新建；应用详情含配置编辑（知识库 + 检索参数 + 问答 prompt）、版本列表（状态机 Tag、发布/回滚、门禁双跑对比结果展示、`GATE_LOG_ONLY` 强制发布确认框）。
- 应用详情新增「API 调试」tab：用指定 API Key 对 search/chat 发真实请求（chat 支持 SSE 流式渲染），展示 curl 示例。
- 系统设置新增「API Key」管理 tab（创建弹窗一次性展示明文 + 复制、列表、禁用/轮换、scope 多选）与「审计日志」查询 tab（按 Key/时间/`target_stage` 过滤 + 调用统计）。
- 新增顶级菜单「问答调试」（`/chat`），走管理端 JWT 鉴权的 `chat-preview` SSE 流式问答。

**M4b · 评测体系**（`docs/M4b-CONTRACTS.md`）
- 新增顶级菜单「评测中心」，四个 tab：评测集管理（列表/新建/删除/导入 Demo 评测集）、标注工作台（case 增删改查、多轮 messages 编辑器、span 证据编辑）、证据复核工作台（待复核列表 + Top3 候选原文 + `REANCHOR`/`DEPRECATE`）、评测任务与报告（配置矩阵多选、提交前费用预估、报告指标对比表 + 分组切换 + 命中明细下钻 + CSV 导出）。
- 检索调试页新增「收进评测集」按钮，勾选结果卡片一键提交为评测 case。
- 知识库详情版本切换确认框展示真实 `affected_eval_case_count`（此前恒为 0 占位）。

**M4a · 文档版本与分片标注**（`docs/M4a-CONTRACTS.md`）
- 知识库详情文档行新增「版本」入口，打开版本管理抽屉：版本列表（版本号/状态/分片数/创建时间/变更说明/当前激活标记）+ 激活按钮（先查 `activate-impact`，弹确认框展示 `rollback_mode` 与待复核标注数）。
- 分片详情抽屉升级为标注工作台：编辑（行内文本域）、启/禁用开关、多选合并、单片拆分；父片显示 `disabled_child_ids` 提示。
- 索引配置抽屉新增 `hide_parent_with_disabled_child`/`inherit_disable_annotation` 两个开关；版本管理抽屉顶部展示跨版本待复核标注 Alert。

**M3 · 多模态解析与清洗**（`docs/M3-CONTRACTS.md`）
- 知识库详情索引配置抽屉扩展清洗规则分组（页眉页脚/水印正则/正则替换/Excel 表头/脱敏开关）与解析预览开关。
- 文档列表新增 `PENDING_CONFIRM` 状态标签、行内「预览确认」入口与顶部批量确认按钮；新增解析预览抽屉（markdown 纯文本渲染防 XSS、图片卡片缩略图 + VLM 文本代理、warnings 提示、确认入库/改规则重解析）。
- 知识库详情新增聊天记录导入向导（上传 → 会话匹配预览表格 → 全选确认导入）。
- 检索结果卡片按 `chunk_type` 展示 image/chat_log 标签与图片缩略图/会话元信息；系统设置新增「告警」tab（webhook、开关、三阈值、静默期、测试发送）；知识库列表空态新增「一键导入 Demo 知识库」按钮。

**M2 · 检索调试链路升级**
- 检索调试页参数面板按改写/召回/融合/重排/过滤/返回分组折叠（`fusion.mode` + `w_vec` 滑杆 + `rrf_k`、`score_threshold`、`metadata_filter` 编辑器含标签多选/会话 ID/发送人/时间范围）。
- 结果卡片展示各路原始分/归一化分/`fused_score`/`rerank_score`（存在时）、阈值作用类型标签、父子模式命中子片明细展开；顶部 `applied` 信息条（改写后 query、融合模式、阈值作用分数）。
- `degraded` 新增枚举值（`query_rewrite_timeout`/`rerank_timeout`/`rerank_error`/`threshold_inactive`）的中文映射。
- 系统设置页升级：模型状态 tab 改为 embedding/rerank/chat 三卡展示；新增「ik 词典」tab（分页列表、新增词条弹窗、删除确认、启停切换）。
- 知识库详情页新增索引配置编辑抽屉（分段长度/重叠、父子分片开关与长度）、`config_stale` 提示条与「按新配置重建」按钮及重建进度展示。

**M1 · 基础框架**
- Vite + React 18 + TypeScript + Ant Design 5 + react-router v6 + axios 项目骨架。
- 登录页与首登强制改密流程（`must_change_password`）。
- 知识库列表页：新建、删除（二次确认）、空态引导。
- 知识库详情页：文档拖拽上传、文档列表（`process_status` 状态标签 + 3s 轮询）、失败原因展示、重建按钮、分片抽屉（分页查看 chunk 内容与 metadata）。
- 检索调试页：知识库选择、`recall_top_k`/`top_n` 参数、结果卡片展示 `score`/`score_type`/`retrieval_source`，`degraded` 非空时的顶部告警。
- 系统设置占位页：只读展示嵌入模型配置状态；全局零 Key 提示 banner（`GET /api/v1/system/model-status`）。
- axios 统一拦截器：Bearer token 注入、401 自动跳转登录、错误统一提示并附带 `request_id`。

### Fixed

- 应用配置页 fusion 字段两处形状错位：评测估算/提交把 `fusion` 发成 `{mode, rrf_k}` 对象而后端是字符串字面量，勾选混合检索/混合+重排即 500；应用配置页读写嵌套 `retrieval.fusion` 对象而后端快照是扁平 `fusion_mode`/`w_vec`/`rrf_k`，未知字段被静默丢弃——应用的融合设置端到端从未生效，两处对齐真实形状。
- 评测报告与门禁双跑对比全列 NaN%：类型层把每个指标假设为 `{value, ci_low, ci_high}` 对象，而后端返回扁平数字 + 独立 `recall_ci`/`hit_rate_ci`；两个抽屉与 CSV 导出一并修正。
- API Key 创建明文弹窗空白复制 `undefined`，且授权范围（scope）勾选语义反转。
- 检索调试页遇到后端返回的未映射枚举值时整页崩溃，改为防御性回退展示原始值。
- 模型状态展示形状与后端返回对齐（`model-status` 响应结构修正）。

### Changed

- 管理台品牌名统一改为「企业RAG管理平台」（侧边栏与页脚）。
- 主布局与登录页新增授权页脚（版权/许可证/联系方式）。
