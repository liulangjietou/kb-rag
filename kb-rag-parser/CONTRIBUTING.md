# 贡献指南

感谢你对 kb-rag 感兴趣。本仓库（kb-rag-parser）是 kb-rag 知识库项目的 Python 文档解析微服务，
只负责"文件解析"这一件事：把上传的文档转成结构化的 `markdown` + 按页文本 + 图片二进制，把聊天
记录导出转成结构化会话消息（详见 README.md 与 [kb-rag-deploy](https://github.com/liulangjietou/kb-rag-deploy)
仓库的 `docs/ARCHITECTURE.md` §4 / `docs/M1-CONTRACTS.md` / `docs/M3-CONTRACTS.md` / `docs/M8-CONTRACTS.md`）。
业务后端与前端请分别参见 kb-rag-server / kb-rag-web 仓库；部署编排、跨仓契约与总体文档在
kb-rag-deploy 仓库维护。

## 代码原创红线

本项目为 Apache-2.0 开源项目。开发过程中参考了非开源项目（LLMentor / know-engine）的
**设计思想**（模块职责划分、集成模式），但**严禁复制其任何代码片段**。提交 PR 前请自查这一点，
Code Review 会按此红线一票否决。

## 分支模型与提交规范

- 分支：`main`（稳定），功能开发请从 `main` 切出 `feature/*` 或 `docs/*` 等分支，通过 PR 合入
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)：
  `feat|fix|docs|chore|refactor|test(scope): 简述`
- 接口先行：涉及 `/api/v1/parse`、`/api/v1/parse/chat` 端点变更的改动，请先确认与
  kb-rag-deploy 仓库 `docs/openapi/kb-parser.yaml` 及相应 `docs/M*-CONTRACTS.md` 保持一致，
  必要时先在 kb-rag-deploy 侧同步更新契约文档

## 本仓库（kb-rag-parser）的改动准则

- 新增文档解析格式：只需在 `app/parsers/` 下新增一个 `BaseParser` 实现，并在
  `app/parsers/registry.py` 注册一行——策略注册表模式，`app/main.py` 主流程无需改动
- 新增聊天记录来源/格式：只需在 `app/mappings/` 下新增一份映射档案 yml（列名候选 / `txt:`
  行模板 / `html:` 选择器），无需改代码；若确需新适配器逻辑，放在 `app/chat/` 下
- 安全约束是本服务的红线，任何改动都不得削弱：
  - 禁止引入任何出站网络请求（HTTP 客户端、URL 拉取），杜绝 SSRF
  - XML/HTML 解析必须走 `defusedxml`/标准库 `html.parser` 等已加固路径，不得直接使用未加固的
    `xml.etree`/`lxml` 默认解析器
  - docx/xlsx 属于 zip 容器格式，新增相关解析路径前先确认已过 `app/security.py` 的
    zip-slip / 解压总大小 / 条目数上限预检
  - 全部常量集中在 `app/config.py`，新增限制值请走这里，不要在业务代码里写魔法值
- 新增/升级任何第三方依赖前，先确认许可证与 Apache-2.0 分发兼容，并同步更新 `NOTICE`
  （详见 `NOTICE` 文件与 README.md「许可注意」小节，PyMuPDF 的 AGPL-3.0 双重许可现状是本仓库
  已知的、尚待项目 Owner 决策的合规事项，不要在无 Owner 决策的情况下自行"解决"或淡化它）
- OCR 相关改动：`OCR_ENGINE=paddle` 依赖的 `paddlepaddle`/`paddleocr` 属于可选依赖
  （`requirements-ocr.txt`），保持默认不随 `requirements.txt`/Dockerfile 基础镜像安装，避免
  拖慢无需 OCR 兜底的部署镜像体积
- 模块 docstring 末尾统一标注 `Author: owlzhangfq@gmail.com`（沿用既有约定）

## 本地开发与测试

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
# 涉及 OCR_ENGINE=paddle 相关改动时再装：
.venv/bin/pip install -r requirements-ocr.txt

.venv/bin/pytest -q
```

未安装 `requirements-ocr.txt` 时，PaddleOCR 真实推理相关用例会被 `skipif` 自动跳过，不影响
其余用例通过；CI（`.github/workflows/ci.yml`）同样不安装 OCR 依赖。

## 提交 PR 前自查清单

- [ ] `pytest -q` 全部通过（或按依赖是否安装被合理 skip）
- [ ] 代码原创，未复制 LLMentor / know-engine 的任何代码片段
- [ ] 未新增任何出站网络请求；新增的 XML/HTML 解析路径已走加固方案
- [ ] 新增第三方依赖已确认许可证兼容性并更新 `NOTICE`
- [ ] 未提交任何真实密钥/密码
- [ ] 已更新 `CHANGELOG.md`
- [ ] 涉及契约（kb-rag-deploy 仓库 `docs/M*-CONTRACTS.md` / `docs/openapi/kb-parser.yaml`）的
      变更已在 PR 描述中说明偏离之处与原因

## 报告 Bug / 提需求

请使用 `.github/ISSUE_TEMPLATE/` 下的模板；安全漏洞请勿走公开 Issue，见 `SECURITY.md`。
