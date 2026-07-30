# M17 开发契约（网页导入 JS 渲染抓取 · 增量于 M1-M16 契约）

> 需求依据：M12 URL 导入明确划定"本期不做 JS 渲染页面（仅抓服务端返回的 HTML，SPA 空壳页属后期）"。实践中 Oracle Javadoc `index.html` 一类 frameset/SPA 页面，服务端返回的是 JS 框架壳、正文靠脚本注入，静态抓取入库后无正文可分片、检索不到。本期**补齐 M12 留的后期项**：为网页源新增**可选的 JS 渲染抓取**，让脚本渲染后的 DOM 作为正文入库。
> 全局约定沿用 M1 §0（@author owlzhangfq@gmail.com、英文注释、info/error 日志带错误码、lombok、CollectionUtils 判空、无魔法值、fast-fail 只在 Controller、不主动 commit）；web 枚举展示走 metaOf。

## 0. 范围与边界

- **本期做**：①网页源新增 `render_js` 开关（**按源、默认关**），开启后该源的抓取走无头浏览器渲染、取渲染后 DOM 入库；②server 内嵌 Playwright-Java（Chromium headless），在 `WebPageFetcher` 端口后新增渲染实现，按开关路由；③渲染路径的 SSRF 防线（浏览器加载的**每个子请求**逐个过 UrlGuard，拦内网/回环/元数据地址）；④渲染的并发/超时/开关配置化；⑤前端登记与列表暴露 `render_js` 开关。
- **本期不做**：站点级爬取/多页递归（一条登记仍=一个 URL=一个文档，沿用 M12 边界）；登录态/表单交互/点击展开（仅"导航→等待渲染→取 DOM"，不做脚本化操作）；截图/PDF 导出；渲染结果缓存池预热（每次抓取新建 context，用完即弃）；把渲染能力扩展到 M14 外部数据源（仅 web-source 通道）。
- **核心机制（复用不重造）**：渲染抓取产物仍是一段 HTML 字节，**继续收敛到 M12 既有链路**——渲染后 `page.content()` 的 DOM 作为 `text/html` 交给 `DocumentService.upload`，经既有 HtmlParser 解析、走版本机制/治理/索引管道。**不新增任何入库旁路**，渲染只替换"取字节"这一步。`content_hash` 未变→不产生新版本的语义天然继承（渲染后 DOM 稳定则命中判重）。
- **安全红线（SSRF，本期重中之重）**：无头浏览器会自动加载页面内的一切子资源（img/xhr/fetch/iframe/css），这些请求**不经主 URL 的 UrlGuard**，是"用户填 URL → 浏览器任意发请求"的新攻击面，可被用于打内网元数据端点（169.254.169.254）。防线见 §3.2，**这是本期能否上线的前置条件**：无此防线则渲染抓取不得启用。
- **部署红线**：Chromium 使镜像体积与内存显著上升（详见 §5）。渲染默认按源关闭、浏览器**懒启动**（首次真正需要渲染时才拉起）；未安装 Chromium 的部署即便误开 `render_js`，也只让该源同步 FAILED 且原因可见，**绝不拖垮应用启动或静态抓取链路**。
- **兼容红线**：
  - `t_kb_web_source` 纯新增一列 `render_js TINYINT NOT NULL DEFAULT 0`，存量源升级后行为零变化（继续静态抓取）；
  - 新增环境变量全部带默认值，`WEB_IMPORT_RENDER_ENABLED` 为总闸（见 §3.5），关闸时忽略所有 `render_js=1` 并按静态抓取兜底（记 UNCHANGED/SUCCESS 同旧逻辑，不 FAILED）；
  - 既有端点 URL 与响应结构不变，仅 `WebSourceResponse`/`RegisterWebSourceRequest`/`UpdateWebSourceRequest` 增 `render_js` 字段（响应新增字段对旧前端无害）。

## 1. 数据模型（Flyway V18，增列）

- `ALTER TABLE t_kb_web_source ADD COLUMN render_js TINYINT NOT NULL DEFAULT 0 COMMENT '置 1 时该源抓取走无头浏览器 JS 渲染，默认 0 静态抓取'`（加在 `sync_enabled` 之后，语义相邻）。
- 不新增索引：`render_js` 不参与查询过滤，仅随行读出。
- 实体 `WebSource` 增 `@TableField("render_js") private Integer renderJs;`，语义与 `syncEnabled` 一致的 0/1 TINYINT（服务层用常量 `RENDER_ON=1`/`RENDER_OFF=0`，不裸用魔法值）。

## 2. 抓取端口与实现（六边形，端口不变语义扩展）

### 2.1 端口签名扩展（kb-domain `WebPageFetcher`）
- 现 `FetchedPage fetch(String url)` → 扩展为 `FetchedPage fetch(String url, boolean renderJs)`；仅一处调用点（WebSourceService），直接改签名，不留重载（无魔法调用）。
- `FetchedPage(byte[] body, String extension)` 记录不变——渲染产物 extension 固定 `html`。

### 2.2 静态实现（kb-infrastructure `HttpWebPageFetcher`）
- 现有逻辑保持；`fetch(url, renderJs)` 中 `renderJs=false` 走原路径。**它不再直接被 WebSourceService 注入**，改由 §2.4 分发器持有。

### 2.3 渲染实现（kb-infrastructure 新增 `PlaywrightWebPageFetcher`）
- 依赖 Playwright-Java（§4 依赖表）；持有单个 `Browser`（Chromium headless），**懒启动**：首次 `fetch` 时 `synchronized` 双检拉起，`@PreDestroy` 关闭。启动失败抛 BizException（带错误码），由 WebSourceService 收成该源 FAILED。
- 并发用 `Semaphore(maxRenderConcurrency)` 限流（浏览器 context 重，超并发会 OOM）；取不到令牌在 `render-timeout-ms` 内等待，超时 FAILED。
- 单次渲染：`browser.newContext()`（禁下载、设 UA、设 `render-timeout-ms` 为默认导航超时）→ 挂 §3.2 路由拦截 → `page.navigate(url, waitUntil=NETWORKIDLE)` → 取 `page.content()` → UTF-8 字节，过 `max-page-size-mb` 上限（渲染后 DOM 可能远大于源码，同样流式/长度校验）→ `context.close()`（finally 保证释放令牌与 context）。
- 主 URL 的逐跳重定向仍由浏览器处理，但重定向目标同样被 §3.2 路由拦截校验（导航请求也是 request 事件），与静态抓取"逐跳过 UrlGuard"等价。

### 2.4 分发器（kb-infrastructure 新增 `WebPageFetcherDispatcher implements WebPageFetcher`）
- 构造注入 `HttpWebPageFetcher` + `PlaywrightWebPageFetcher` + `KbProperties`；`@Primary` 供 WebSourceService 注入。
- 路由：`renderJs && properties.getWebImport().getRender().isEnabled()` → 渲染实现；否则静态实现（含总闸关闭时的兜底降级）。

## 3. kb-rag-server

### 3.1 来源服务（WebSourceService）
- `register(kbId, url, syncEnabled, renderJs)`：落登记行时写入 `renderJs`；首次同步照旧。
- `sync(source)`：唯一改动是 `webPageFetcher.fetch(uri.toString(), source.getRenderJs() != null && source.getRenderJs() == RENDER_ON)`；其余（hash 判重、trash skip、rebind、record 四态、不重抛）**完全不变**——渲染只影响"取到的字节"，下游语义无感。
- 新增 `updateRenderJs(sourceId, enabled)` 或复用 `updateSyncEnabled` 的模式扩展 update（见 §3.3 端点决策）；翻开关只改列，不触发抓取（与 sync 开关一致）。

### 3.2 渲染 SSRF 防线（PlaywrightWebPageFetcher 内，复用 UrlGuard）
- 对渲染 context 注册路由拦截 `context.route("**/*", handler)`：每个即将发出的请求，取其 URL host 过 `urlGuard.validate(reqUrl)`——命中内网/回环/私网/链路本地/组播/通配 → `route.abort()` 并 info 日志记错误码（不抛，个别子资源被拦不应中断整页渲染）；放行 → `route.resume()`。
- 与静态抓取共用同一 `UrlGuard`（stateless domain service，M12 已为此设计）；因此"主 URL 过 UrlGuard + 每个子请求过 UrlGuard"形成闭合防线。
- 纵深防御（部署侧，写入 §5 文档而非代码）：建议渲染实例网络出站仅放行公网、显式屏蔽元数据网段，作为代码防线之外的兜底。

### 3.3 端点与 DTO
- `POST /api/v1/kb/{kbId}/web-sources`：`RegisterWebSourceRequest` 增 `@JsonProperty("render_js") Boolean renderJs`（可空，缺省 false）；Controller 解析 `boolean renderJs = request.renderJs() != null && request.renderJs()`。
- `PUT /api/v1/web-sources/{sourceId}`：`UpdateWebSourceRequest` 现仅 `sync_enabled`，增可选 `@JsonProperty("render_js") Boolean renderJs`；两字段均可空（只改传入的那个），Service 分别 set 非空项。契约保持"翻开关不重抓"。
- `WebSourceResponse` 增 `@JsonProperty("render_js") boolean renderJs`，`from(entity)` 按 `RENDER_ON` 映射。
- 权限沿用：register/sync/update 仍 `DOC_WRITE`，list 仍 `KB_READ`（渲染不新增权限，属同一数据接入能力）。

### 3.4 指标
- 复用既有 `kb.websource.sync` 四态计数（M13），**不新增指标**：渲染成败已收敛到同一 `record` 漏斗，四态语义不变；是否渲染属抓取内部实现，不进无界/额外维度（与 M13"只在既有汇聚点搭车"同哲学）。

### 3.5 配置键（KbProperties.WebImport.Render 新增内嵌类，application.yml 接环境占位符）
- `kb.web-import.render.enabled=true`（WEB_IMPORT_RENDER_ENABLED）——总闸；关闭则所有 `render_js` 被忽略、降级静态抓取。
- `kb.web-import.render.timeout-ms=20000`（WEB_IMPORT_RENDER_TIMEOUT_MS）——单页导航+networkidle 等待上限，独立于静态 `fetch-timeout-ms`（渲染更慢）。
- `kb.web-import.render.max-concurrency=2`（WEB_IMPORT_RENDER_MAX_CONCURRENCY）——同时渲染的页数（浏览器 context 重，默认保守）。
- `kb.web-import.render.wait-until=networkidle`（WEB_IMPORT_RENDER_WAIT_UNTIL）——Playwright waitUntil 策略（load/domcontentloaded/networkidle 之一，缺省 networkidle）。
- 复用既有 `max-page-size-mb`（渲染后 DOM 同受此上限约束）；不新增体积键。

## 4. 依赖（版本由 BOM 或显式锁定）

| 模块 | 新增依赖 | 理由 |
|---|---|---|
| kb-infrastructure | `com.microsoft.playwright:playwright` | 渲染实现所需；Spring Boot BOM 不含，需在父 pom `dependencyManagement` 显式锁版本，避免各模块漂移 |

- Playwright-Java 运行需 Chromium 二进制与系统库；不走"运行时自动下载浏览器"（生产无网/慢），改为镜像构建期安装（§5）。

## 5. kb-rag-deploy（收尾与部署）

- **Dockerfile（kb-rag-server 镜像）**：基础镜像追加 Chromium 及其运行库与常用字体（`playwright install --with-deps chromium` 或等价系统包 + 字体包），并设 `PLAYWRIGHT_BROWSERS_PATH` 指向镜像内固定路径。CHANGELOG 醒目说明镜像体积/内存上升与新增系统依赖。
- **docker-compose / 资源**：渲染实例内存建议在原基础上上调（Chromium 单 context 数百 MB 级）；`WEB_IMPORT_RENDER_MAX_CONCURRENCY` 与容器内存联动设定。
- **.env.example**：新增 `WEB_IMPORT_RENDER_*` 四变量（enabled/timeout-ms/max-concurrency/wait-until），带默认值与中文注释（含"开启渲染需镜像内置 Chromium"提示）。
- **OpenAPI kb-server.yaml**：`WebSource` schema、`RegisterWebSourceRequest`、`UpdateWebSourceRequest` 三处增 `render_js`；版本号按现行规则递增。
- **CHANGELOG**：新增 M17 条目——网页源支持 JS 渲染抓取（按源开关、默认关）、SSRF 子请求防线、镜像新增 Chromium 依赖与资源要求、新增四个环境变量。

## 6. kb-rag-web

- `WebSourcesTab`（kb/components）：
  - 登记行内表单增 "JS 渲染" 复选框/Switch（默认关，`render_js` 传入 register），并加一句说明："开启后用无头浏览器渲染再抓取，适用于内容靠脚本加载的页面（如 Javadoc 框架页），更慢更耗资源。"
  - 列表增 "JS 渲染" 列：Switch 行内 PUT（复用 update 端点，传 `render_js`），与"定时同步"Switch 同交互模式。
- `api/webSource.ts`：`registerWebSource` payload 增可选 `render_js`；`updateWebSource` 扩展为可传 `render_js`（或新增一个专用函数，与既有 `sync_enabled` 风格一致）。
- `types.ts`：`WebSourceEntry` 增 `render_js: boolean`；`RegisterWebSourceRequest` 增可选 `render_js`。

## 7. 单测（必须，精确断言）

- **UrlGuard 复用**：无需新增（M12 已覆盖 scheme/userinfo/内网各态）；渲染路由拦截器的判定直接复用其结论。
- **WebPageFetcherDispatcher**：`renderJs=true` 且总闸开 → 调渲染实现；`renderJs=true` 但总闸关 → 降级静态实现；`renderJs=false` → 静态实现。
- **WebSourceService**：register/update 正确持久化 `render_js`；`sync` 按 `render_js` 传参给 fetcher（mock 断言入参）；渲染实现抛异常 → 该源记 FAILED + last_error 且不重抛（沿用 M12 语义，新增渲染失败一例）。
- **PlaywrightWebPageFetcher（infrastructure 集成测，可 @Tag 隔离/CI 条件跳过——依赖浏览器）**：对本地起的"JS 注入正文"页面渲染后 `content()` 含注入文本（静态抓取取不到，形成对照）；路由拦截对指向私网地址的子请求 `abort`（起一个页面内含私网子资源的用例，断言主页面仍渲染成功且子请求被拦）；超时页面 → 在 `render-timeout-ms` 抛超时。
- **DTO/Response**：`RegisterWebSourceRequest`/`UpdateWebSourceRequest` 反序列化 `render_js` 缺省与显式两态；`WebSourceResponse.from` 的 0/1 映射。
- **既有测试适配**：`WebSourceServiceTest`（构造增 dispatcher/fetcher mock、`fetch` 改双参）、`HttpWebPageFetcherTest`（`fetch(url,false)` 签名适配）。

## 8. 验收

1. 登记一个纯 JS 渲染的内容页（`render_js=on`）→ 文档入库后有正文、可检索命中；同页 `render_js=off` 登记（另一 KB）→ 入库正文为空壳/极少，形成对照。
2. `render_js=on` 的源，页面内含指向 `169.254.169.254`/私网的子资源 → 主页面正常渲染入库，子请求被拦（日志可见），无内网请求成功发出。
3. `WEB_IMPORT_RENDER_ENABLED=false` 时，`render_js=on` 的源同步 → 降级静态抓取，结果为 SUCCESS/UNCHANGED（非 FAILED），行为等同 M12。
4. 未内置 Chromium 的镜像误开 `render_js` → 应用正常启动、静态源不受影响；该渲染源同步记 FAILED 且原因可见（浏览器启动失败），不崩应用。
5. 内容未变时对 `render_js=on` 源手动 sync → 渲染后 DOM 稳定则 UNCHANGED、版本数不变；页面 JS 输出变化后 sync → 产生新版本，走 M4a 保留。
6. 前端：登记时可勾选 JS 渲染；列表 JS 渲染 Switch 行内切换即时生效（PUT）。
7. `mvn -B -ntp verify` 全绿（渲染集成测按 CI 浏览器可用性条件执行）。

## 9. 待评审的开放问题（实现前需确认）

- **Q1 浏览器依赖引入方式**：镜像构建期 `playwright install --with-deps chromium`（体积可控、构建需网）vs 换用 `mcr.microsoft.com/playwright/java` 官方基础镜像（省事但基础镜像更大、与现有基础镜像分叉）。倾向前者，待定。
- **Q2 update 端点形态**：是把 `render_js` 并入现有 `PUT /web-sources/{id}`（一个端点两个可选开关）还是各自独立端点。倾向并入（前端两个 Switch 都走同一 PUT），待定。
- **Q3 渲染默认 waitUntil**：`networkidle` 对多数页面正确但对长轮询/心跳页面会拖到超时；是否需要按源可配 waitUntil。本期先全局配置，按源可配留后期。
- **Q4 max-page-size 对渲染的适用**：渲染后 DOM 常数倍于源码，10MB 上限是否需为渲染单独放宽。倾向复用同一上限、必要时调大全局值，待定。
