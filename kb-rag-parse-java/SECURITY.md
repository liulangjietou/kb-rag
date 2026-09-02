# 安全策略（Security Policy）

kb-rag-parse-java 是 kb-rag（一个自托管/self-hosted 的开源知识库 / RAG 系统）的 Java 文档解析
微服务，处理用户上传的文档与聊天记录导出文件，安全问题请负责任地披露（responsible disclosure）。

## 支持的版本

一期仅维护 `main` 分支的最新版本；尚无长期支持（LTS）分支。安全修复以最新 release 为准，
不对历史 tag 做回溯打补丁。

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

1. 漏洞类型与影响范围（例如：路径穿越、zip-slip/zip 炸弹、XXE、SSRF、YAML 反序列化、
   拒绝服务等）
2. 复现步骤（PoC 越具体、响应越快，可附带触发问题的最小样例文件）
3. 影响的组件与版本（本仓库 kb-rag-parse-java，或与 kb-rag-server / kb-rag-web / kb-rag-deploy
   联动时请一并说明；若同一问题在 Python 实现 kb-rag-parser 上也成立，请注明）
4. 建议的修复方向（可选）

## 响应流程

- 我们会在 **3 个工作日**内确认收到报告
- 在 **14 天**内给出初步评估（是否成立、严重级别、预计修复时间）
- 修复发布后，会在 CHANGELOG.md 与（如适用）GitHub Security Advisory 中致谢报告者
  （除非报告者要求匿名）

## 本项目的安全默认值（供审计参考）

- **禁止出站网络请求**：全程未实现任何 HTTP 客户端 / URL 拉取逻辑，杜绝 SSRF。jsoup 只用于
  解析已在手的字节，`Jsoup.connect` 全仓零调用，解析基准 URI 为空；聊天记录 HTML 适配器
  （`chat/HtmlChatAdapter`）对 `<img src>` 只判断"是否存在"，从不下载，且解析前剥离
  `<script>`/`<style>` 内容
- **XML 解析防 XXE**：POI 通过 `XMLHelper` 统一构造解析器，已设 `disallow-doctype-decl`、
  禁外部通用/参数实体、禁外部 DTD、开 `FEATURE_SECURE_PROCESSING`——DTD 声明不了，外部实体
  自然无从声明。本服务**不手工解析任何 XML**（docx 关系查找走 POI 的 `PackagePart` API），
  因此没有第二个解析器需要维护加固。完整审计结论与理由见 `security/XmlHardening` 的类注释
- **zip 安全预检（防 zip-slip / zip 炸弹）**：docx/xlsx 本质是 zip 包，解析前先经
  `security/ZipSafetyGuard` 校验：条目路径不得越出（zip-slip）、解压总大小上限 500MB、条目数
  上限 2000，超限 fast-fail 返回 `PARSE_FAILED`。该检查读的是压缩包**自己声明**的元数据，
  因此另设第二道防线：POI 的解压比阈值（100x）在实际读取时监视条目真实膨胀量，拦住在中央
  目录里谎报大小的包
- **YAML 反序列化**：映射档案（`profile_yaml` 表单字段）一律走 SnakeYAML 的 `SafeConstructor`，
  等价于 Python 的 `yaml.safe_load`。档案是随请求到达的数据，能实例化数据中所指定任意类的
  构造器是 RCE 原语，不是便利功能
- **上传文件大小上限**：100MB，在进入解析前校验，超限 fast-fail；容器层另设 110MB 兜底，
  避免超大 body 在校验能跑之前就被完整缓冲进内存
- **解析超时熔断**：300s，解析在专用线程池执行、由 `Future.get(timeout)` 约束；超时后中断该
  worker 并返回 `PARSE_FAILED`，不会拖垮整个进程
- **解析并发上限**：`PARSER_MAX_WORKERS`（默认 4）。解析是 CPU 密集的，这个池而不是容器的
  工作线程数才是本服务真实的并发天花板——否则一台核数远少于容器线程数的机器会被并发解析拖垮
- **图片资源上限**：单文档图片数上限（默认 100）、单图字节上限（默认 10MB），超限跳过该图并
  记录 warning，不失败整篇文档，防止恶意构造文件耗尽内存
- **OCR 依赖门禁**：`OCR_ENGINE=tesseract` 但未以 `mvn -Pocr` 构建时，应用启动即 fast-fail，
  不会拖到首次遇到扫描页才报错；OCR 推理全程本地执行，不发起任何网络请求
- **依赖许可与供应链**：不引入 AGPL-3.0 或其他带网络服务触发条款的依赖；每项依赖的许可
  从其自身 POM 元数据逐项核实，记录在 `NOTICE`
- 本服务不持有任何用户凭据、不做鉴权（鉴权由 kb-rag-server 侧网关/接口层负责），部署时不应
  直接暴露给公网，应仅在内网/服务间网络中被 kb-rag-server 调用
