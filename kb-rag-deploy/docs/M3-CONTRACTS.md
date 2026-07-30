# M3 开发契约（多模态与清洗 · 增量于 M1/M2 契约，冲突以本文件为准）

> 需求依据：知识库需求文档 §4.2/§4.3/§5/§10-M3。M1+M2 已交付，各仓 main 为基线。
> 全局约定沿用 M1-CONTRACTS §0（端口 20000/20001/20002、英文注释、**每个类必须带 `@author owlzhangfq@gmail.com`**、日志 info/error 英文带错误码、lombok、无魔法值、fast-fail 一处、LLMentor 代码红线、不要 git commit）。

## 0. 本期范围与两处明确取舍

1. **OCR 走 VLM，不引入 PaddleOCR**：需求 §8 把 PaddleOCR 列为"本地 OCR 兜底"，M3 不实现。扫描件的文字提取由 qwen-vl-max 完成（它本身具备 OCR 能力），parser 只负责把无文本层的页面渲染成图片交给 server。理由：避免为一个兜底路径引入数百 MB 依赖与 ARM 平台构建风险；离线部署的本地 OCR 兜底列为二期。
2. **Demo 一键导入只做文档集**：需求 §10-M3 原写"Demo 文档集与示例评测集打包与一键导入"且验收含"跑通评测报告"，但评测能力属 M4b，M3 无法验收。本期实现文档集导入；示例评测集文件随 deploy 分发但**导入入口留到 M4b**。需求文档 M3/M4b 两行同步修订（deploy 仓在同一 PR 内改）。

## 1. 服务职责边界（M1 §4.2 已定，此处细化到数据结构）

- **kb-parser 不调用任何大模型**：只做文件解析、版面判定、图片抽取，输出"文本 + 图片占位符 + 图片二进制"
- **kb-server 负责全部模型调用**：VLM 图文理解、文本代理生成与**插回占位符原位**、清洗、切分、嵌入

## 2. kb-rag-parser 增量

### 2.1 解析响应扩展（向后兼容，M1 字段不变）
```json
{"code":"OK","data":{
  "markdown":"...含 [[IMAGE:img_1]] 占位符...",
  "pages":[{"page_no":1,"text":"...","scanned":false}],
  "images":[{"image_id":"img_1","page_no":1,"kind":"embedded|page_render","media_type":"image/png","content_base64":"..."}]
}}
```
- `kind=embedded`：pdf/docx 内嵌图片；`kind=page_render`：整页无文本层（扫描件），把该页渲染为 PNG（150 dpi）
- **扫描页判定**：该页抽取文本去空白后长度 < `SCANNED_PAGE_TEXT_THRESHOLD`（默认 20 字符）即视为扫描页，`pages[].scanned=true` 且产出一个 `page_render` 图片，其占位符插入该页文本位置
- **乱码页判定**（后补，与代码一致）：文本层长度达标但可识别字符（ASCII/CJK/假名/中日标点等区段）占比 < `GARBLED_PAGE_VALID_CHAR_RATIO_PCT`%（默认 50，可环境变量覆盖）——典型为内嵌子集字体 ToUnicode CMap 缺失/损坏导致的错码位"字形汤"——该页降级走扫描页路径：乱码文本**置空不入库**，产出 `page_render` 图片交 OCR/VLM 兜底，并在 `data.warnings[]` 记录一条说明
- 占位符格式固定 `[[IMAGE:{image_id}]]`，一行独占，便于 server 精确替换
- 图片上限保护：单文档图片数上限（默认 100，`MAX_IMAGES_PER_DOC`）、单图字节上限（默认 10MB），超限跳过并在响应 `data.warnings[]` 中说明（不失败整篇）
- **内嵌图片按对象去重**（后补，与代码一致）：pdf 的同一图片对象（xref）被多页绘制时，只产出**一条** `images[]` 记录与**一个**占位符，位置取其首次出现页，`page_no` 记该页。页眉页脚 logo 正是这种"一个对象画在每一页"的形态，按出现位置计数会让 247 页文档报出 493 张图——每张都要 base64 进响应、每张都要 server 逐个调 VLM 描述，且撑满图片上限后刷出几百条 warning。去重不算降级，不写 warnings。这也维持了"markdown 中占位符 id 唯一"这一 server 回填所依赖的不变量：同一 id 出现在 247 页会把该图的描述文本插进 247 处，污染每个分片
- **超上限的扫描页不再渲染**（后补，与代码一致）：图片数已达上限且未配本地 OCR（`OCR_ENGINE=none`）时，该页跳过渲染——渲出的 PNG 无人可读（不进 `images[]`，也不产出文本）。`OCR_ENGINE=paddle` 时仍渲染，上限约束的是响应携带的图片数，不是本服务读取页面的能力

### 2.2 聊天记录解析（新端点）
`POST /api/v1/parse/chat`：multipart `file` + form `file_ext`(csv|xlsx) + 可选 form `mapping_profile`(默认 `memotrace`)
```json
{"code":"OK","data":{"sessions":[{"session_id":"...","session_name":"...","messages":[
  {"msg_id":"...","sender":"...","is_self":false,"send_time":1737800000000,"msg_type":"text|image|voice|video|other","content":"..."}]}],
  "skipped":{"voice":3,"video":1,"other":0}}}
```
- 内置映射档案 `memotrace`（留痕/MemoTrace 常见列名，大小写与空格容错）：
  `session_id←room_name|talker|TalkerId`、`session_name←NickName|Remark|room_name`、`sender←Sender|NickName|talker`、`is_self←IsSender|is_sender`、`send_time←CreateTime|StrTime`、`msg_type←Type|type_name`、`content←StrContent|msg|content`
- 映射档案从 `mappings/*.yml` 载入（deploy 仓提供模板，parser 内置一份默认），未知来源可加 yml 而不改代码
- 时间解析：epoch 秒/毫秒自动判别；字符串按 `yyyy-MM-dd HH:mm:ss` 等常见格式尝试；无法解析则该消息落 `skipped.other` 并记 info
- **语音/视频消息跳过**（需求"不做音视频"边界），图片消息保留为 `msg_type=image`，content 为其原始占位描述文本（M3 不下载聊天内嵌图片文件）
- **⚠️ 真实样例待验证**：本档案依据公开列名约定编写，用户提供真实导出样例后需回归校准；测试用自造样例覆盖

### 2.3 其他
- 复用既有 zip/XXE/大小/超时安全约束；新端点同样受 100MB 与 300s 约束
- 新增单测：扫描页判定、占位符与图片对应关系、图片上限保护、chat csv/xlsx 各一正例 + 列名缺失负例 + 时间格式多态

## 3. kb-rag-server 增量

### 3.1 VisionProvider 落地
- DashScope 兼容端点 `chat/completions`，模型 `VISION_MODEL`（默认 `qwen-vl-max`），入参为 image_url(base64 data URL) + 固定英文 prompt："描述图片内容并逐字转录其中所有文字"
- 超时 `VISION_TIMEOUT_MS`（默认 20000，图片理解慢于文本）；失败分类沿用 M2 的 ProviderErrorType
- `model-status` 增 `vision_configured/vision_provider/vision_model`（扁平字段，遵循 M2 §1.6）

### 3.2 图片资产管线（索引管线内新增阶段，位于 parse 之后、clean 之前）
1. parser 返回的每张图片 → MinIO `kb/{kbId}/doc/{docId}/{versionId}/images/{image_id}.{ext}`，登记 `t_kb_image_asset`
2. 调 VisionProvider 生成文本代理（描述 + OCR 文本），落 `t_kb_image_asset.text_proxy`；同一文档的多张图片**并发**调用（`IMAGE_DESCRIBE_CONCURRENCY`，默认 8），但资产行仍按图片在 markdown 中出现的顺序落库——详见 §7.6
3. **文本代理插回原位**：把 markdown 中 `[[IMAGE:img_1]]` 替换为 `\n[图片内容] {text_proxy}\n`（占位符消失，图片语义参与后续统一切分——需求 §4.2 内嵌图片归属条款）
4. 切分后，包含图片代理文本的 chunk 其 `metadata.image_urls` 记录对应图片的 object key 列表；`chunk_type` 仍为 `text`（内嵌图片不单独成片）
5. **独立上传的图片文件**（文件本身即 png/jpg）：整篇只有一张图，文本代理前置拼接文件名作为上下文，单独成片且 `chunk_type=image`、`metadata.image_urls=[该图]`
6. VLM 未配置或调用失败：图片跳过（占位符替换为空并记 info），文档其余部分正常入库，不失败整篇；`t_kb_image_asset.status=SKIPPED|FAILED` 供后续补跑
- 检索返回时 `image_urls` 转为**限时预签名 URL**（需求 §4.8，复用 MinIO presigned，TTL 沿用 `MINIO_PRESIGNED_TTL_MINUTES`）

### 3.3 清洗规则（KB 级配置，索引管线内 clean 阶段）
`t_kb_knowledge_base.index_config` 增 `clean_rules`：
```json
{"strip_header_footer":false, "strip_watermark_patterns":[], "regex_replacements":[{"pattern":"","replacement":""}],
 "excel_header_join":true, "extract_metadata":false,
 "desensitize":{"enabled":false,"phone":true,"id_card":true,"bank_card":true,"email":false}}
```
- **执行顺序固定**：去页眉页脚 → 去水印 → 正则替换 → 脱敏；每步可独立开关
- 去页眉页脚：跨页重复行检测（同一文本在 ≥60% 页面的首/末两行出现即视为页眉页脚并删除），阈值可配
- 脱敏：正则掩码（手机号保留前 3 后 4、身份证保留前 6 后 4、银行卡保留后 4、邮箱保留域名），**聊天记录导入时默认开启**（需求 §4.2）
- clean_rules 参与 `parse_fingerprint` 计算（需求 §4.1 六元组），改清洗规则会正确标记 config_stale

### 3.4 解析预览与确认
- `index_config.parse_preview_required`（KB 级开关，**默认 false**——需求 §4.2 定的，保证批量上传顺畅）
- 开启时：管线在 clean 完成后暂停，文档 `process_status=PENDING_CONFIRM`（ProcessStatus 新增枚举），预览产物存 MinIO
- `GET /api/v1/documents/{docId}/preview` → `{markdown, pages, images:[{image_id,preview_url,text_proxy}], warnings}`
- `POST /api/v1/documents/{docId}/confirm` → 继续切分入库
- `POST /api/v1/documents/{docId}/reparse` → 按当前清洗规则重解析（body 可选 clean_rules 覆盖，仅本次预览用，不改 KB 配置）
- `POST /api/v1/kb/{kbId}/documents/confirm`（批量确认，body `doc_ids`，缺省=该库全部 PENDING_CONFIRM）

### 3.5 聊天记录导入
- `POST /api/v1/kb/{kbId}/chat-imports`（multipart file + 可选 mapping_profile）：调 parser chat 端点 → 返回**匹配预览**（不落库）
  `{sessions:[{session_id,session_name,message_count,time_range,matched_doc_id|null,action:CREATE|NEW_VERSION}]}`
- `POST /api/v1/kb/{kbId}/chat-imports/confirm`（body: upload_token + 可选 session 子集）：执行导入
  - 逻辑文档标识 = `来源渠道 + session_id`（需求 §4.2）；已存在 → 该文档新版本（全量替换，走 M1 版本机制）；新会话 → 新文档
  - 一个文件多会话 → 拆多文档
  - 聚合：按 `chat_aggregation:{window_minutes:60,max_messages:50}`（KB 级配置）**无重叠顺切**（需求 v1.6 定：一期不做重叠滑窗）
  - 每 chunk `chunk_type=chat_log`，metadata 写 `session_id/session_name/sender(该窗口主要发送人列表)/msg_time(窗口起始毫秒)`——正是 M2 metadata_filter 的过滤字段
  - 聊天文本格式：`[时间] 发送人: 内容` 逐行拼接，脱敏默认开启
- 上传文件与解析结果暂存 MinIO，`upload_token` 有效期 30 分钟

### 3.6 告警 Webhook
- `t_kb_system_config` 存 `alert.webhook_url`、`alert.enabled`、`alert.task_fail_threshold`(默认3)、`alert.degrade_rate_threshold`(默认0.3)、`alert.sync_backlog_threshold`(默认1000)
- `GET|PUT /api/v1/system/alert-config`、`POST /api/v1/system/alert-config/test`（发测试消息）
- 触发源：①同类任务连续失败 ≥ 阈值；②最近 5 分钟检索降级率 > 阈值；③双写积压 > 阈值（复用 M2 补偿扫描统计）
- 消息体兼容钉钉/企微/Slack 的通用 text 结构：`{"msgtype":"text","text":{"content":"..."}}`；未配置 URL 时降级为 error 日志（需求 §5）
- **静默期**：同一告警类型 `alert.silence_minutes`（默认 30）内不重复发送，避免刷屏

### 3.7 Demo 一键导入
- `POST /api/v1/system/demo/import` → 创建知识库"Demo 知识库"并导入 deploy 仓分发的文档集（镜像内路径 `DEMO_DATA_DIR`，默认 `/opt/kb-rag/demo`，本地开发指向 deploy 仓 `demo/` 目录）
- 幂等：已存在同名 Demo 知识库则返回其 kb_id 不重复导入
- `GET /api/v1/system/demo/status` → `{available:bool, imported:bool, kb_id, doc_count}`（`available=false` 表示未找到素材目录，前端据此置灰按钮）

### 3.8 数据模型（Flyway V3）
| 表 | 列 |
|---|---|
| t_kb_image_asset | image_id UK, kb_id IDX, doc_id IDX, document_version_id IDX, page_no, kind(EMBEDDED/PAGE_RENDER/STANDALONE), object_key, media_type, bytes, text_proxy MEDIUMTEXT, status(PENDING/DONE/SKIPPED/FAILED) IDX, fail_reason + 通用列 |
- `t_kb_document` 的 process_status 枚举增 `PENDING_CONFIRM`（不改列类型，VARCHAR 已足够）
- 其余配置均落已有 `index_config` / `t_kb_system_config`，不新增表

### 3.9 新增单测（必须）
清洗四步顺序与各步独立开关、跨页页眉页脚检测、四类脱敏正则边界（含不误伤正常数字）、占位符替换与 image_urls 关联、扫描页→VLM 跳过时不失败整篇、聊天窗口聚合边界（跨窗口/空窗口/单条）、会话匹配 CREATE/NEW_VERSION 判定、告警静默期与阈值判定、Demo 导入幂等

## 4. kb-rag-web 增量
- **知识库详情 → 索引配置抽屉**扩展：清洗规则分组（页眉页脚/水印正则列表/正则替换列表/Excel 表头/脱敏四项开关）、解析预览开关、聊天聚合参数
- **文档列表**：`PENDING_CONFIRM` 状态 Tag + 行内"预览确认"入口 + 顶部批量确认按钮
- **解析预览抽屉**：markdown 预览（纯文本渲染，禁止 HTML 注入——需求 §4.2 XSS 约束）、图片卡片（预签名 URL 缩略图 + VLM 文本代理）、warnings 提示、"确认入库"/"改规则重解析"两个动作
- **聊天记录导入向导**（知识库详情新入口）：上传 → 会话匹配结果表格（会话名/消息数/时间范围/动作 CREATE 或 NEW_VERSION）→ 全选确认导入
- **检索结果卡片**：`chunk_type=image|chat_log` 显示对应 Tag；`image_urls` 非空时渲染缩略图（点击放大）；chat_log 显示 session_name/sender/时间
- **系统设置**新增"告警"tab：webhook URL、开关、三个阈值、静默期、发送测试按钮
- **知识库列表空态**："一键导入 Demo 知识库"按钮（调 demo/status 决定是否置灰，导入中显示进度，完成跳转该库详情）
- 类型层同步：ProcessStatus 增 PENDING_CONFIRM、ChunkType 用于 Tag、新增 CleanRules/ChatImport/AlertConfig/DemoStatus 等类型

## 5. kb-rag-deploy 增量
- `demo/` 目录：3-5 篇公开无版权文档（自造技术说明文档即可，覆盖 pdf/docx/xlsx/md 各一）+ `demo/manifest.json`（文件名、标题、说明）；`demo/eval-cases.json`（示例评测集，M4b 才导入，本期只分发并在 README 注明）
- `mappings/chat/memotrace.yml`（聊天列名映射模板，与 parser 内置一致）+ README 说明如何为新来源加档案
- `.env.example` 增：`VISION_MODEL`、`VISION_TIMEOUT_MS`、`SCANNED_PAGE_TEXT_THRESHOLD`、`MAX_IMAGES_PER_DOC`、`DEMO_DATA_DIR`
- compose：parser 与 server 挂载 demo 与 mappings 目录（本地开发用 bind mount 指向仓库路径）
- `docs/openapi/` 两份同步 M3 端点与响应；NOTICE 增 qwen-vl 使用声明
- **同一 PR 内修需求文档**（`docs/知识库需求文档.md`）：①§10-M3 行拆分 Demo 导入范围（文档集 M3 / 评测集 M4b）并移除"跑通评测报告"验收；②§10-M4b 行补"示例评测集导入"；③§8 技术栈把 PaddleOCR 标注为"二期本地 OCR 兜底，M3 未引入"；④升 v1.11 并写变更记录

## 6. 验收清单（实现完成后主会话执行）
1. 上传含内嵌图片的 pdf → 图片入 MinIO、VLM 文本代理生成、代理文本可被检索命中、结果卡片显示缩略图
2. 上传扫描件 pdf（无文本层）→ 整页渲染走 VLM，文字可检索
3. 独立上传 png → 单独成片、chunk_type=image
4. 开启脱敏 → 含手机号/身份证的文档入库后分片内容已掩码；关闭则原文保留
5. 开启解析预览 → 文档停在 PENDING_CONFIRM，预览可见，确认后才入库；改规则重解析生效
6. 导入聊天 CSV → 会话匹配预览正确、导入后 chunk_type=chat_log 且 metadata 带 session_id/sender/msg_time；用 M2 的 metadata_filter 按会话+时间过滤能命中（补上 M2 验收 4 当时因无数据未真跑的项）
7. 同一会话二次导入 → 生成文档新版本而非重复文档
8. 告警：配 webhook 后点测试收到消息；未配置时降级为 error 日志不报错
9. Demo 一键导入 → 新库自动建好、文档全部 INDEXED、可检索；重复点击幂等

## 7. 实现期修订（主会话审查与 E2E 验收后回补，与代码一致）

### 7.1 数据模型偏离（已接受）
- `t_kb_image_asset` 增 `source_image_id`：parser 返回的 `img_1` 是文档内编号，不能做全局唯一键。故 `image_id` 为全局业务 ID，另建唯一键 `(document_version_id, source_image_id)`，占位符回填按后者查找
- `t_kb_document` 增 `source_key`：聊天会话的逻辑文档标识需扛住改名与二次导出，`file_name` 是展示值做不到。仍遵守"不新增表"（加列非加表）
- 解析产物落 `parsed.json`（markdown+pages+warnings）而非 `parsed.md`：页眉页脚检测需要分页文本，重建时不能重调 parser；读取端对 `.md` 旧键回退为纯 markdown，M1/M2 版本仍可重建

### 7.2 管线次序说明
清洗与占位符替换的物理次序为 parse → 图片入库+VLM → **清洗（带占位符正文 + 各文本代理分别清洗）→ 代理插回**。可观察行为与 §3.2 一致（代理在原位、正文与代理都被清洗），但替换后置保证记录的偏移量精确，`image_urls` 与 chunk 的关联才是确定的——先替换再清洗会让每处偏移失效。

### 7.3 config_stale 口径扩展（有副作用，属预期）
`current_config_fingerprint` 由单一 chunk 指纹改为 parse+chunk 组合指纹，否则改清洗规则无法标记 config_stale（§3.3 要求）。**副作用**：升级后 M1/M2 时期的文档会显示 `config_stale=1`（视觉模型与清洗规则是新增指纹输入），重建后归零。

### 7.4 E2E 验收中发现并修复的三个缺陷
1. **provider 401 被误报为网络不可达**：传输层用 `SimpleClientHttpRequestFactory`（JDK HttpURLConnection），服务端 401 带 `WWW-Authenticate` 时无法重放请求体，抛 I/O 异常，真正的状态码到不了 `classify()`，所有凭证问题都落进 `NETWORK_UNREACHABLE` 桶，把排查方向指向网络。改用 `JdkClientHttpRequestFactory`（java.net.http），并加本地 401 服务的回归测试锁定
2. **银行卡脱敏漏 17/18/19 位**：正则 `\d{4}(?:[ -]?\d{4}){3,4}` 只匹配 16 位与 20 位，而 ISO/IEC 7812 是 16-19 位（含多数银联卡），开关显示已启用却放行。改为 `\d{4}(?:[ -]?\d{4}){3}(?:[ -]?\d{1,3})?`，四种长度各加参数化用例
3. **ES 别名只追加不切换**：`putAlias` 从不摘旧索引也不指定写索引。嵌入版本段一变（换嵌入模型，或丢 Key 退回 `none`），第二个物理索引加入同一别名，ES 无法判定写入目标，该库**所有写入与删除永久失败**（`no write index is defined for alias`）。改为 `updateAliases` 单次原子操作：摘除其余索引 + 以 `is_write_index` 加入目标——这也正是索引契约要求的"新建物理索引 + 别名原子切换"

### 7.5 遗留（不阻塞 M3 验收）
- `clean_rules.excel_header_join` 与 `extract_metadata` 已进配置与指纹但服务端无行为（前者属 parser 职责，后者 M3 未定规格）
- `t_kb_image_asset` 的 `SKIPPED/FAILED` 行是补跑清单，但尚无补跑端点（配 Key 后重描述图片）
- 知识库删除未清理 `t_kb_image_asset` 行与 MinIO 图片对象（随 M4 CLEANUP 任务处理）
- `memotrace` 列名映射仍待真实导出样例校准

### 7.6 图片描述并发化（后补，与代码一致）

§3.2 第 2 步原为逐图串行调用。一次调用的墙上时间是 `VISION_TIMEOUT_MS`（默认 20s）量级，图片撑满上限（100 张）的文档会独占一个索引管线槽位半小时以上——一份插图报告就能把整条索引队列堵住。现按 `IMAGE_DESCRIBE_CONCURRENCY`（`kb.image.describe-concurrency`，默认 8）并发发起，100 张的最坏耗时从约 33 分钟降到约 4 分钟。

- 并发的只有两个网络动作：图片写 MinIO 与 VisionProvider 调用。**资产行仍在调用线程按阅读顺序串行 insert**——`findByVersion` 按主键升序返回，占位符回填把这个顺序读作「图片在 markdown 中出现的顺序」，并发 insert 会让自增 id 顺序随模型响应快慢漂移，代理文本插到别的图片位置上
- 并发度取 `min(配置值, 本文档图片数)`；配置值 ≤ 1 时退化为串行且不建线程池。线程池按文档创建、用完即 shutdown
- 上界由模型服务方的限流决定，不是本机 CPU（全程等待远端）。被限流的调用按 §3.2 第 6 条记 `status=FAILED` 且**不重试**，所以并发度开过头是拿文本丢失换延迟，默认 8 是留了余量的取值
- 单图失败语义不变（记 `SKIPPED|FAILED`、不失败整篇）；MinIO 写失败仍原样上抛终止该文档，异常从 `CompletionException` 解包后再抛，任务的 `fail_reason` 文本与串行时期一致
- **`MAX_IMAGES_PER_DOC` 刻意未随之调大**：该变量由 parser 与 server 共用，调大同时放大解析响应体（每张图 base64 内联在 `images[]` 里），扫描件场景单张就有几百 KB
