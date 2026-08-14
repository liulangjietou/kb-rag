# 贡献指南

感谢你参与 kb-rag。当前仓库是包含 Java 主服务、Python 解析服务、React 管理台和部署契约的
monorepo；各子目录的 `CONTRIBUTING.md` 继续描述本模块特有约束，本文件说明跨模块共同约束。

## 开发原则

- 从最新 `main` 创建独立分支，不直接在 `main` 开发。
- 一次提交只解决一个边界清晰的问题；跨模块变更必须说明调用方、下游和兼容性影响。
- API 变更先更新 OpenAPI 与里程碑契约，配置变更同步更新 `.env.example` 和部署文档。
- 提交信息遵循 Conventional Commits，禁止在提交和 PR 中添加 AI 署名。
- 严禁提交真实密钥、公司信息、开发机绝对路径或仅对个人环境成立的配置。

## 本地质量门禁

根目录 `.github/workflows/ci.yml` 是 GitHub Actions 唯一入口，本地提交前应执行对应模块的同一组命令：

```bash
(cd kb-rag-server && mvn -B -ntp verify -DexcludedGroups=browser)
(cd kb-rag-parser && pytest -q)
(cd kb-rag-web && npm test && npm run lint && npm run build)
(cd kb-rag-deploy && python3 -m unittest discover -s tests -p 'test_*.py')
(cd kb-rag-deploy && python3 scripts/validate_config.py)
```

`kb-infrastructure` 中标记为 `browser` 的用例会启动真实 Chromium。准备好 Playwright Chromium 后，
使用 `mvn -B -ntp -pl kb-infrastructure -am test -Dgroups=browser` 单独执行，避免环境依赖让基础门禁失去确定性。

涉及部署契约时，还需执行 compose、OpenAPI 与 Shell 语法校验，完整命令见
[`kb-rag-deploy/CONTRIBUTING.md`](kb-rag-deploy/CONTRIBUTING.md)。

## 文档同步

- 根 README 描述当前稳定能力和快速启动方式。
- `kb-rag-deploy/docs/ARCHITECTURE.md` 描述实现架构，里程碑契约描述具体行为边界。
- `docs/知识库需求文档.md` 与 `kb-rag-deploy/docs/知识库需求文档.md` 必须逐字一致；配置校验会阻止两份副本漂移。
- 行为、配置、依赖或运维方式发生变化时，在同一提交中更新对应 README、契约和 CHANGELOG。
