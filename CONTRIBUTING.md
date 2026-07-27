# 贡献指南

感谢你对 kb-rag 感兴趣。本仓库（kb-rag-server）是系统的 Java 主服务：知识库与文档生命周期、
索引管线、检索融合、标注与评测、应用发布与对外 API，以及全部大模型调用。文档解析在
kb-rag-parser，管理台在 kb-rag-web，部署编排与跨仓文档在 kb-rag-deploy。

## 代码原创红线

本项目为 Apache-2.0 开源项目。开发过程中参考了非开源项目（LLMentor / know-engine）的
**设计思想**（表结构、类职责划分、集成模式），但**严禁复制其任何代码片段**。提交 PR 前
请自查这一点，Code Review 会按此红线一票否决。

## 分支模型与提交规范

- 分支：`main`（稳定）+ `feature/*` / `fix/*` / `docs/*` 分支开发，通过 PR 合入
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)：
  `feat|fix|docs|chore|refactor|test(scope): 简述`
- **接口先行**：涉及 API 变更的改动请先更新 `kb-rag-deploy/docs/openapi/kb-server.yaml`，
  再实现代码，保持契约与实现一致
- **schema 变更只走 Flyway**：禁止手工 DDL。新迁移编号顺延（当前到 `V11`），已发布的脚本
  永不修改；迁移向后兼容一个版本（先加列后删列、不重命名），CHANGELOG 对应条目标 `[schema]`

## 架构约束（改动前先读）

依赖方向是硬约束，PR 中出现反向依赖会被直接打回：

```
kb-api ──► kb-app ──► kb-domain ──► kb-common
   └─────► kb-infrastructure ──► kb-domain ──► kb-common
```

- **kb-app 只依赖端口接口**（`io.kbrag.domain.port`），从不 import kb-infrastructure 的具体类
- **kb-domain 不认识任何中间件 SDK**，领域服务是可单测的纯函数
- **kb-api 是唯一装配点**：`@SpringBootApplication(scanBasePackages = "io.kbrag")`
- 新增外部依赖能力时，先在 kb-domain 定义端口，再在 kb-infrastructure 实现，不要让
  Service 直接握 SDK
- 能力未配置时注入 `Unconfigured*` / `Disabled*` 实现，上游只写一个 `isConfigured()` 分支。
  不要在调用点补 null 检查——防御式编程全链路只做一处

## 代码规范

- Java 17；**注释与公共 API 文档一律用英文**（面向国际贡献者）
- 每个类的类级 Javadoc 必须带 `@author <你的邮箱>`
- 日志**只用 info 与 error，不用 warn**；日志内容为英文，error 日志用占位符输出错误码
- 用 lombok；集合判空用 `CollectionUtils`；JSON 走 `JsonUtil`；哈希走 `HashUtil`
- 无魔法值：常量进 `KbConstants` 或所属类的私有常量
- fast-fail 优先于防御式编程；充血模型优先，但与既有框架代码保持平衡
- 核心算法（切分、融合、命中判定、门禁裁决）必须附设计说明注释：为什么是这个口径，
  以及换个口径会坏在哪里

## 测试

- 全量单测不依赖任何外部中间件，可离线执行；请勿引入需要真实 MySQL / ES / Milvus 的测试
- 新增领域算法必须带**精确断言**的单测（手算期望值），不接受「跑通就行」的冒烟测试
- 修 bug 时补一条回归测试，注释写清这条测试在防什么

本机默认 `java` 不是 17 时需显式指定 `JAVA_HOME`：

```bash
export JAVA_HOME=/path/to/jdk17
mvn -B -ntp verify
```

CI 用 temurin 17 跑同一条 `mvn -B -ntp verify`。

## 提交 PR 前自查清单

- [ ] `mvn -B -ntp verify` 本地通过
- [ ] 代码原创，未复制 LLMentor / know-engine 的任何代码片段
- [ ] 依赖方向未被打破（kb-app 未 import kb-infrastructure 具体类）
- [ ] 新增类带 `@author`，注释为英文，无 `log.warn`，无魔法值
- [ ] 涉及 API 变更时已同步 `kb-rag-deploy/docs/openapi/kb-server.yaml`
- [ ] 涉及 schema 变更时已新增 Flyway 脚本，CHANGELOG 条目标 `[schema]`
- [ ] 新增第三方依赖已确认许可证兼容 Apache-2.0 分发并更新 `NOTICE`
- [ ] 未提交任何真实密钥 / 密码（密钥只从环境变量读取，不入代码也不入配置文件）
- [ ] 与契约（`kb-rag-deploy/docs/M*-CONTRACTS.md`）有偏离时，已在 PR 描述中说明偏离之处与原因
- [ ] `CHANGELOG.md` 已补充本次变更条目

## 报告 Bug / 提需求

请使用 `.github/ISSUE_TEMPLATE/` 下的模板。报 Bug 时请带上 `request_id`——它在入口过滤器生成、
写进日志 MDC 并透传到 parser，是串联整条链路最快的抓手。

安全漏洞请勿走公开 Issue，见 [SECURITY.md](SECURITY.md)。
