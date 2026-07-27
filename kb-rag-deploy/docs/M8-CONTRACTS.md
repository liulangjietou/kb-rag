# M8 开发契约（二期 · 导入与解析增强 · 增量于 M1-M7 契约）

> 范围 = 二期清单项 1/2/3/4（需求文档 v1.13 各"二期"标注的转正实现；完工后需求文档升 v1.14 把对应标注改为已实现语义）：
> ①聊天记录 TXT/HTML 格式（§4.2 格式范围——**主会话定版：真实样例仍未到位，按留痕/MemoTrace 与微信公开已知导出格式实现内置模板 + 界面可配，样例到位后校准**，此决策写入 §5）；②PaddleOCR 本地 OCR 兜底（§8，零 Key/离线可解析扫描件）；③聊天聚合重叠滑窗 + 近重复归并（§4.2）；④字段映射维护界面（§4.2，t_kb_source_mapping 表一期已就位）。
> 全局约定沿用 M1 §0（作者标注、英文注释与日志、info/error 带错误码、lombok、无魔法值、fast-fail 单点、不 commit/不切分支）；parser 遵循既有 Python 规范（模块 docstring 尾 Author: owlzhangfq@gmail.com、解析安全约束 §4.2：禁出站请求/defusedxml/zip 防护）。

## 0. 核心设计定版（偏离须申报）

1. **TXT 适配器（parser）**：扩展既有 ChatMessage 适配器体系（app/chat/），新增 `txt` 行模式解析：内置两种行模板（A. `YYYY-MM-DD HH:MM:SS 发送人` 换行消息体——留痕/MemoTrace TXT 风格；B. `发送人 (YYYY-MM-DD HH:MM:SS):` 同行/换行消息体——微信 PC 端风格），模板以**行首正则 + 命名捕获组（send_time/sender）**表达，随 mapping profile 承载（yml 的 `txt:` 段），支持自定义正则覆盖；多行消息体归并到上一条；无法匹配任何行模板的行数占比 > 30% → 解析失败并报可操作错误（防拿错格式静默出垃圾）。
2. **HTML 适配器（parser）**：留痕导出 HTML 的 DOM 解析（标准库 html.parser 或 bs4——不引入新的重依赖优先标准库；**严禁**加载远程资源，剥离 script/style，遵循既有 HTML 安全约束）；选择器同样进 mapping profile（`html:` 段：消息节点/发送人/时间/正文选择器），内置留痕模板；图片消息节点转 `[IMAGE]` 占位按既有图片消息语义处理，语音/视频跳过。
3. **格式接入方式**：复用既有 chat 两步式导入（preview→confirm）与 mapping_profile 机制，`file_ext` 扩 txt/html；ChatParseData 中间模型不变（一期扩展点兑现——新增格式=新增适配器，server 侧仅放开扩展名白名单与 profile 校验）。
4. **PaddleOCR 兜底（parser）**：可选依赖（`requirements-ocr.txt`，默认不装保镜像体积）；环境开关 `OCR_ENGINE=none|paddle`（默认 none）。解析扫描页时的三级次序：kb-server 侧 VLM（有 Key，现状）→ parser 侧 PaddleOCR（`OCR_ENGINE=paddle` 且已装）→ 跳过并记降级（现状）。落点：扫描页渲染 PNG 后 parser 直接出 OCR 文本回填（新增 `ocr_source: paddle` 标记进解析产物 metadata），kb-server 对带 ocr_source 的页**不再**调 VLM（省一次调用；VLM 与 OCR 的取舍权在部署配置，不做逐页混合）。`OCR_ENGINE=paddle` 未装依赖 → 启动 fast-fail 明确报错。中英文模型（ch_PP-OCRv4）；OCR 超时/异常按页降级跳过并计数。
5. **重叠滑窗（server）**：聊天聚合参数增 `window_overlap`（消息数，默认 0=现状顺切，上限 < 窗口最大消息数一半，违规 INVALID_PARAM 单点校验）；chunk metadata 增 `window_seq`（窗口序号）与 `msg_span:[起,止]`（会话内消息序号区间，闭区间）。
6. **近重复归并（server 检索侧）**：库内融合后、rerank 前——同 `session_id` 且 `msg_span` 区间重叠率（交集消息数/较小窗口消息数）≥ 0.5 的命中只保留排名最高者（被并者进 metadata `merged_window_chunk_ids` 上限 5，供调试页展示）；归并实现放 ParentChildMerger 同层的独立单点（勿混入父子逻辑）；非聊天 chunk（无 msg_span）零影响。
7. **字段映射维护（server+web）**：`t_kb_source_mapping` 落 CRUD：`GET/POST /api/v1/source-mappings`、`PUT/DELETE /api/v1/source-mappings/{mappingId}`（行含 name UK、source_type(csv/xlsx/txt/html)、profile_yaml 文本、is_builtin）；**内置模板启动时从 parser 侧 yml 种子化入库（幂等，is_builtin=true 不可删可复制）**；导入 preview 时 mapping_profile 参数改为传 mappingId 或内置名（兼容旧值）；parser 的 `/parse-chat` 接口 mapping 内容由 server 随请求传递（profile_yaml 全文），parser 不再只认本地文件（本地 yml 保留作种子与默认）。web：系统设置新增"导入映射"tab（列表/新建/编辑 yaml 文本域/复制内置/删除自定义），聊天导入向导的 profile 下拉改为读该列表。
8. **降级/错误码**：无新增 degraded 枚举（导入是管理操作非检索链路）；新错误码沿用 INVALID_PARAM/PARSE_FAILED 体系。
9. **配置键**：parser `OCR_ENGINE=none`、`OCR_TIMEOUT_S=30`；server 无新键（overlap 是知识库级参数非部署键）。

## 1. 分工
- **kb-rag-parser（sonnet）**：§0.1/0.2/0.3（parser 半）/0.4；pytest 覆盖：TXT 两模板+自定义正则+30% 失败线+多行归并、HTML 留痕模板+安全剥离+图片占位、profile_yaml 随请求传入优先于本地文件、OCR 开关三态（none 跳过/paddle 未装 fast-fail/装了出文本带 ocr_source——paddle 真实推理用小图 fixture，CI 无依赖时 skip 标记）
- **kb-rag-server（opus）**：§0.3（server 半）/0.5/0.6/0.7（API+种子化）；Flyway V9（t_kb_source_mapping 若一期建表缺列则补齐，按实际表结构定）；单测：overlap 校验单点、msg_span 落 metadata、近重复归并（重叠率 0.5 边界/保排名最高/merged ids ≤5/非聊天零影响/与父子归并次序）、映射 CRUD 与内置保护、preview 兼容旧 profile 名
- **kb-rag-web（sonnet）**：§0.7 web 半（导入映射 tab、聊天导入向导 profile 下拉与 TXT/HTML 格式项、调试页 merged_window_chunk_ids 展示走既有 metadata 明细惯例）
- **kb-rag-deploy（主会话收尾）**：OpenAPI 0.9.0-m8、CHANGELOG、.env.example（OCR 两键注释在 parser 段）、需求文档 v1.14、契约 §5 回补

## 4. 验收（主会话，零 Key 域）
① 构造留痕风格 TXT 与 HTML 样例文件→两步导入→分片可检索、会话/时间 metadata 正确；自定义正则 profile 生效；坏格式文件报可操作错误
② `OCR_ENGINE=none`（默认）行为与现状完全一致；`paddle` 未装依赖启动 fast-fail（可操作报错）；装依赖后对扫描 PDF 出文本（本机 pip install 验一次，结果记契约）
③ overlap>0 导入聊天→相邻 chunk 含重叠消息、msg_span 正确；检索命中重叠窗口→归并只留最高分并带 merged ids；overlap=0 完全现状
④ 映射 CRUD+内置模板保护（不可删）、新建自定义 profile 走通 TXT 导入；旧 profile 名兼容
> 真实样例校准（项 1 定版遗留）：用户提供留痕/微信/钉钉真实导出后，校准内置模板并回补契约。

## 5. 实现期修订（完工后回补）

**主会话定版**：web 假设七条全采纳（mapping_id 主键名、created_at/updated_at、GET 裸数组、独立 `POST /{id}/copy` 端点、PUT 全量替换、mapping_profile wire 字段兼容旧内置名、格式不匹配由 server preview 拦截）；parser 接口定版（`profile_yaml` 表单字段优先于本地文件、按扩展名内置默认 memotrace/liuhen_txt/liuhen_html、txt/html 单会话、PageContent.ocr_source）；metadata 键名 window_seq/msg_span/merged_window_chunk_ids。

**parser 申报偏离六条全接受**：①30% 失败线分母只计非空行、分子不含已开始消息的续行（防多行消息误判）；②txt/html 固定单会话（导出本身即单一对话转储）；③空配置 fast-fail 不返回空会话；④OCR 版本选型留主会话实测校准（见下）；⑤HTML 选择器为自研最小 CSS 子集（tag/.class/#id/tag.class，标准库优先）；⑥内置模板按公开约定编写、样例到位只改 yml 不改代码。

**server 申报偏离七条全接受**：①**t_kb_source_mapping 一期"表结构就位"系失实，实为从未建表**——V9 由补列改为建表（需求文档 v1.14 已修正该表述）；②CSV↔XLSX 在格式闸门互通（一份列名 profile 天然服务两格式）；③默认 profile 解析链=配置默认可读该扩展名→该格式内置行→不传由 parser 用自身默认；④**修复既有真实缺陷**：UpdateIndexConfigRequest 的清洗/聊天聚合校验被父子分片 early-return 短路、单层库从未生效，上移为无条件执行（可观测变化：以前能存的非法单层配置现在被拒）；⑤profile_yaml 结构不在 server 复刻校验（schema 属主 parser，格式错误由 PARSE_FAILED 上报）；⑥HTTP 适配器无单测沿历史惯例、字段名在 port 层断言；⑦overlap 上限=overlap×2<max_messages，聚合器 clamp、校验单点在请求入口。

**验收期发现并修复的第三个缺陷（M7 遗留）**：`NEO4J_URI` 为空时自定义健康探测正确退避，但 classpath 上的 neo4j-java-driver 触发 **Spring Boot Neo4j 自动配置**默认连 bolt://localhost:7687 并注册自带健康探测→无图部署整体健康 DOWN，"空 URI 零影响"契约被自动配置击穿；修复=application.yml 排除 Neo4jAutoConfiguration（图栈全部经 GraphStoreConfig 装配）。E2E 复验：URI 空→健康 UP 且无 neo4j 探针。

**OCR 依赖实测校准（偏离④的闭环）**：原拟 paddlepaddle==2.6.2/paddleocr==2.7.3 在 macOS arm64 + Python 3.13 无 wheel 不可装；重 pin 为 **paddlepaddle==3.3.1 + paddleocr==3.3.3** 并把 engine.py 适配 PaddleOCR 3.x API（use_textline_orientation/predict()/rec_texts，关闭文档级预处理模型——本引擎只见已摆正的单页渲染图）。

**验收结果（2026-07-27，零 Key 域）**：①TXT 双步导入→"火星电台"可检索、session/msg_time/msg_span metadata 正确；HTML 导入（script/style 剥离、图片占位）→"银河邮局"可检索；坏 TXT 报 `4/4 lines (100%) matched no configured txt: line template (threshold 30%)` 可操作错误；③overlap=1/window=4 导入 10 消息→窗口 (0,3)(3,6)(6,9) 相邻重叠、msg_span 落库；11 消息尾窗场景（重叠率恰 0.5）→归并触发、存活者带 merged_window_chunk_ids、0.5 以下不误并；越界 overlap=2 被 INVALID_PARAM 拦截（**验收笔记：等长窗口在 overlap×2<max 约束下重叠率恒<0.5，归并的实际触发面是尾窗/重导入等短窗场景——语义正确非缺陷**）；④内置三模板种子化、内置不可删（INVALID_PARAM）、复制→编辑自定义正则 profile→专用格式 TXT 导入检索全通、旧内置名兼容、格式不匹配闸门生效；②OCR_ENGINE=none 与现状完全一致（①③④全程默认档通过即证）、paddle 未装依赖 fast-fail 报错含安装指引、**装依赖后真实推理通过**（扫描 PDF 页出文本且 ocr_source=paddle，pytest 实测；运维笔记：默认模型源 HuggingFace 在部分网络不可达，此时设 `PADDLE_PDX_MODEL_SOURCE=ModelScope` 即可，首次运行需联网下载 PP-OCRv4 模型，纯离线部署须预置模型缓存目录）。三仓构建：server 762（+74）/parser 45+1skip/web tsc 0 错。
