# M9 开发契约（二期 · 标注语义与图搜 · 增量于 M1-M8 契约）

> 范围 = 二期清单项 5/6/7（需求文档 v1.14 各"二期"标注转正；完工后需求文档升 v1.15）：
> ⑤父片禁用子片的精确文本剔除（§4.5：按 t_kb_chunk 落 parent_start_offset/parent_end_offset 精确剔除文本段并定义编辑后失效处理）；⑥标注跨版本相似度辅助迁移（§4.5：v1.6 教训——**禁止照搬 §4.6 以 span 为分母的非对称重叠率**，须用对称相似度；只辅助推荐、人工确认，不自动迁移）；⑦图片 query（§4.8：v1.8 删除时预留的二期语义——"VLM 转文本后拼入 query"，另定义降级行为）。
> 全局约定沿用 M1 §0；主会话既有决策：项 7 完整实现 + 零 Key 降级验收，真实 VLM 效果待 Key 恢复补验（与 M3 同模式）。

## 0. 核心设计定版（偏离须申报）

1. **子片偏移落库（Flyway V10）**：`t_kb_chunk` 加 `parent_start_offset`/`parent_end_offset`（int，可空；语义=子片文本在父片 content 中的 [起,止) 字符偏移）。父子模式切分时由切分器落值（子片本就从父片切出，偏移是切分的副产品，不做事后反查匹配）；**单点校验**：父片 content 按偏移截取必须与子片规范化前文本一致，不一致落 null 并 info 日志（宁缺勿错）。
2. **失效语义**：子片编辑（M4a 标注编辑，内容变更）→ 该子片偏移置 null；子片合并/拆分产生的新行 → null（不再对应原始偏移）；父片编辑 → 该父片**全部**子片偏移置 null（母文本变了所有偏移作废）。失效收敛在标注写路径单点，检索侧只判 null。
3. **精确剔除（检索父子归并）**：`hide_parent_with_disabled_child=false` 的库，父片返回前对**禁用子片且偏移非 null**者，从父片文本剔除 [start,end) 段并以固定省略标记 `（已省略被禁用内容）` 替换（多段按偏移倒序剔除防位移）；任一禁用子片偏移为 null → 该父片整体回退现状（整片返回，不做部分剔除——半剔除会造成"看似完整实则缺段"的误导）；node.metadata 增 `redacted_child_count`（发生剔除时）。`hide_parent_with_disabled_child=true` 行为不变（整父片隐藏）。
4. **对称相似度（项 6）**：`AnnotationMigrationAdvisor`（kb-domain 纯函数）：规范化（沿用 chunk_text_hash 同款规范化）后字符 3-gram **Dice 系数** = 2×|交|/(|A|+|B|)（对称、长度无偏；v1.6 否掉的非对称指标不可用，本条为红线）；候选=新激活版本同 kb 的全部分片，取 top 3 且分数 ≥ 0.35（`kb.annotation.migration-min-score` 可配）；短文本（规范化后 <10 字符）不给候选（3-gram 无意义）。
5. **辅助迁移 API**：`GET /api/v1/documents/{docId}/annotations/pending-review` 响应行增 `suggestions:[{chunk_id, content_preview(≤120字), score}]`（懒计算：请求时算，不落库不缓存——pending 列表本就低频）；新增 `POST /api/v1/annotations/{annotationId}/migrate` body `{target_chunk_id}`：把该标注（禁用/编辑语义按 M4a 既有标注类型）应用到目标分片并将原 pending 记录置已处理（幂等：重复 migrate 同目标返回当前状态）。**不做自动迁移、不做批量端点**（误迁移代价大于逐条确认成本）。
6. **图片 query（项 7）**：对外 `POST /knowledge/search`、`/knowledge/chat` 与管理端 chat-preview 入参增可选 `images: [base64 字符串]`（**仅 base64，不收 URL——外部 URL 是 SSRF 面**；上限 3 张、单张解码后 ≤5MB、总量 ≤10MB，越界 INVALID_PARAM）；处理=逐张 VisionProvider 转文本（复用 M3 视觉链路与超时），转出文本以 `[图片内容] ` 前缀拼接到 query 尾部再进既有检索链路（改写开启时拼接发生在改写**之前**——图片语义应参与改写）；**降级**：零 Key/无视觉模型/超时/失败 → 忽略全部图片继续纯文本检索，degraded += `image_understanding_unavailable`（新枚举值，需求文档 v1.15 + OpenAPI 同步）；纯图片无文本 query 且理解失败 → INVALID_PARAM（无可检索内容，明确报错优于返回全库随机结果）。审计 query_digest 记录拼接后文本（脱敏规则既有）。
7. **配置键**：`kb.annotation.migration-min-score=0.35`（ANNOTATION_MIGRATION_MIN_SCORE）、`kb.retrieval.image-query-max-count=3`（IMAGE_QUERY_MAX_COUNT）、`kb.retrieval.image-query-max-bytes=5242880`（IMAGE_QUERY_MAX_BYTES）。

## 1. 分工
- **kb-rag-server（opus）**：§0 全部；单测清单（精确断言）：偏移截取校验不一致落 null；三类失效路径（子编辑/合并拆分/父编辑全量）；多段倒序剔除结果文本精确断言；任一 null 整片回退；redacted_child_count；hide=true 不变；Dice 公式（"abcd" vs "abce" 手算值）、对称性（sim(a,b)=sim(b,a)）、短文本无候选、阈值过滤、top3 截断；migrate 幂等与 pending 状态流转；图片 query 张数/大小校验、拼接位置在改写前、全失败降级+degraded、纯图无文本失败 INVALID_PARAM、audit digest 含拼接文本
- **kb-rag-web（sonnet）**：待复核工作台 pending 行展开显示 suggestions（分数+预览+"迁移到此分片"按钮确认框）；chat 调试页与 API 调试页支持贴图/选图（转 base64，张数大小前置提示，权威校验在 server）；degraded 标签表增 `image_understanding_unavailable`；调试页 redacted_child_count 提示行
- **kb-rag-deploy（主会话收尾）**：OpenAPI 0.10.0-m9、CHANGELOG、.env.example 三键、需求文档 v1.15（images 入参恢复定义 + degraded 枚举 + §4.5 两项转正）、契约 §5 回补

## 4. 验收（主会话，零 Key 域）
① 父子库导入→禁用一个子片→父片返回文本缺该段且带省略标记与 redacted_child_count；编辑该子片→偏移失效→整片回退；hide=true 库行为不变
② 上传新版本（内容局部改动）→ pending-review 出 suggestions 且分数排序合理（改动小的分片高分）；migrate 到建议分片→原 pending 消失、目标分片获得标注（禁用生效可检索验证）；短文本标注无候选
③ 图片 query：零 Key 下带 images 调用 → degraded 含 image_understanding_unavailable 且文本检索正常；超张数/超大小 INVALID_PARAM；纯图无文本 INVALID_PARAM；审计落库
> Key 恢复后补验：真实 VLM 图片理解拼接检索、真实场景迁移建议质量。

## 5. 实现期修订（完工后回补）

**主会话定版**：web 五条假设全采纳（suggestions 恒为数组、migrate 响应按 void 消费、redacted_child_count 仅剔除时出现、images 只在对外/预览三端点、客户端限制仅提示权威在 server）；server 响应字段定版见其报告（migrate 返回 already_migrated 等六字段）。

**server 申报偏离八条全接受**：①新增第四配置键 `IMAGE_QUERY_MAX_TOTAL_BYTES=10485760`（总量 10MB 无法由张数×单张推出）；②`KnowledgeCallRequest.query` 去 @NotBlank，query/images 至少其一的校验收敛到 ImageQueryService 单点（纯图调用的前提）；③migrate 仅支持 TOGGLE/EDIT（MERGE/SPLIT 无法对单目标表达），EDIT 摘录被截断（>500 字）拒绝防静默丢尾；④**候选范围修正为同文档当前激活版本**（契约原文"同 kb 全部分片"系措辞过宽——跨文档不是迁移，且与懒计算矛盾），migrate 同样强制同文档；⑤部分图片成功仍按"忽略全部"降级并提前止损省调用；⑥父片 merge/split 触发的 recut 子片偏移一律落 null（按契约字面，该父片退回整片返回直至重导入——已知行为后果，重算列为可选优化）；⑦SplitChunk/FixedLengthTextSplitter 结构化产出偏移（"不做事后反查"的必要条件；LLM 语义切分不产偏移故校验分支真实可达）；⑧DisabledChildVisibility 内部签名改携带偏移，对外 JSON 不变。

**验收结果（2026-07-27，零 Key 域）**：①父子库偏移落库（含 overlap 场景 0-312/304-454 的 clamp 正确）；禁用子片后经**启用兄弟片**召回的父片文本缺被禁段、含省略标记、redacted_child_count=1；被禁子片是唯一命中路径时父片整体不召回（正确的引擎侧过滤语义，剔除只作用于"父片经其他子片召回"面）；编辑子片后偏移失效整片回退无标记；hide=true 整父片隐藏不变。②pending-review 带 suggestions（**验收笔记两条**：新版本激活为异步，INDEXED 后立即查询会因激活未完成而空列表，属时序非缺陷；高度模板化语料会使全部候选同分——3-gram 对只差实体词的同构文本判别力有限，真实散文无此问题，差异化语料实测 top1=0.793 正确指向改动段且低相关候选被阈值滤除）；migrate 成功/幂等/pending 消失/目标分片禁用生效全过；单文档单分片场景候选唯一且正确。③带图零 Key degraded=image_understanding_unavailable 且文本检索正常；超张数/纯图无文本/非法 base64 三类 INVALID_PARAM；审计落库。构建：server 821（+59）全过、web tsc 0 错。Key 恢复后补验：真实 VLM 图搜、真实语料迁移建议质量。
