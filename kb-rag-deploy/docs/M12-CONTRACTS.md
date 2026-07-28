# M12 开发契约（数据接入：URL 导入与增量同步 · 增量于 M1-M11 契约）

> 需求依据：知识库需求文档未含 URL 导入条款，本期为新增能力（数据接入渠道扩展）：把"上传文件"之外的第二条入库通道——**登记网页 URL → 抓取 → 解析入库**——补齐，并配套**增量同步**（定时/手动重抓，内容未变不产生新版本）。当前无用户体系，来源登记不记录"谁"，与 M10/M11 同口径。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带错误码、lombok、CollectionUtils 判空、无魔法值、fast-fail 只在 Controller、不主动 commit）；web 枚举展示走 metaOf。

## 0. 范围与边界

- **本期做**：①URL 导入（`POST /kb/{kbId}/web-sources` 登记并立即抓取，抓到的页面走既有上传链路入库）；②HTML 通用解析（kb-rag-parser 注册 html 解析器，顺带解锁 .html 文件直接上传）；③增量同步（来源登记表 + 手动 sync 端点 + 每日定时重抓，`content_hash` 未变则跳过、变了走 M4a 版本机制产生新版本）。
- **本期不做**：站点级爬取/多页递归（一条登记=一个 URL=一个文档，站点地图属后期）；JS 渲染页面（仅抓服务端返回的 HTML，SPA 空壳页属后期，抓到什么入什么）；登录态/带 Cookie 抓取（仅公开可匿名访问的 URL）；RSS/API 等其他数据源。
- **核心机制（复用不重造）**：抓取产物统一收敛到 `DocumentService.upload(kbId, fileName, bytes)`——文件名由 URL 派生且稳定，重抓同一 URL 天然命中"同名加版本"路径，`DocumentVersionPlanner` 的 content_hash 去重免费提供"内容未变→不产生新版本"；治理（M11 review_required/回收站）、版本保留（M4a）、索引管道全部免费继承，**本期不新增任何入库旁路**。
- **安全红线（SSRF）**：项目现有 HTTP 出站（webhook/parser/模型端点）都是运营者配置的固定地址，无用户输入 URL；本期是第一处"用户给什么就请求什么"，必须新建 SSRF 防线（见 §3.2）：仅 http/https、禁 userinfo、解析后拒绝回环/私网/链路本地/组播地址、重定向逐跳重验、限时限体积限跳数。
- **兼容红线**：
  - 纯新增表与端点，存量行为零变化；
  - `UPLOAD_ALLOWED_EXTENSIONS` 默认值追加 `html`——已显式配置该环境变量的部署需自行追加，CHANGELOG 醒目说明；
  - 来源登记与文档是**弱绑定**：移除登记不动文档，删除文档（回收站）不动登记（同步时跳过并记因）；文档被彻底删除后，下次同步重新建档并重绑。

## 1. 数据模型（Flyway V14，新表）

| 表 | 定义 |
|---|---|
| t_kb_web_source | `id` BIGINT PK AUTO、`source_id` VARCHAR(64) UK（biz id，ws_ 前缀）、`kb_id` VARCHAR(64) NOT NULL、`url` VARCHAR(2048) NOT NULL、`url_hash` CHAR(64) NOT NULL（url 的 sha256，等值定位用）、`doc_id` VARCHAR(64) NULL（绑定文档，首抓成功后回填）、`file_name` VARCHAR(255) NULL（派生文件名，稳定）、`sync_enabled` TINYINT NOT NULL DEFAULT 1、`last_content_hash` CHAR(64) NULL、`last_fetch_at` DATETIME NULL、`last_fetch_status` VARCHAR(16) NULL（SUCCESS/UNCHANGED/SKIPPED/FAILED）、`last_error` VARCHAR(512) NULL、`created_at`/`updated_at` DATETIME；索引 UK `uk_kb_url(kb_id, url_hash)`、KEY `idx_kb(kb_id)` |

- 同一 KB 内同一 URL 只允许登记一次（UK 兜底 + 服务层友好拒绝）；不同 KB 可各自登记同一 URL（各建各的文档）。
- 派生文件名规则：`{host}-{path slug}-{url_hash 前 8 位}.html`（slug 化去非法字符、总长截到 200）；hash 后缀保证不同 URL 不会撞名合并成一个文档。

## 2. kb-rag-parser（HTML 解析器）

- 新增 `app/parsers/html.py`：`HtmlParser(BaseParser)`，registry 注册 `html`/`htm` 两个扩展名。
- 实现沿用 chat html_adapter 的先例：**只用标准库 `html.parser.HTMLParser`，不引 bs4**（避免新依赖，app/security.py 的 XML 防线不适用于事件式解析器，无 XXE 面）。
- 解析语义：丢弃 `script/style/noscript/head`（`<title>` 除外）；`<title>` 作为一级标题输出；`h1-h6` 映射 markdown 标题；块级元素（p/div/li/tr/br 等）断行；`<a>` 只留文本；连续空行折叠。产出 `ParseData(markdown=..., pages=[单页], images=[])`——图片外链不下载（SSRF 面留在 server 侧防线之外，解析器不发任何网络请求）。
- 编码走既有 `decode_bytes`；解析失败抛 `ParseError`（PARSE_FAILED），与其他解析器同模式。
- 单测 `tests/test_parse_html.py`：标签剥离、script/style 丢弃、title→标题、标题层级映射、编码兜底、损坏输入报 PARSE_FAILED。

## 3. kb-rag-server

### 3.1 上传白名单（UploadValidator，一行配置）
- `application.yml` `kb.upload.allowed-extensions` 默认值追加 `html`（html 无魔数，UploadValidator 的 MAGIC 表不动——文本格式本就"仅验扩展名与大小"）。

### 3.2 SSRF 防线（UrlGuard，kb-domain service）
- `UrlGuard.validate(url)`：仅 `http/https`；禁 userinfo（`user@host` 形态直接拒）；host 解析出的**每个** InetAddress 均须非回环/非私网（10/172.16-31/192.168）/非链路本地（169.254）/非组播/非通配（0.0.0.0）；解析失败 → INVALID_PARAM。中文拒绝消息（如"该地址指向内网，禁止抓取"）。
- 重定向不交给 HttpURLConnection 自动跟随：抓取器手动跟随 ≤ `max-redirects`（默认 3）次，**每一跳重新过 UrlGuard**——防"公网 URL 302 到 169.254.169.254"的经典绕过。

### 3.3 抓取器（WebPageFetcher：kb-domain 定义 port，kb-infrastructure 实现）
- `FetchedPage fetch(String url)`：RestClient + `SimpleClientHttpRequestFactory`（连接/读取超时 = `fetch-timeout-ms`），流式读且累计超过 `max-page-size-mb` 即中止（不能只信 Content-Length）。
- Content-Type 白名单：`text/html`、`application/xhtml+xml`、`text/plain`、`text/markdown`（缺省按 text/html 处理）；白名单外（pdf/图片/二进制）→ INVALID_PARAM"仅支持网页与文本类内容"。text/plain、text/markdown 派生文件名后缀相应用 .txt/.md。
- 非 2xx → 抓取失败（不是参数错误，登记仍成立，状态记 FAILED）。

### 3.4 来源服务（WebSourceService，kb-app 包 websource）
- `POST /api/v1/kb/{kbId}/web-sources`：`{url, sync_enabled?=true}`——UrlGuard 校验 → 落登记行 → **立即执行一次同步**（同步失败不回滚登记，状态记 FAILED 供列表可见）；同 KB 重复 URL → INVALID_PARAM。
- `GET /api/v1/kb/{kbId}/web-sources?page=&size=`：登记列表，created 最新优先（分页默认/上限 20/200）。
- `POST /api/v1/web-sources/{sourceId}/sync`：手动同步，返回本次结果（status + doc_id + version 变化与否）。
- `PUT /api/v1/web-sources/{sourceId}`：`{sync_enabled}`，只开关自动同步。
- `DELETE /api/v1/web-sources/{sourceId}`：移除登记（硬删登记行；**不动文档**，文档去留走 M11 回收站流程）。
- **单次同步语义**（登记即抓、手动 sync、定时任务三处共用同一方法）：
  1. UrlGuard 重验（登记后 DNS 可能已变，防 rebinding）→ fetch；
  2. `sha256(body)` == `last_content_hash` → 记 UNCHANGED，不触上传链路（连"planner 判重"都不进，省一次对象存储写）；
  3. 绑定文档在回收站（trashed=1）→ 记 SKIPPED + last_error"文档在回收站，恢复或彻底删除后才会继续同步"——不给回收站文档追加版本（与 M11 findLogicalDocument 同一语义），也不另建新档（避免恢复后一个 URL 两个文档）；
  4. 其余 → `DocumentService.upload(kbId, fileName, bytes)`：绑定文档已被彻底删除时该调用自然重新建档，回填 doc_id；
  5. 无论成败回填 `last_fetch_at/last_fetch_status/last_error(截 512)/last_content_hash(仅成功)`；异常仅 error 日志带错误码，不重抛（定时批次内单条失败不中断他条）。
- 定时同步：`@Scheduled(cron = kb.web-import.sync-cron)`，`sync-enabled=false` 短路；每轮取 `sync_enabled=1` 的登记按 id 升序、单轮 ≤ `sync-batch-size` 条（抓取是外网慢操作，按条数限批）。

### 3.5 配置键（application.yml 接环境占位符，KbProperties.WebImport 承载）
- `kb.web-import.fetch-timeout-ms=10000`（WEB_IMPORT_FETCH_TIMEOUT_MS）
- `kb.web-import.max-page-size-mb=10`（WEB_IMPORT_MAX_PAGE_SIZE_MB）
- `kb.web-import.max-redirects=3`（WEB_IMPORT_MAX_REDIRECTS）
- `kb.web-import.sync-cron=0 30 2 * * *`（WEB_IMPORT_SYNC_CRON）
- `kb.web-import.sync-enabled=true`（WEB_IMPORT_SYNC_ENABLED）
- `kb.web-import.sync-batch-size=50`（WEB_IMPORT_SYNC_BATCH_SIZE）

### 3.6 单测（必须，精确断言）
- UrlGuard：scheme 白名单、userinfo 拒、回环/私网/链路本地/组播/0.0.0.0 各一例拒、公网放行、解析失败拒
- 派生文件名：稳定性（同 URL 两次同名）、不同 URL 不撞名、长路径截断、Content-Type 决定后缀
- 同步语义：hash 未变→UNCHANGED 且不触 upload、变了→upload 被调且回填、trashed 文档→SKIPPED 不触 upload、doc 已彻底删→重新建档重绑、失败→FAILED+last_error 回填不重抛
- 登记：同 KB 重复 URL 拒、跨 KB 同 URL 允许、登记即触发一次同步、移除登记不动文档
- 定时：disabled 短路、批大小边界、单条失败不中断批次
- 抓取器（infrastructure 侧）：超体积中止、Content-Type 白名单外拒、重定向逐跳重验（302 到私网地址被拦）

## 4. kb-rag-web

- 知识库详情新增 tab **网页来源**（WebSourcesTab，kb/components）：
  - 顶部行内表单：URL 输入 + "添加并抓取"按钮（成功后刷新列表与文档列表）
  - 列表列：URL（ellipsis+Tooltip）/ 最近抓取时间 / 抓取结果 Tag（WEB_SOURCE_STATUS_META 走 metaOf，FAILED/SKIPPED 的 Tooltip 展示 last_error）/ 自动同步 Switch（行内 PUT）/ 操作（立即同步、移除登记 Popconfirm——文案明确"仅移除登记，已入库文档不受影响"）
- api 层：新增 `webSource.ts`（register/list/sync/update/remove 五函数）；types.ts 增 `WebSource`、`WebSourceFetchStatus` 及 `WEB_SOURCE_STATUS_META`

## 5. kb-rag-deploy（收尾）

- OpenAPI kb-server.yaml：新增 `web-sources` tag + 5 端点 + WebSource schema；版本升 0.13.0-m12
- .env.example 增 WEB_IMPORT_ 六变量；CHANGELOG 记录（含 UPLOAD_ALLOWED_EXTENSIONS 默认值追加 html 的醒目说明）

## 6. 验收

1. 登记一个公网文档页 URL → 立即入库可检索，文档列表出现派生名 .html 文档；.html 文件直接上传同样入库
2. 登记指向 `http://127.0.0.1`、`http://192.168.x.x`、`http://user@host/` 的 URL → 均被拒绝且给中文原因；302 跳内网的 URL 抓取被拦（FAILED）
3. 内容未变时手动 sync → 结果 UNCHANGED，文档版本数不变；改动页面后 sync → 产生新版本，旧版本按 M4a 保留策略处理
4. 将绑定文档移入回收站后 sync → SKIPPED 且原因可见；彻底删除后 sync → 重新建档
5. review_required=1 的 KB 登记 URL → 新文档为 DRAFT（治理链路继承验证）
6. `mvn -B -ntp verify` 全绿；kb-rag-parser `pytest` 全绿
