# 贡献指南

感谢你对 kb-rag 感兴趣。本仓库（kb-rag-parse-java）是 kb-rag 知识库项目的 **Java 文档解析微服务**，
是 [kb-rag-parser](../kb-rag-parser)（Python）的功能对等移植，只负责"文件解析"这一件事：把上传的
文档转成结构化的 `markdown` + 按页文本 + 图片二进制，把聊天记录导出转成结构化会话消息（详见
README.md 与 [kb-rag-deploy](https://github.com/liulangjietou/kb-rag-deploy) 仓库的
`docs/ARCHITECTURE.md` §4 / `docs/M1-CONTRACTS.md` / `docs/M3-CONTRACTS.md` / `docs/M8-CONTRACTS.md`
/ `docs/M12-CONTRACTS.md` / `docs/M14-CONTRACTS.md`）。业务后端与前端请分别参见 kb-rag-server /
kb-rag-web 仓库；部署编排、跨仓契约与总体文档在 kb-rag-deploy 仓库维护。

## 最重要的一条：行为基准是 Python 实现

本仓库存在的前提是两套实现给同一份输入**同一个答案**。任何改动都要回答一个问题：Python 实现
在这个输入上会怎么答？

- 改动涉及解析行为（不只是重构）时，跑一遍 `tools/crosscheck.py` 对拍，确认仍然全项一致。
  这是唯一能证明等价性的手段，单元测试只能证明各自符合自己的预期。
- 如果一处差异是有意的（例如库能力所限、或本实现更正确），把它写进 README 的
  「与契约的偏离说明」，说清楚**为什么**以及**kb-rag-server 会不会受影响**。不要留下无记录的差异。
- 如果发现的是 Python 实现的 bug，先在那边修，再同步过来——否则基准就漂了。

## 代码原创红线

本项目为 Apache-2.0 开源项目。开发过程中参考了非开源项目（LLMentor / know-engine）的
**设计思想**（模块职责划分、集成模式），但**严禁复制其任何代码片段**。提交 PR 前请自查这一点，
Code Review 会按此红线一票否决。

## 分支模型与提交规范

- 分支：`main`（稳定），功能开发请从 `main` 切出 `feat/*` 或 `docs/*` 等分支，通过 PR 合入
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)：
  `feat|fix|docs|chore|refactor|test(scope): 简述`
- 接口先行：涉及 `/api/v1/parse`、`/api/v1/parse/chat` 端点变更的改动，请先确认与 kb-rag-deploy
  仓库 `docs/openapi/kb-parser.yaml` 及相应 `docs/M*-CONTRACTS.md` 保持一致，必要时先在
  kb-rag-deploy 侧同步更新契约文档；两套实现要一起改，不能只改一边

## 本仓库的改动准则

- **新增文档解析格式**：实现一个 `parser/DocumentParser`，在 `parser/ParserRegistry` 注册一行——
  策略注册表模式，控制层无需改动
- **新增聊天记录来源/格式**：在 `src/main/resources/mappings/` 下新增一份映射档案 yml
  （列名候选 / `txt:` 行模板 / `html:` 选择器），无需改代码；若确需新适配器逻辑，放在 `chat/` 下
- **常量集中**：契约固定、不可配置的值放 `config/ParserConstants`，可调运行时配置放
  `config/ParserProperties` 并在 `application.yml` 里绑定同名环境变量。不要在业务代码里写魔法值，
  也不要把"谁都不该改的值"做成配置项——配置项本身就在暗示改它是被支持的
- **日志**：只用 info 与 error 两级，内容用英文，错误日志带 `errorCode=` 占位符输出
- **注释用途**：注释说明**为什么**，不复述代码在做什么。类注释交代这个类为何存在、边界在哪
- **安全约束是红线**，任何改动都不得削弱：
  - 禁止引入任何出站网络请求（HTTP 客户端、URL 拉取），杜绝 SSRF。特别注意 jsoup：
    `Jsoup.connect` 在本仓库应当永远为零调用，只用 `Jsoup.parse` 解析已在手的字节
  - XML 解析必须走 POI 已加固的路径；如果确实需要自己解析 XML，必须显式禁用 DTD 与外部实体，
    并更新 `security/XmlHardening` 的审计结论（现在那里写的是"本服务不手工解析任何 XML"，
    新增了就得改）
  - docx/xlsx 属于 zip 容器格式，新增相关解析路径前先确认已过 `security/ZipSafetyGuard` 预检
  - YAML 反序列化必须用 `SafeConstructor`；映射档案是随请求到达的数据
- **依赖许可**：新增/升级任何第三方依赖前，先确认许可证与 Apache-2.0 分发兼容，并同步更新
  `NOTICE`（逐项从该 artifact 自己的 POM 元数据核实，不要凭印象填）。特别地，**不要引入
  AGPL-3.0 或其他带网络服务触发条款的依赖**——绕开这一类义务正是本仓库存在的理由之一，
  详见 README「为什么有这个仓库」
- **OCR 相关改动**：tess4j 属于可选依赖（Maven profile `ocr`），保持默认不构建，避免拖慢无需
  OCR 兜底的部署镜像体积；引擎调用保持反射方式，使主源码在没有该 jar 时也能编译运行
- 类注释末尾统一标注 `@author owlzhangfq@gmail.com`（沿用既有约定）

## 本地开发与测试

```bash
mvn test
```

```bash
mvn -DskipTests package && java -jar target/kb-rag-parse-java-0.1.0-SNAPSHOT.jar
```

两实现对拍（改动了解析行为就该跑）：

```bash
cd kb-rag-parser && .venv/bin/uvicorn app.main:app --port 20012
```

```bash
cd kb-rag-parse-java && java -jar target/kb-rag-parse-java-0.1.0-SNAPSHOT.jar --server.port=20011
```

```bash
kb-rag-parser/.venv/bin/python kb-rag-parse-java/tools/crosscheck.py
```

## 测试准则

- 样例文件用代码生成，**不提交二进制 fixture**。一个签进仓库的二进制文件是不透明的：审阅者
  看不出它里面到底有什么，解析行为变化时也无从判断是 fixture 错了还是预期错了
- 每个安全约束都要有负例（zip-slip、zip 炸弹、坏 YAML、选择器零匹配……），正例证明能用，
  负例才证明防线在
- 断言写清楚**为什么**这个值是对的。`assertEquals(1, images.size())` 不如带一句
  "同一张 logo 画在 4 页上仍然只是一张图"

## PR 检查清单

- [ ] `mvn test` 全绿
- [ ] 改动了解析行为的话，`tools/crosscheck.py` 全项一致
- [ ] 新增/变更的行为在 README 有对应描述；有意的差异写进「与契约的偏离说明」
- [ ] CHANGELOG.md 记了这次改动
- [ ] 新增依赖的话，NOTICE 已按其 POM 元数据核实更新
- [ ] 端点契约变更已与 kb-rag-deploy 的 openapi/契约文档对齐，且两套实现一起改
