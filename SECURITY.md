# 安全策略（Security Policy）

kb-rag-web 是 kb-rag 知识库 / RAG 系统的前端管理台，是自托管（self-hosted）部署的一部分，
会持有管理员登录凭据（JWT）与 API Key 明文（仅创建时短暂展示）。安全问题请负责任地披露
（responsible disclosure）。

## 支持的版本

一期（M1-M6）与二期（M7-M9）仅维护 `main` 分支的最新版本；尚无长期支持（LTS）分支。安全
修复以最新 release 为准，不对历史 tag 做回溯打补丁。

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

1. 漏洞类型与影响范围（例如：XSS、越权访问、Token/Key 泄露、CSRF、开放重定向等）
2. 复现步骤（PoC 越具体、响应越快）
3. 影响的组件与版本（kb-rag-web / kb-rag-server / kb-rag-parser / kb-rag-deploy）
4. 建议的修复方向（可选）

## 响应流程

- 我们会在 **3 个工作日**内确认收到报告
- 在 **14 天**内给出初步评估（是否成立、严重级别、预计修复时间）
- 修复发布后，会在 CHANGELOG.md 与（如适用）GitHub Security Advisory 中致谢报告者
  （除非报告者要求匿名）

## 本项目的安全默认值（供审计参考）

- 全仓不使用 `dangerouslySetInnerHTML`：解析预览、分片内容等一律走 preformatted 纯文本
  渲染，避免后端返回内容中的恶意 HTML/脚本被执行
- JWT 存储于浏览器本地（`src/api/authStorage.ts`），401 响应统一清除本地 token 并跳转登录，
  避免过期/失效凭据被继续使用
- API Key 明文仅在创建/轮换的那次响应中短暂展示，页面不做二次持久化；后续只按脱敏形式展示
- 图片贴图/选图（问答调试、API 调试）仅接受 base64 编码上传，不接受外部图片 URL，避免
  前端成为 SSRF 请求的发起入口（权威张数/大小校验在后端二次执行）
- 前端不引入 mock 数据、不硬编码任何密钥；开发态 `/api`、`/actuator` 仅代理到本机
  `127.0.0.1:20000`（`vite.config.ts`），生产环境由部署方的反向代理（如 Nginx）转发，
  本仓库不承担生产环境的 TLS/鉴权边界配置
- 依赖第三方开源库（React/Ant Design/axios/react-router 等）均为 MIT/Apache-2.0 许可，
  详见 `NOTICE`
