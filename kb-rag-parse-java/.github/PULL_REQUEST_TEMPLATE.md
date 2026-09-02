## 变更说明

<!-- 这个 PR 做了什么？为什么需要这个变更？ -->

## 变更类型

- [ ] feat 新功能
- [ ] fix 缺陷修复
- [ ] docs 文档
- [ ] refactor 重构（不改变行为）
- [ ] chore 构建/工具/依赖
- [ ] 契约变更（涉及 kb-rag-deploy 仓库 `docs/M*-CONTRACTS.md` 或 `docs/openapi/kb-parser.yaml`）

## 自查清单

- [ ] 代码原创，未复制 LLMentor / know-engine 的任何代码片段
- [ ] `mvn test` 全部通过
- [ ] 若改动了解析行为：`tools/crosscheck.py` 与 Python 实现 kb-rag-parser 对拍全项一致；
      有意的差异已写入 README「与契约的偏离说明」并交代对 kb-rag-server 的影响
- [ ] 未新增任何出站网络请求（特别是 `Jsoup.connect` 应保持零调用）；新增的 XML 解析路径已走
      加固方案，且已更新 `security/XmlHardening` 的审计结论（见 CONTRIBUTING.md）
- [ ] 新增第三方依赖已按其 POM 元数据核实许可证兼容性并更新 `NOTICE`；未引入 AGPL-3.0 或其他
      带网络服务触发条款的依赖
- [ ] 未提交任何真实密钥/密码
- [ ] 已更新 `CHANGELOG.md`
- [ ] 涉及契约变更时，已在下方说明偏离之处与原因，且两套实现已一起更新

## 测试方式

<!-- 如何验证这个改动是有效的？附命令或输出 -->

## 关联 Issue

Closes #
