# 安全策略（Security Policy）

kb-rag 是一个自托管（self-hosted）的开源知识库 / RAG 系统，涉及文档内容、检索日志与管理台
凭据，安全问题请负责任地披露（responsible disclosure）。

## 支持的版本

一期（M1-M6）仅维护 `main` 分支的最新版本；尚无长期支持（LTS）分支。安全修复以最新
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

1. 漏洞类型与影响范围（例如：越权访问、SQL 注入、路径穿越、SSRF、密钥泄露等）
2. 复现步骤（PoC 越具体、响应越快）
3. 影响的组件与版本（kb-rag-server / kb-rag-parser / kb-rag-web / kb-rag-deploy）
4. 建议的修复方向（可选）

## 响应流程

- 我们会在 **3 个工作日**内确认收到报告
- 在 **14 天**内给出初步评估（是否成立、严重级别、预计修复时间）
- 修复发布后，会在 CHANGELOG.md 与（如适用）GitHub Security Advisory 中致谢报告者
  （除非报告者要求匿名）

## 本项目的安全默认值（供审计参考）

- 密钥/密码不进代码与配置文件仓库：全部通过环境变量 + `.env.example` 模板注入
  （`.env` 已在 `.gitignore` 中忽略）
- 管理台登录密码使用 BCrypt 哈希存储，禁止明文入库
- 首次启动自动生成管理员账号 + 随机密码，强制首登改密（must_change_password）
- 登录失败计数与锁定（5 次失败锁 15 分钟），记入 t_kb_login_audit 供审计
- docker-compose 各服务镜像 tag 全部固定版本号，禁止使用 `latest`
- Elasticsearch/MinIO/Milvus 均作为独立容器通过标准 API 调用，不修改其源码、不构成衍生作品
  （详见 NOTICE）
- kb-rag-parser 解析链路：defusedxml 处理 XML、禁止任何出站网络请求、zip 解包路径校验、
  文件大小与总量上限（见 M1-CONTRACTS.md §6）
