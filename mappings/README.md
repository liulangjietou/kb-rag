# 聊天记录列名映射模板（M3）

`POST /api/v1/parse/chat`（`docs/M3-CONTRACTS.md` §2.2）解析聊天记录导出文件
（CSV/Excel）时，通过表单字段 `mapping_profile` 指定使用哪一份列名映射档案，把
来源各异的原始列名统一映射到 `ChatMessage` 中间模型的字段（`session_id` /
`session_name` / `sender` / `is_self` / `send_time` / `msg_type` / `content`）。

映射档案是 **kb-rag-parser 解析适配器的配置化扩展点**：新增一种聊天记录来源，
只需在本目录下新增一份 yml 档案，**不需要改 parser 代码**（对应知识库需求文档
§4.2「统一中间模型：所有解析器输出统一的 ChatMessage 结构……新增一种 IM 只需
新增一个解析适配器」）。

## 目录结构

```
mappings/
└── chat/
    └── memotrace.yml   # 微信「留痕」/MemoTrace 类工具导出（M3 内置默认档案）
```

按来源分类建子目录（当前只有 `chat/`，对应聊天记录一类来源；预留给二期其他
接入类型使用同样的目录约定）。

## 如何为新来源新增映射档案

1. 在 `mappings/chat/` 下新建 `<来源名>.yml`（如 `dingtalk.yml`），复制
   `memotrace.yml` 的结构作为模板
2. 修改文件顶部 `profile:` 为来源名（须与文件名去掉扩展名一致，`mapping_profile`
   表单参数按此值查找档案）与 `description:`
3. 按新来源的实际导出列名，逐项填写 `field_mapping` 下每个统一字段的
   `candidates` 候选列名数组（按优先级从高到低排列，第一个在源文件中非空命中的
   候选列即采用；匹配对列名大小写与首尾空格容错）
4. 如新来源的时间列格式不在 `time_parse.string_formats` 常见格式之列，追加一条
   格式串（parser 侧的时间解析器按 epoch 自动识别 → 配置的字符串格式列表顺序
   尝试，全部失败则该消息落 `skipped.other` 并记 info 日志，不中断整批解析）
5. 顶部注释加作者行 `# Author: <你的邮箱>`（新建配置文件同样适用于知识库需求
   文档 §5 的作者标注规范）
6. 调用 `POST /api/v1/parse/chat` 时传 `mapping_profile=<来源名>` 使用新档案；
   kb-rag-server 侧 `mappings/chat/` 目录随 deploy 仓分发，parser 服务需能读到
   同一份文件（本地开发为 bind mount，容器内路径由 kb-rag-parser 部署配置决定）

## 已知限制（M3 定版，M8 已部分放开）

- ~~一期聊天记录格式收敛为 CSV/Excel，TXT 行模式识别与 HTML DOM 解析降级为
  二期~~ **M8 已交付**：`file_ext` 白名单放开为 csv/xlsx/txt/html 四种。TXT 行模式
  识别（行首正则 + 命名捕获组，内置留痕/MemoTrace 与微信 PC 端两种行模板）与
  HTML DOM 解析（消息节点/发送人/时间/正文选择器，内置留痕模板）分别通过映射
  档案的 `txt:` / `html:` 段配置，parser 按扩展名内置默认档案
  `memotrace` / `liuhen_txt` / `liuhen_html`，自定义正则与选择器均可覆盖
  （见 `docs/M8-CONTRACTS.md` §0.1–0.3）
- **M8 起映射档案不再只有本目录的静态 yml**：`t_kb_source_mapping` 表（Flyway V9）
  承载映射档案的增删改查（`GET/POST /api/v1/source-mappings`、
  `PUT/DELETE /api/v1/source-mappings/{mappingId}`、`POST /{id}/copy`），web 端在
  系统设置「导入映射」tab 维护（列表/新建/编辑 yaml/复制内置/删除自定义）。内置
  模板由 server 启动时从 parser 侧 yml 幂等种子化入库（`is_builtin=true` 不可删、
  可复制），导入 preview 的 `mapping_profile` 参数可传 mappingId 或内置名（兼容
  旧值）；parser 解析时的档案内容由 server 随请求以 `profile_yaml` 传递，优先级为
  请求内联 `profile_yaml` > 本地 `mappings/*.yml` > 按扩展名内置默认。本目录 yml
  仍作为种子与本地默认保留
- 内置模板（含 `memotrace.yml` 与 parser 侧 liuhen 模板）依据公开的导出格式约定
  编写，**真实导出样例尚待验证**（知识库需求文档 §12 遗留待办 1，M8 §0 维持此
  决策）：拿到真实样例后需回归校准候选列名/行正则/选择器与时间格式，直接编辑
  yml（或在「导入映射」界面编辑）即可，无需改代码
- 语音/视频消息一律跳过（不生成 `ChatMessage`），图片消息保留为
  `msg_type=image` 但 M3 不下载聊天记录内嵌的图片文件，`content` 为源文件中的
  原始占位描述文本
