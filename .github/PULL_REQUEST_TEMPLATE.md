## 变更说明

<!-- 这个 PR 做了什么？为什么需要这个变更？ -->

## 涉及模块

- [ ] kb-common
- [ ] kb-domain
- [ ] kb-infrastructure
- [ ] kb-app
- [ ] kb-api

## 变更类型

- [ ] feat 新功能
- [ ] fix 缺陷修复
- [ ] docs 文档
- [ ] refactor 重构（不改变行为）
- [ ] chore 构建/工具/依赖
- [ ] schema 变更（新增 Flyway 迁移）
- [ ] 契约变更（kb-rag-deploy 的 `docs/openapi/kb-server.yaml` 或 `docs/M*-CONTRACTS.md`）

## 自查清单

- [ ] `mvn -B -ntp verify` 本地通过
- [ ] 代码原创，未复制 LLMentor / know-engine 的任何代码片段
- [ ] 依赖方向未被打破（kb-app 未 import kb-infrastructure 具体类；kb-domain 不认识中间件 SDK）
- [ ] 新增类带 `@author`，注释与日志为英文，无 `log.warn`，无魔法值
- [ ] 涉及 API 变更时已同步 `kb-rag-deploy/docs/openapi/kb-server.yaml`
- [ ] 涉及 schema 变更时已新增 Flyway 脚本（编号顺延、不改已发布脚本），CHANGELOG 条目标 `[schema]`
- [ ] 新增第三方依赖已确认许可证兼容 Apache-2.0 并更新 `NOTICE`
- [ ] 未提交任何真实密钥/密码
- [ ] 已更新 `CHANGELOG.md`
- [ ] 单元测试已补充或更新（新增领域算法带精确断言；修 bug 带回归测试）

## 与契约的偏离

<!-- 若实现与 kb-rag-deploy/docs/M*-CONTRACTS.md 有出入，逐条说明偏离之处与理由；无偏离写「无」 -->

## 测试方式

<!-- 如何验证这个改动是有效的？附命令或截图 -->

## 关联 Issue

Closes #
