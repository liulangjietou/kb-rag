# 贡献指南

感谢你对 kb-rag 感兴趣。本仓库（kb-rag-deploy）承载四仓库共用的部署编排、环境变量模板、
接口契约（OpenAPI）与总体文档；业务代码请分别参见 kb-rag-server / kb-rag-parser /
kb-rag-web 仓库。

## 代码原创红线

本项目为 Apache-2.0 开源项目。开发过程中参考了非开源项目（LLMentor / know-engine）的
**设计思想**（表结构、类职责划分、集成模式），但**严禁复制其任何代码片段**。提交 PR 前
请自查这一点，Code Review 会按此红线一票否决。

## 分支模型与提交规范

- 分支：`main`（稳定）/ `dev`（集成）+ `feature/*` 分支开发，PR 合入 `dev`，发布时合入 `main`
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)：
  `feat|fix|docs|chore|refactor|test(scope): 简述`
- 接口先行：涉及 API 变更的改动请先更新 `docs/openapi/*.yaml`，再实现代码，保持契约与实现一致

## 本仓库（kb-rag-deploy）的改动准则

- `docker-compose*.yml`：镜像 tag 必须固定版本号（禁止 `latest`），新增服务需带
  `healthcheck` + `restart: unless-stopped` + 命名 volume
- `.env.example`：新增环境变量必须同步补充中文注释，说明是否可空、默认值与影响范围
- 新增/升级任何第三方依赖前，先确认许可证与 Apache-2.0 分发兼容，并同步更新 `NOTICE`
  （参见需求文档 §12.1）
- `docs/openapi/*.yaml`：修改端点或 DTO 后，请用以下命令自查可解析：
  ```bash
  python3 -c "import yaml, sys; yaml.safe_load(open(sys.argv[1]))" docs/openapi/kb-server.yaml
  python3 -c "import yaml, sys; yaml.safe_load(open(sys.argv[1]))" docs/openapi/kb-parser.yaml
  ```
- 提交前请至少跑一次配置语法校验：
  ```bash
  python3 -m unittest discover -s tests -p 'test_*.py'
  python3 scripts/validate_config.py
  docker compose -f docker-compose.lite.yml config -q
  docker compose -f docker-compose.yml config -q
  ```

## 提交 PR 前自查清单

- [ ] `scripts/validate_config.py` 与配置校验单测通过
- [ ] `docker compose config -q` 通过（lite 与 full 两个文件）
- [ ] OpenAPI 文件可被 `yaml.safe_load` 解析
- [ ] 未提交任何真实密钥/密码（检查 `git diff` 是否误提交 `.env`）
- [ ] 涉及契约（`docs/M1-CONTRACTS.md`）的变更已在 PR 描述中说明偏离之处与原因
- [ ] CHANGELOG.md 已补充本次变更条目

## 报告 Bug / 提需求

请使用 `.github/ISSUE_TEMPLATE/` 下的模板；安全漏洞请勿走公开 Issue，见 `SECURITY.md`。
