# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。

本仓库是 [kb-rag-parser](../kb-rag-parser)（Python 实现）的功能对等移植，行为以其为基准。
因此本文件记录的是**移植本身**与移植过程中做出的实现选择，不重复 kb-rag-parser 的里程碑
沿革（M1/M3/M8/M12/M14 各自新增了什么，见该仓库的 CHANGELOG.md 与 kb-rag-deploy 的
`docs/M*-CONTRACTS.md`）。

## [未发布] - 0.1.0 首个版本

### 新增

- **Java 实现的文档与聊天记录解析微服务**，与 kb-rag-parser 功能对等：`GET /health`、
  `POST /api/v1/parse`、`POST /api/v1/parse/chat` 三个端点，统一 `{code, data, message, request_id}`
  响应信封，端口 20001，环境变量名与 Python 实现逐一对齐（`SCANNED_PAGE_TEXT_THRESHOLD`、
  `MAX_IMAGES_PER_DOC`、`OCR_ENGINE` 等），同一份部署环境可直接切换两种实现。
  技术栈 Java 17 + Spring Boot 3.3.9，与 kb-rag-server 同版本线。
- **文档解析**：pdf（PDFBox：文本层 + 乱码页降级 + 扫描页 150dpi 渲染 + 内嵌图片按对象文档级
  去重）、docx（POI XWPF：段落/表格按原文顺序 + 内嵌图片占位符插回）、xlsx（POI XSSF：每 sheet
  一页 + markdown 表格）、csv（Commons CSV + 分隔符探测）、txt/md/sql（编码探测后原样透传）、
  html/htm（jsoup：标题与块级分段→markdown，剔除 script/style/noscript/template）。
  `pages[].markdown` 逐页切片满足"按 `\n\n` 拼接与 `data.markdown` 逐字符相等"的 M14 不变量。
- **聊天记录解析**：csv/xlsx 列名映射、txt 行模板正则、html DOM 选择器四种来源；
  `profile_yaml` 请求内联档案优先于 classpath 档案；语音/视频剔除并计入 `skipped`，
  图片消息保留；`send_time` 自动判别 epoch 秒/毫秒、原生日期单元格与常见字符串格式。
  内置 `memotrace` / `liuhen_txt` / `liuhen_html` 三份档案，内容与 Python 实现同源。
- **安全约束**全部落地：100MB 上传上限、zip-slip 与 zip 炸弹预检（500MB / 2000 条目，
  并以 POI 解压比阈值作为第二道防线）、300s 解析超时、`PARSER_MAX_WORKERS` 并发上限、
  SnakeYAML `SafeConstructor`、零出站网络请求、OOXML XXE 加固。
- **可选本地 OCR 兜底**：Maven profile `ocr` + `OCR_ENGINE=tesseract`，默认不构建、不启用；
  配置了却没以 `-Pocr` 构建时启动 fast-fail。单页超时按页降级，绝不失败整篇。
- **112 个测试用例**：覆盖每种文档格式、zip-slip / zip 炸弹负例、扫描页与内嵌图片、上限保护、
  重复图片去重、乱码页降级、按页 markdown 无损拼回、chat 四格式正负例、`profile_yaml` 优先级、
  OCR 三态、正则方言转换、空白语义、时间与消息类型归一。样例文件全部由代码生成，不提交二进制 fixture。
- **CI 门禁**：根仓库 `.github/workflows/ci.yml` 新增 `parse-java` job（JDK 17，`mvn -B -ntp verify`），
  与既有 server / parser / web / deploy 四个 job 并列。刻意用默认 profile 构建——那也就顺带验证了
  反射式 OCR 引擎在 tess4j 不在 classpath 时仍能编译运行。
- **`tools/crosscheck.py` 两实现对拍脚本**：把同一份样例字节同时发给 Python 与 Java 两个服务，
  逐项比对契约字段。这是"两套实现行为等价"这一说法的证据来源——单元测试只能证明各自符合
  自己的预期。2026-09-02 实测 **42/42 一致**，其中 pdf 文本层连换行位置都逐字符相同、
  `markdown` 完全相等，chat 消息逐字段全等（含 `msg_id` 与 `send_time`）。

### 修复

- **`support/Whitespace`：按 Python 语义判定空白。** 这是对拍抓出来的真实差异，不是预防性改动：
  `U+00A0` NO-BREAK SPACE（每个 `&nbsp;` 解码后的样子，也是 pdf 文本抽取的常见产物）在 Python 的
  `str.strip()`/`str.split()` 眼里是空白，在 Java 的 `String.strip()`、`String.isBlank()`、
  `Character.isWhitespace` 与正则 `\s` 眼里都不是。放着不管，`标题&nbsp;` 会在 Java 侧留下不可见
  的尾字符而在 Python 侧被去掉，一页只含不间断空格的 pdf 会在 Python 侧算扫描页而在 Java 侧算
  文本页。现以 `isWhitespace || isSpaceChar` 复刻 Python 的 `str.isspace()`（前者覆盖
  `U+001C`-`U+001F` 这些 `isSpaceChar` 漏掉的控制符，后者覆盖 `U+00A0`/`U+2007`/`U+202F` 这些
  `isWhitespace` 漏掉的分隔符，缺一不可），扫描页阈值判定、乱码占比统计、HTML 空白折叠、
  聊天字段裁剪、表头归一化等全部改走它。对拍相应新增 3 项 `&nbsp;` 用例。

### 实现选择（与 Python 实现的差异，详见 README「与契约的偏离说明」）

- **pdf 解析库改用 Apache PDFBox（Apache-2.0）**，而非 Python 实现的 PyMuPDF（AGPL-3.0）。
  这解开了 kb-rag-parser 的 README 与 NOTICE 记录在案、待项目 Owner 决策的 AGPL-3.0 合规
  关系——它正是那份记录中列出的可能方向之二（"更换 pdf 解析库为许可更宽松的替代方案"）的
  一个已完成、已验证的落地。本仓库直接依赖全部为 Apache-2.0 / MIT / BSD-3-Clause；传递依赖中
  Logback 与 `jakarta.annotation-api` 为弱 copyleft 双许可，其义务只在修改并分发该库本身时
  触发，与 AGPL-3.0 的网络服务条款性质不同，逐项核实见 `NOTICE`。
- **OCR 引擎为 Tesseract，`ocr_source` 取值 `"tesseract"` 而非 `"paddle"`**：PaddleOCR 无 JVM
  绑定。契约仍成立，因为 kb-rag-server 判的是该标记存不存在而非等于什么
  （`ParsedDocument.Page#isOcrBackfilled`）。若要求 OpenAPI 的 `ocr_source` 枚举严格为 `paddle`，
  需相应放宽该枚举——这是一处待 Owner 拍板的契约文本调整。
- **`chat/PythonRegexTranslator`**：把映射档案里 Python 风格的 `(?P<name>...)` 命名组转成编号组
  加旁挂名字表，并开启 `UNICODE_CHARACTER_CLASS`。这不是锦上添花而是必需——Java 的正则组名
  不允许下划线，契约里的 `send_time` 直接无法表达；没有这一层，同一份 `t_kb_source_mapping`
  档案就喂不进两种实现，档案格式会分裂成两套。
- **pdf 内嵌图片编码**：JPEG 原样透传原始 DCT 字节，其余格式解码后统一编码为 PNG
  （它们在 pdf 内部本就没有独立文件形态）。因此 `media_type` 分布可能与 Python 实现不同；
  契约只约束它是有效 MIME。
- **html 选择器为 CSS 全集**（jsoup），是 Python 侧最小选择器子集的严格超集：为 Python 实现写的
  档案在这里原样可用，反之则未必。
- **XXE 防护姿态相同、手段不同**：JVM 没有 `defusedxml.defuse_stdlib()` 那样的全局开关，
  这里靠"POI 自身已加固（审计结论写在 `security/XmlHardening` 类注释）+ 本服务不手工解析任何
  XML"达成，docx 关系查找走 POI 的 `PackagePart` API 而非自建 DOM 解析。
- **`extractPageText` 提为可覆写的实例方法**：ToUnicode CMap 损坏的 pdf 无法用 PDFBox 自己的
  写入器伪造（它总是产出合法 CMap），乱码页降级路径除了在这个接缝替换抽取结果之外没有别的
  端到端验证办法。这是为可测试性做的最小让步，位置与理由都写在方法注释里。

### 已知限制 / 待校准

- 三份内置映射档案沿袭自 Python 实现，同样依据公开约定编写，**尚未用真实导出样例校准**，
  详见各 yml 顶部说明。
- txt/html 固定解析为单一 `ChatSession`，不支持同文件多会话拆分（与 Python 实现一致）。
- docx 无可靠分页信息，整篇作为 `page_no=1` 返回（与 Python 实现一致）。
- 本地 OCR 走 Tesseract，需要宿主机自行安装引擎与语言包；本仓库的测试以桩件验证解析侧行为，
  不验证 Tesseract 自身的识别质量。
