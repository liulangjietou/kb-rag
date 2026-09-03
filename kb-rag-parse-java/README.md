# kb-rag-parse-java

kb-rag 知识库项目的 **Java 文档解析微服务**，是 [kb-rag-parser](../kb-rag-parser)（Python 实现）的功能对等移植。只负责"文件解析"这一件事：把上传的文档转成结构化的 `markdown` + 按页文本 + 图片二进制，把聊天记录导出（csv/xlsx/txt/html）转成结构化会话消息；不做任何模型调用（VLM 图文理解/嵌入/切分等由 kb-rag-server 侧负责，见需求文档 §4.2 服务职责边界、M3-CONTRACTS.md §0），扫描页 OCR 的本地兜底（M8-CONTRACTS.md §0.4）是唯一例外，且默认关闭。

两套实现对同一份输入给出**同一个答案**：42 项端到端对拍用例（含 pdf 文本层、图片去重、markdown 表格、时间戳归一、空白语义、聊天消息逐字段）全部一致，见下文[「与 Python 实现的等价性」](#与-python-实现的等价性)。契约与两侧共同的规范来源一致（M1/M3/M8/M12/M14-CONTRACTS.md 与 `docs/openapi/kb-parser.yaml`），kb-rag-server 无需任何改动即可换用本服务。

## 为什么有这个仓库

两个理由，第二个是决定性的。

第一，与主服务技术栈统一。kb-rag-server 是 Spring Boot 3.3.9 / Java 17，本服务与之一致后，整套后端只剩一种构建、一种运行时、一套可观测配置。

第二，**解开 pdf 解析的 AGPL-3.0 合规死结。** Python 实现的 pdf 路径依赖 PyMuPDF，其实际许可为 AGPL-3.0（Artifex 双重许可，免费一侧即 AGPL-3.0），而 kb-rag 整体以 Apache-2.0 发布——AGPL-3.0 要求以网络服务形式对外提供时须向使用者提供完整对应源代码，这与项目的整体许可目标之间存在一个**尚未解决**的关系，kb-rag-parser 的 README 与 NOTICE 都把它作为待 Owner 决策事项如实记录着。本实现的 pdf 路径走 **Apache PDFBox（Apache-2.0）**，是那份记录中列出的可能方向之二（"更换 pdf 解析库为许可更宽松的替代方案"）的一个已完成、已验证的落地。

说清楚这消除了什么、没消除什么：本仓库的**直接依赖**全部是 Apache-2.0、MIT 或 BSD-3-Clause，其中没有 copyleft 项；**传递依赖**里有两项弱 copyleft 的双许可件——Logback（EPL-1.0 或 LGPL-2.1，Spring Boot 的默认日志实现）与 `jakarta.annotation-api`（EPL-2.0 或 GPL-2.0 + Classpath Exception）。它们与 AGPL-3.0 的关键差别不在"强弱"这个说法上，而在触发条件：这两者的义务只在**修改并分发该库本身**时才落到修改者头上，未加修改地作为库依赖使用不触发任何源码披露；AGPL-3.0 则额外规定，以网络服务形式对外提供包含该代码的程序**这一行为本身**就触发完整对应源代码的提供义务——而解析服务恰恰就是一个网络服务。逐项许可与核实依据见 `NOTICE`。

这不代表 Python 实现应当下线：它是契约的原始定义方与行为基准，本实现的每一处判定都以它为准绳。

## 技术栈

- Java 17 + Spring Boot 3.3.9（与 kb-rag-server 同版本线）
- Apache PDFBox 3.0.5（pdf）/ Apache POI 5.4.1（docx、xlsx）/ jsoup 1.18.3（html 页面与 html 聊天记录 DOM）/ Apache Commons CSV（csv）/ SnakeYAML（映射档案）/ Apache Commons Compress（zip 安全预检）
- 可选：Tesseract via tess4j（`mvn -Pocr`，`OCR_ENGINE=tesseract` 时才需要，默认不装）

与 Python 实现的库对应关系：

| 职责 | kb-rag-parser | kb-rag-parse-java |
|---|---|---|
| HTTP 服务 | FastAPI + Uvicorn | Spring Boot Web (Tomcat) |
| 响应模型 | pydantic 2 | Jackson + Lombok |
| pdf | PyMuPDF（**AGPL-3.0**） | Apache PDFBox（Apache-2.0） |
| docx | python-docx | Apache POI XWPF |
| xlsx | openpyxl | Apache POI XSSF |
| csv | 标准库 `csv` | Apache Commons CSV |
| html | 标准库 `html.parser` | jsoup |
| 映射档案 yml | PyYAML `safe_load` | SnakeYAML `SafeConstructor` |
| XXE 防护 | `defusedxml.defuse_stdlib()` | POI `XMLHelper` 默认加固（审计）+ 解压比阈值 |
| 扫描页 OCR 兜底 | PaddleOCR（可选） | Tesseract via tess4j（可选） |

## 快速启动

```bash
# 1. 构建（默认不含 OCR 可选依赖，镜像保持精简）
mvn -DskipTests package

# 1.1 可选：需要本地 OCR 兜底时才这样构建
mvn -Pocr -DskipTests package

# 2. 启动（默认端口 20001，与 M1 契约 kb-rag-parser 端口一致）
java -jar target/kb-rag-parse-java-1.1.0.jar

# 3. 运行测试
mvn test
```

需要 JDK 17 或更高。

容器方式：

```bash
docker build -t kb-rag-parse-java:local .
```

```bash
docker run --rm -p 20001:20001 kb-rag-parse-java:local
```

镜像为多阶段构建，运行阶段只带 JRE，以非 root 用户（uid 10001）运行——解析的输入全部来自
不可信文件，把这一层权限收窄是廉价且值得的。运行时不需要任何出站网络访问（需求文档 §4.2），
容器的网络策略应当只放行来自 kb-rag-server 的入站流量。

## 环境变量

变量名与 Python 实现逐一对齐，同一份部署环境可直接切换两种实现。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `20001` | 监听端口 |
| `SCANNED_PAGE_TEXT_THRESHOLD` | `20` | pdf 页面文本层短于此长度即判定为扫描页 |
| `GARBLED_PAGE_VALID_CHAR_RATIO_PCT` | `50` | pdf 页面文本层可识别字符（ASCII/CJK/假名/中日标点等）占比低于此百分比即判定为乱码页，降级走扫描页路径 |
| `MAX_IMAGES_PER_DOC` | `100` | 单文档图片数量上限 |
| `MAX_IMAGE_BYTES` | `10485760`（10MB） | 单张图片字节数上限 |
| `PARSER_MAX_WORKERS` | `4` | 解析线程池大小，即本服务真实的解析并发上限 |
| `OCR_ENGINE` | `none` | `none`\|`tesseract`；`tesseract` 但未以 `-Pocr` 构建时启动 fast-fail（M8-CONTRACTS.md §0.4） |
| `OCR_TIMEOUT_S` | `30` | 单页 OCR 超时（秒），超时按页跳过并计数，不影响整篇文档 |
| `OCR_LANGUAGE` | `chi_sim+eng` | Tesseract 语言包，仅 `OCR_ENGINE=tesseract` 时生效 |
| `OCR_DATA_PATH` | 空 | Tesseract `tessdata` 目录；留空则由引擎按自身默认查找 |

## API 说明

统一响应体（与 kb-rag-server 一致的契约风格）：

```json
{"code": "OK", "data": {...}, "message": "success", "request_id": "..."}
```

失败时 `code` 为 `PARSE_FAILED`，`data` 为 `null`，`message` 给出可读的失败原因（不泄露原始堆栈）。**失败同样返回 HTTP 200**：解析不了的文档是一种结果而不是传输错误，kb-rag-server 的客户端读的是 `code`；返回 5xx 只会让重试与熔断把"这个文件永远读不了"当成瞬时故障。

### `GET /health`

存活探针。

```json
{"status": "UP"}
```

### `POST /api/v1/parse`

`multipart/form-data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `file` | file | 待解析的文档 |
| `file_ext` | form 字段 | 文件扩展名（不带点），如 `pdf`/`docx`/`txt`/`md`/`sql`/`xlsx`/`csv`/`html`/`htm` |

`file_ext` 取自表单而非文件名后缀，是为了让策略分派不受调用方给文件起什么名字左右。

成功响应 `data` 结构（M3-CONTRACTS.md §2.1，M14 增 `pages[].markdown`，向后兼容 M1）：

```json
{
  "markdown": "整篇文档的 markdown 表示，可能含独占一行的 [[IMAGE:img_1]] 占位符",
  "pages": [{"page_no": 1, "text": "该页的纯文本", "markdown": "该页对应的 markdown 切片", "scanned": false, "ocr_source": null}],
  "images": [
    {"image_id": "img_1", "page_no": 1, "kind": "embedded", "media_type": "image/png", "content_base64": "..."}
  ],
  "warnings": []
}
```

- `pages[].text`：该页抽取的纯文本。kb-rag-server 的页眉页脚检测逐页比对的是它，所以它不含页标题、也不含图片占位符。
- `pages[].markdown`（M14-CONTRACTS.md §F3）：该页对应的 `data.markdown` 切片，含页标题（pdf `## Page N` / xlsx `## Sheet: 名`）与 `[[IMAGE:{image_id}]]` 占位符行；**各页按 `\n\n` 拼接与 `data.markdown` 逐字符相等**，这是 server 侧逐页清洗后无损拼回所依赖的不变量。按页切分策略消费它而不是 `text`——否则按页切出的分片永远关联不到图片，拿到的也是未经清洗脱敏的原文。
- `pages[].scanned`：该页文本去空白后长度 < `SCANNED_PAGE_TEXT_THRESHOLD`（默认 20）即为 `true`，此时该页按 150dpi 渲染为 PNG（`kind=page_render`）替代文本层；只有 pdf 页面可能为扫描页，其余格式恒为 `false`。
- **乱码页降级**（pdf）：文本层长度达标但可识别字符占比 < `GARBLED_PAGE_VALID_CHAR_RATIO_PCT`（默认 50%）时，判定该页文本层不可用——常见于内嵌子集字体的 ToUnicode CMap 缺失/损坏，中文被抽成错码位的"字形汤"（数字/英文往往仍正常）。此时该页 `text` 置空、`scanned=true`、产出 `page_render` 图片交 OCR/VLM 兜底，并在 `warnings[]` 记录一条说明，乱码文本不会进入切分与索引。
- `pages[].ocr_source`（M8-CONTRACTS.md §0.4）：扫描页被本服务的本地 OCR 兜底成功识别出文本时为引擎名（本实现为 `"tesseract"`，见[偏离说明](#与契约的偏离说明)），此时 `pages[].text` 已回填为 OCR 结果；否则为 `null`（`OCR_ENGINE=none` 默认状态 / 非扫描页 / OCR 超时或失败按页跳过）。kb-rag-server 对带 `ocr_source` 的页不应再走自己的 VLM。
- `images[].kind`：`embedded`（pdf/docx 内嵌图片）或 `page_render`（扫描页整页渲染）。**同一个图片对象在文档级只上报一次**：页眉 logo 在 pdf 里是一个对象被每页绘制，逐页上报会让 kb-rag-server 为一张图调数百次视觉模型，也会破坏"markdown 中占位符 id 唯一"这一 server 回填所依赖的不变量。
- `warnings[]`：单文档图片数上限或单图字节上限超限时，该图被跳过并在此记录说明，不影响文档其余部分正常返回；pdf 乱码页降级也在此记录。

### `POST /api/v1/parse/chat`

聊天记录导出解析为结构化会话消息（M3-CONTRACTS.md §2.2，file_ext 由 M8-CONTRACTS.md §0.1/§0.2 扩至 txt/html）。`multipart/form-data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `file` | file | 待解析的聊天记录导出文件 |
| `file_ext` | form 字段 | `csv`\|`xlsx`\|`txt`\|`html` |
| `mapping_profile` | form 字段，可选 | 映射档案名，对应 `src/main/resources/mappings/{profile}.yml`；省略时按 `file_ext` 取内置默认（csv/xlsx -> `memotrace`，txt -> `liuhen_txt`，html -> `liuhen_html`） |
| `profile_yaml` | form 字段，可选 | 映射档案的完整 YAML 文本；**若提供则优先于 `mapping_profile` 对应的本地文件**（本地 yml 仅作为种子/默认内容，M8-CONTRACTS.md §0.7） |

成功响应 `data` 结构：

```json
{
  "sessions": [{
    "session_id": "room_a", "session_name": "Alice's Room",
    "messages": [{"msg_id": "...", "sender": "alice", "is_self": false, "send_time": 1737800000000, "msg_type": "text", "content": "..."}]
  }],
  "skipped": {"voice": 0, "video": 0, "other": 0}
}
```

csv/xlsx（列名映射）：

- 按目标字段的候选列名列表依次匹配，匹配时忽略大小写与空白；只有 `content` 是硬性要求（一处 fast-fail 边界），其余字段（`session_id`/`sender`/`is_self`/`send_time`/`msg_type`）在列缺失时优雅降级（如落入默认会话、`sender` 置空、`msg_type` 默认为 `text`）。新增来源只需新增一份 yml，无需改代码。
- `send_time` 归一化：自动判别 epoch 秒/毫秒（以 10^11 为分界）、xlsx 原生日期单元格、常见字符串格式；单条消息无法解析时该消息计入 `skipped.other`（记 info 日志），不影响其余消息。
- 语音/视频消息（`msg_type` 判定为 `voice`/`video`）从 `sessions[].messages[]` 中剔除并计入 `skipped`；图片消息保留（`msg_type=image`，`content` 为导出文件中的原始占位描述文本，本服务不下载聊天内嵌图片）。
- **⚠️ 内置 `memotrace` 档案依据公开列名/编码约定编写，尚未用真实导出样例校准**，详见 `src/main/resources/mappings/memotrace.yml` 顶部说明。

txt（M8-CONTRACTS.md §0.1，行模式）：

- 逐行匹配 mapping profile `txt:` 段的有序行首正则列表（命名捕获组 `send_time`/`sender`，可选 `content`），内置 `liuhen`（`YYYY-MM-DD HH:MM:SS 发送人` 换行消息体）与 `wechat_pc`（`发送人 (时间):` 同行/换行消息体）两种模板；不匹配任何模板的行归并为上一条消息的续行，若发生在首条消息之前则计入"不匹配"。
- 整个文件的不匹配行占比 > 30% 判定为格式/模板选错，直接返回 `PARSE_FAILED` 并给出可操作提示，而非静默产出残缺会话。
- **正则方言**：档案里写的是 Python 的 `(?P<name>...)`，本实现原样吃下——`PythonRegexTranslator` 把命名组转成编号组并旁挂名字表（Java 的组名不允许下划线，`send_time` 直接过不了），同时开启 `UNICODE_CHARACTER_CLASS` 让 `\d`/`\w`/`\s` 与 Python 3 同义。Java 自己的 `(?<name>...)` 也同样接受。
- 一个 txt 文件固定解析为单一 `ChatSession`（`session_id`/`session_name` 取自文件名 stem）。

html（M8-CONTRACTS.md §0.2，DOM 选择器）：

- 解析前剥离 `<script>`/`<style>` 内容、**绝不请求任何远程资源**（`<img src>` 只判断"是否存在"，从不下载；jsoup 仅作为已在手字节的分词器使用，`Jsoup.connect` 全仓无调用）。
- 消息节点/发送人/时间/正文由 mapping profile `html:` 段的选择器定位，内置"留痕"模板见 `src/main/resources/mappings/liuhen_html.yml`。
- 图片消息节点（命中 `image` 选择器）转为固定 `content="[IMAGE]"` 占位、`msg_type=image`；命中 `voice`/`video` 选择器的消息整条跳过并计入 `skipped`，与 csv/xlsx 语义一致。
- `message` 选择器一个节点都没匹配到时判定为选择器/模板选错，返回 `PARSE_FAILED`。
- 同样固定解析为单一 `ChatSession`。

### 支持格式一览

`/api/v1/parse`：

| file_ext | 解析库 | 说明 |
|---|---|---|
| `pdf` | Apache PDFBox | 按页抽取文本层；无文本层的页面（扫描页）或文本层为乱码的页面（ToUnicode CMap 损坏）渲染为 PNG，默认交由 kb-rag-server 侧 VLM OCR，`OCR_ENGINE=tesseract` 时本服务自行兜底出文本 |
| `docx` | Apache POI XWPF | 按文档原始顺序抽取段落+表格+内嵌图片；docx 无可靠页码概念，整篇作为 `page_no=1` 返回 |
| `txt` | JDK | 尽力探测编码（utf-8-sig/utf-8/gbk），原样返回 |
| `md` | JDK | 原样透传，本身已是 markdown |
| `sql` | JDK | 原样透传，不臆造 markdown 语法 |
| `xlsx` | Apache POI XSSF | 每个 sheet 对应一个 `page_no`，同时渲染为 markdown 表格 |
| `csv` | Apache Commons CSV | 自动探测分隔符（逗号/分号/tab），渲染为 markdown 表格 |
| `html` / `htm` | jsoup | 通用 HTML 页面→markdown：`<title>` 与 h1-h6 映射为标题、块级元素分段，剔除 script/style/noscript/template；单页返回（`page_no=1`）、不产出图片、绝不请求远程资源（URL 抓取与 SSRF 防护在 kb-rag-server 侧） |

新增文档格式只需实现一个 `DocumentParser` 并在 `parser/ParserRegistry` 里注册一行 —— 策略注册表模式，控制层无需改动。

`/api/v1/parse/chat`：`csv`/`xlsx`（列名映射）、`txt`（行模板）、`html`（DOM 选择器）。

## 安全约束（需求文档 §4.2 落实情况）

| 约束 | 落实方式 |
|---|---|
| 禁止出站网络请求 | 全程未实现任何 HTTP 客户端 / URL 拉取逻辑，杜绝 SSRF；jsoup 只用于解析已在手的字节，`Jsoup.connect` 全仓无调用 |
| XML 解析防 XXE | POI 通过 `XMLHelper` 统一构造解析器，已设 `disallow-doctype-decl`、禁外部通用/参数实体、禁外部 DTD、开 `FEATURE_SECURE_PROCESSING`——DTD 都声明不了，外部实体自然无从声明；本服务自身不手工解析任何 XML（docx 关系查找走 POI 的 `PackagePart` API），因此没有第二个解析器需要维护加固。审计结论与理由见 `security/XmlHardening` 的类注释 |
| zip 安全预检（防 zip-slip / zip 炸弹） | docx/xlsx 本质是 zip 包，解析前先经 `ZipSafetyGuard` 校验：条目路径不得越出（zip-slip）、解压总大小上限 500MB、条目数上限 2000，超限 fast-fail 返回 `PARSE_FAILED`；POI 的解压比阈值（100x）作为第二道防线，拦住在中央目录里谎报大小的包 |
| 单文件大小上限 | 100MB，在进入解析前校验，超限 fast-fail；容器层再设 110MB 兜底，避免超大 body 在校验前被完整缓冲 |
| 解析超时 | 300s，解析在专用线程池执行、由 `Future.get(timeout)` 约束，超时后中断该 worker 并返回 `PARSE_FAILED` |
| 解析并发上限 | `PARSER_MAX_WORKERS`（默认 4）。这是本服务真实的并发天花板：解析是 CPU 密集的，若让每个被接受的连接都并行解析，会在核数远少于容器工作线程数的机器上互相拖垮 |
| 映射档案 YAML 反序列化 | SnakeYAML `SafeConstructor`（等价于 `yaml.safe_load`）——映射档案是随请求到达的数据，能实例化数据中所指定任意类的构造器是 RCE 原语，不是便利功能 |
| html 聊天记录安全 | 剥离 `<script>`/`<style>` 内容，`<img>` 只判断是否存在、从不下载 |
| OCR 依赖门禁 | `OCR_ENGINE=tesseract` 但未以 `-Pocr` 构建时，应用启动即 fast-fail，不会拖到首次遇到扫描页才报错 |

本服务不持有任何用户凭据、不做鉴权（鉴权由 kb-rag-server 侧负责），部署时不应直接暴露给公网。

## 项目结构

```
src/main/java/io/kbrag/parser/
  ParserApplication.java        Spring Boot 入口 + 启动期加固与 OCR 门禁
  config/
    ParserConstants.java        契约固定、不可配置的常量（100MB 上限、zip 500MB/2000 条目、150dpi、30% 失败线等）
    ParserProperties.java       可调运行时配置，每项对应一个同名环境变量
  error/                        错误码与解析异常类型（ParseException 及其子类）
  model/                        响应模型：ParseData / PageContent / ImageAsset / ChatSession / ApiResponse ...
  security/
    UploadGuard.java            文件大小上限
    ZipSafetyGuard.java         zip-slip / zip 炸弹预检
    XmlHardening.java           OOXML 加固与 XXE 审计结论
  support/
    TextDecoder.java            文本编码探测（utf-8-sig -> utf-8 -> gbk -> 替换兜底）
    NumberFormatting.java       数值单元格字符串化（避免 30 变成 "30.0"）
    Whitespace.java             Python 语义的空白判定（JDK 不认 &nbsp; 是空白，Python 认）
  web/
    ParseController.java        /health、/api/v1/parse、/api/v1/parse/chat 三个端点
    ParseExecutor.java          解析线程池 + 300s 超时 + 失败归一化
    ParseRequestExceptionHandler.java  畸形请求同样收敛为统一信封
  parser/
    DocumentParser.java         策略接口
    ParserRegistry.java         file_ext -> parser 的策略注册表
    PdfParser.java              pdf（PDFBox）：文本层 + 乱码降级 + 扫描页渲染与 OCR 回填 + 内嵌图片按对象去重
    DocxParser.java             docx（POI XWPF）：段落/表格 + 内嵌图片按原文顺序插回占位符
    ExcelParser.java            xlsx（POI XSSF）：每 sheet 一页 + markdown 表格
    CsvParser.java              csv + DelimiterSniffer 分隔符探测
    HtmlParser.java             M12 通用 HTML 页面解析（jsoup）：标题/块级分段→markdown，零出站请求
    ImageAssetCollector.java    图片采集与数量/字节上限保护
    GarbledTextDetector.java    乱码文本层判定
    TableMarkdown.java          markdown 表格渲染（docx/xlsx/csv 共用）
  chat/
    ChatLogParser.java          按 file_ext 分派与会话编排
    MappingProfile.java         映射档案：列名候选 + txt 行模板 + html 选择器
    MappingProfileLoader.java   档案加载（请求内联 profile_yaml 优先于 classpath 档案）
    PythonRegexTranslator.java  Python 正则方言 -> Java Pattern（命名组转编号组 + Unicode 字符类）
    TxtLinePattern.java         编译后的行模板，按档案里的名字取捕获组
    TxtChatAdapter.java         txt 行模式适配器：多行归并 + 30% 不匹配行失败线
    HtmlChatAdapter.java        html DOM 适配器：选择器定位 + script/style 剥离
    ValueNormalizer.java        is_self / send_time / msg_type 归一化
  ocr/
    OcrEngine.java              OCR 策略接口
    NoOpOcrEngine.java          OCR_ENGINE=none 的默认实现
    TesseractOcrEngine.java     可选 Tesseract 兜底（反射调用，单页超时降级）
    OcrEngineFactory.java       引擎解析与启动 fast-fail 门禁
src/main/resources/
  application.yml               端口、multipart 上限、snake_case、各环境变量绑定
  mappings/                     内置映射档案（memotrace / liuhen_txt / liuhen_html）
src/test/java/io/kbrag/parser/  112 个用例：每种文档格式、zip-slip / zip 炸弹负例、扫描页与内嵌图片、
                                上限保护、乱码降级、按页 markdown 无损拼回、chat 四格式正负例、
                                profile_yaml 优先级、OCR 三态、正则方言转换、时间/类型归一
tools/crosscheck.py             与 Python 实现的端到端对拍脚本（42 项）
```

## 与 Python 实现的等价性

单元测试只能证明各自符合自己的预期；要证明两套实现给同一份输入同一个答案，得让它们同时跑起来对同一批字节作答。`tools/crosscheck.py` 做的就是这件事：

```bash
# 终端 1
cd kb-rag-parser && .venv/bin/uvicorn app.main:app --port 20012

# 终端 2
cd kb-rag-parse-java && java -jar target/kb-rag-parse-java-1.1.0.jar --server.port=20011

# 终端 3
kb-rag-parser/.venv/bin/python kb-rag-parse-java/tools/crosscheck.py
```

覆盖 42 项：txt/md/sql/gbk 编码、csv、xlsx、docx（含内嵌图）、html/htm/破损标记/`&nbsp;`、pdf（单页/多页/内嵌图/重复 logo 去重/扫描页/扫描页超上限）、不支持扩展名、损坏文件；以及 chat 的 csv（正例、语音视频跳过、坏时间、多房间、缺 content 列、`&nbsp;` 列名）、xlsx、txt（两种内置模板、错格式失败、坏时间戳、自定义正则）、html（正例、图片占位、语音视频跳过、script 剥离、自定义选择器、选择器零匹配、`&nbsp;` 填充）、profile_yaml 优先级、未知档案、不支持扩展名。

比对口径：`/api/v1/parse` 比对 `code`、`pages` 数量与每页 `page_no`/`scanned`/`ocr_source`/`text`、`images` 的数量与 `image_id`/`page_no`/`kind`、markdown 中占位符个数、`warnings` 条数，以及两边各自的"逐页 markdown 拼接 == 合并 markdown"不变量；`/api/v1/parse/chat` 比对 `skipped` 计数与 `sessions`/`messages` **逐字段全等**（含 `msg_id` 与 `send_time`）。

**2026-09-02 实测 42/42 一致。** 实测中 pdf 文本层连换行位置都逐字符相同，`markdown` 完全相等。

对拍抓出过一处真实差异，值得记下来当作这类移植的教训：**JDK 与 Python 对「什么算空白」的判定不同。** `U+00A0` NO-BREAK SPACE——每个 `&nbsp;` 解码后的样子，也是 pdf 文本抽取的常见产物——在 Python 的 `str.strip()`/`str.split()` 眼里是空白，在 Java 的 `String.strip()`、`String.isBlank()`、`Character.isWhitespace` 和正则 `\s` 眼里都不是。放着不管，一个 `标题&nbsp;` 的标题会在这边留下不可见的尾字符而在那边被去掉，一页只含不间断空格的 pdf 会在那边算扫描页而在这边算文本页。`support/Whitespace` 用 `isWhitespace || isSpaceChar` 复刻了 Python 的 `str.isspace()`（前者覆盖 `U+001C`-`U+001F` 这些 isSpaceChar 漏掉的控制符，后者覆盖 `U+00A0`/`U+2007`/`U+202F` 这些 isWhitespace 漏掉的分隔符，缺一不可），全服务的空白处理统一走它。

唯一不作逐字节比对的是图片二进制：两侧的图片编码链路本就不同（见下），契约要求的是"同一张图被报告一次、位置正确、类型正确"，不是"字节相同"。

## 与契约的偏离说明

以下沿袭自 Python 实现，两侧一致：

- **docx 页码**：Word 文档在渲染前没有可靠的分页信息，整篇文档作为单一 `page_no=1` 返回，内嵌图片沿用同一 `page_no=1`；如需真实分页，需引入排版引擎，留作后续 TODO。
- **扫描页不再额外抽取内嵌图片**：整页已作为一张 `page_render` 图片产出，再抽其内嵌图会让同一内容被计两次。
- **乱码页判定**是对契约"无文本层"扫描页判定的补充（`GARBLED_PAGE_VALID_CHAR_RATIO_PCT`），M3-CONTRACTS.md §2.1 已同步该判定。
- **docx 内嵌图片的占位符位置**：能从段落 XML 匹配到 `r:embed` 引用时按原文顺序在该段落后插入占位符；无法匹配到具体段落的图片（表格单元格、页眉页脚内的图片）统一追加在文档末尾，仍计入 `images[]`，不会丢失。
- **txt/html 固定单会话**：`session_id`/`session_name` 取文件名 stem，不像 csv/xlsx 支持同文件多会话。
- **`memotrace` / `liuhen_txt` / `liuhen_html` 三份内置档案**依据公开约定编写，尚未用真实导出样例校准，见各 yml 顶部说明。
- **`request_id` 由本服务生成**，不透传请求头的 `X-Request-Id`——与 Python 实现的行为一致（OpenAPI 的字段描述提到透传，但两侧实现均为自生成）。

以下是本实现相对 Python 实现的偏离，均已实测确认不影响 kb-rag-server 的消费：

- **OCR 引擎为 Tesseract，`ocr_source` 取值为 `"tesseract"` 而非 `"paddle"`。** PaddleOCR 没有 JVM 绑定，而这一层要回答的问题只是"本服务能不能在没有模型 Key 的情况下读出扫描页"。契约仍然成立：kb-rag-server 判的是这个标记**存不存在**而不是它等于什么（`ParsedDocument.Page#isOcrBackfilled` 即 `ocrSource != null && !ocrSource.isBlank()`）。若严格要求枚举值为 `paddle`，`docs/openapi/kb-parser.yaml` 的 `ocr_source` 枚举需相应放宽——这是一处**需要 Owner 拍板的契约文本调整**，本文档不代为决定。
- **pdf 内嵌图片的编码**：JPEG 原样透传原始 DCT 字节（`media_type=image/jpeg`），其余（Flate 位图、CCITT 传真、JBIG2 等）解码一次后统一编码为 PNG。这些格式在 pdf 内部本来就没有独立文件形态，必须重编码；JPEG 单独走透传是因为把照片型扫描页重编码为 PNG 常常让体积翻数倍，而响应里每张图都是 base64。Python 实现走 PyMuPDF 的 `extract_image`，直出原始编码流与原始扩展名，因此 `media_type` 分布可能不同——契约只约束 `media_type` 是有效 MIME，未约束具体取值。
- **html 选择器是 CSS 全集**。Python 实现手写了最小选择器引擎，只支持 `tag`/`.class`/`#id`/`tag.class`；jsoup 带完整 CSS 选择器，是严格超集。为 Python 实现写的档案在这里原样可用；反过来，在这里写的档案若用了更复杂的选择器，拿回 Python 实现会被拒。档案作者需要知道这个方向性。
- **XXE 防护方式不同**：Python 侧在启动时用 `defusedxml.defuse_stdlib()` 全局 patch 标准库；JVM 没有等价的全局开关（每个解析器都来自调用方自行配置的工厂），因此这里靠"POI 自身已加固（审计）+ 本服务不手工解析 XML"达成同一姿态，另加解压比阈值作为 zip 炸弹的第二道防线。审计结论写在 `security/XmlHardening` 的类注释里。
- **`txt:` 正则的方言转换**：档案里的 Python 命名组 `(?P<name>...)` 被转成编号组 + 名字旁表（Java 的组名不允许下划线，契约里的 `send_time` 直接无法表达），`\d`/`\w`/`\s` 开启 Unicode 语义以对齐 Python 3。这是让同一份 `t_kb_source_mapping` 档案能喂给任一实现的必要条件，见 `chat/PythonRegexTranslator` 与其单元测试。
- **chat csv 固定按逗号解析**（与 Python 实现一致，不做分隔符探测）；文档端点的 csv 解析器则会探测分隔符。两者的差别是有意的：文档 csv 可能来自任意导出工具，而 chat csv 要按候选列名匹配表头，猜错分隔符会让整行表头变成一列，最终报成"找不到 content 列"这种驴唇不对马嘴的错误。

## 文档导航

本仓库只维护自身的 README/CHANGELOG 与代码内注释；跨仓库的架构总览、端到端业务流程与里程碑契约集中维护在 [kb-rag-deploy](https://github.com/liulangjietou/kb-rag-deploy) 仓库：

- 整体架构（本服务在 kb-rag 系统中的定位、与 kb-rag-server 的调用关系）：`docs/ARCHITECTURE.md` §4
- 端到端业务流程（文档导入、聊天记录导入等完整链路，本服务只是其中一环）：`docs/FLOWS.md`
- 各里程碑契约（本服务行为的规范来源，本仓库的实现与偏离说明均以其为准）：`docs/M1-CONTRACTS.md`、`docs/M3-CONTRACTS.md`、`docs/M8-CONTRACTS.md`、`docs/M12-CONTRACTS.md`、`docs/M14-CONTRACTS.md`
- 接口契约（OpenAPI）：`docs/openapi/kb-parser.yaml`
- 行为基准实现：[kb-rag-parser](../kb-rag-parser)（Python），本仓库的每一处判定以它为准绳
- 贡献与安全：本仓库的 `CONTRIBUTING.md` / `SECURITY.md`
