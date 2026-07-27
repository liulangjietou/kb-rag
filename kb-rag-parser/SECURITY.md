# 安全策略（Security Policy）

kb-rag-parser 是 kb-rag（一个自托管/self-hosted 的开源知识库 / RAG 系统）的文档解析微服务，
处理用户上传的文档与聊天记录导出文件，安全问题请负责任地披露（responsible disclosure）。

## 支持的版本

一期（M1-M8）仅维护 `main` 分支的最新版本；尚无长期支持（LTS）分支。安全修复以最新
release 为准，不对历史 tag 做回溯打补丁。

| 版本 | 是否接收安全修复 |
| --- | --- |
| main / 最新 release | 是 |
| 历史 tag | 否 |

## 报告漏洞

**请不要通过公开 Issue 报告安全漏洞。**

请通过以下方式之一私下联系维护者：

- 邮件：在仓库 GitHub 主页的维护者联系方式中获取（避免在本文件中硬编码个人邮箱被爬虫抓取）
- GitHub：使用仓库的 [Private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability)
  功能提交私密报告

请在报告中包含：

1. 漏洞类型与影响范围（例如：路径穿越、zip-slip/zip 炸弹、XXE、SSRF、拒绝服务等）
2. 复现步骤（PoC 越具体、响应越快，可附带触发问题的最小样例文件）
3. 影响的组件与版本（本仓库 kb-rag-parser，或与 kb-rag-server / kb-rag-web / kb-rag-deploy
   联动时请一并说明）
4. 建议的修复方向（可选）

## 响应流程

- 我们会在 **3 个工作日**内确认收到报告
- 在 **14 天**内给出初步评估（是否成立、严重级别、预计修复时间）
- 修复发布后，会在 CHANGELOG.md 与（如适用）GitHub Security Advisory 中致谢报告者
  （除非报告者要求匿名）

## 本项目的安全默认值（供审计参考）

- **禁止出站网络请求**：全程未实现任何 HTTP 客户端 / URL 拉取逻辑，杜绝 SSRF；
  聊天记录 HTML 适配器（`app/chat/html_adapter.py`）对 `<img src>` 只判断"是否存在"，
  从不下载，且解析前剥离 `<script>`/`<style>` 内容
- **XML/HTML 解析防 XXE**：应用启动时调用 `defusedxml.defuse_stdlib()` 全局加固标准库
  XML；另外审计确认 docx/xlsx 实际使用的 lxml 解析器均已配置 `resolve_entities=False`
  （python-docx 与 openpyxl 在其安装版本中均自带此项默认防护），详见 `app/security.py`
  模块 docstring
- **zip 安全预检（防 zip-slip / zip 炸弹）**：docx/xlsx 本质是 zip 包，解析前先经
  `ensure_zip_is_safe` 校验：条目路径不得越出（zip-slip）、解压总大小上限 500MB、条目数
  上限 2000，超限 fast-fail 返回 `PARSE_FAILED`
- **上传文件大小上限**：100MB，在进入解析前校验，超限 fast-fail
- **解析超时熔断**：300s，通过 `asyncio.wait_for` 包裹在线程池中执行的解析调用；超时同样
  返回 `PARSE_FAILED`，不会拖垮整个进程
- **图片资源上限**：单文档图片数上限（默认 100）、单图字节上限（默认 10MB），超限跳过该图
  并记录 warning，不失败整篇文档，防止恶意构造文件耗尽内存
- **OCR 依赖门禁**：`OCR_ENGINE=paddle` 但未 `pip install -r requirements-ocr.txt` 时，
  应用启动即 fast-fail（见 `app/ocr/engine.py`），不会拖到首次遇到扫描页才报错；PaddleOCR
  推理全程本地执行，不发起任何网络请求（模型文件的首次下载见 README.md「许可注意」相邻的
  运维说明，与运行时解析请求无关）
- 本服务不持有任何用户凭据、不做鉴权（鉴权由 kb-rag-server 侧网关/接口层负责），部署时不应
  直接暴露给公网，应仅在内网/服务间网络中被 kb-rag-server 调用
