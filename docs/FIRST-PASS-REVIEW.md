# 首轮代码审查纪要（2026-11-26）

> 自动化 + 静态扫瞄 + 已有安全文档的对照，**非渗透测试**。结论供排优先级，不等同于「必须立即修复」。

## 已较好覆盖的领域

| 主题 | 依据 |
| --- | --- |
| 管理 actuator 暴露面 | [`ACTUATOR-SECURITY.md`](../kb-rag-deploy/docs/ACTUATOR-SECURITY.md) |
| 解析路径穿越 / XML | `kb-rag-parser/app/security.py` 单测 |
| SSE 断连与流式行界 | `OpenAiChatStreamSubscriberTest`、`SseChatDisconnectIntegrationTest` |
| 权限与租户隔离 | 大量 `*AccessTest`、`*ContractTest` |
| 配置漂移 | `validate_config.py`、双份需求文档一致性校验 |

## 建议持续关注（中等优先级）

1. **单实例调度假设**：后台任务默认同进程竞争；多副本需配合 `KB_CACHE_PROVIDER=redis` 与运维文档中的调度边界（见 `DURABLE-SCHEDULING-DECISION.md`）。扩容前需复核 `@Scheduled` 与锁实现。
2. **员工反馈自动建单**：`EmployeeFeedbackQualityAutomation` 对单库失败以 error 级别记录（含错误码与异常栈）并继续；运营侧需知部分 KB 可能未建单（已有单测约束扇出与吞错行为）。
3. **前端 Fast Refresh 警告**：`AuthContext` 等文件 export 非组件符号，`oxlint` 仅 warning；不影响生产构建，拆分文件可改善 DX。
4. **自测文档路径**：`docs/自测步骤.md` 已改为从仓库根目录出发的相对路径，并按 Sa-Token 与滑块验证码更新取 token 方式。

## 低优先级 / 观察项

- **API Key 与 Memory Key**：靠 Bearer 前缀区分；日志与审计需避免打印完整 Key（审查 spot check：Provider 与 filter 层）。
- **大文件解析**：parser 与 pipeline 超时配置分散在 `.env` 与 `KbProperties`；压测脚本在 `kb-rag-deploy/scripts/benchmark.sh`。

## 建议的下一轮（人工）

- 对 `kb-api` 开放路由做鉴权矩阵表（匿名 / 会话 / API Key / MCP）。
- 在 staging 跑 `backup.sh` + `restore.sh` 与文档交叉验证。
- 有 Neo4j / Qdrant 档位时跑一轮图检索与向量双写的故障注入。

## 本次已补的质量债

- `QualityAutomationConfigServiceTest`、`EmployeeFeedbackQualityAutomationTest`
- `QualityAutomationTab.test.tsx`
- [`CODEBASE-MAP.md`](CODEBASE-MAP.md)、[`TROUBLESHOOTING-DEV.md`](TROUBLESHOOTING-DEV.md)、根目录 `scripts/dev-gate.sh`