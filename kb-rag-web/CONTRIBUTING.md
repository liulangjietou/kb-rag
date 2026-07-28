# 贡献指南

感谢你对 kb-rag-web 感兴趣。本仓库承载 kb-rag 知识库 RAG 系统的前端管理台；后端接口、
部署编排与总体文档请分别参见 kb-rag-server / kb-rag-parser / kb-rag-deploy 仓库
（接口契约见 `kb-rag-deploy/docs/M1~M13-CONTRACTS.md`，前端架构见
`kb-rag-deploy/docs/ARCHITECTURE.md` 第 5 节）。

## 代码原创红线

本项目为 Apache-2.0 开源项目。开发过程中参考了非开源项目（LLMentor / know-engine）的
**设计思想**（页面信息架构、组件职责划分、与后端的交互模式），但**严禁复制其任何代码片段**。
提交 PR 前请自查这一点，Code Review 会按此红线一票否决。

## 分支模型与提交规范

- 分支：`main`（稳定）/ `dev`（集成）+ `feature/*` 分支开发，PR 合入 `dev`，发布时合入 `main`
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)：
  `feat|fix|docs|chore|refactor|test(scope): 简述`
- 接口先行：涉及后端接口变更的改动请先确认 `kb-rag-deploy/docs/M*-CONTRACTS.md` 与
  `docs/openapi/kb-server.yaml` 中的契约，前端实现与之保持一致；发现契约与后端实现不一致时，
  请在 PR 描述中说明并同步告知 kb-rag-deploy/kb-rag-server 维护者

## 本仓库的改动准则

- 状态管理：不引入 Redux/Zustand 等三方状态库，登录态与全局模型状态统一走 React Context
  （`src/auth/AuthContext.tsx`、`src/context/ModelStatusContext.tsx`）
- 与后端交互：常规管理操作走 `src/api/request.ts` 的统一 axios 实例（Bearer 注入 + 401
  拦截 + 信封拆包）；SSE 流式与 API Key 直连场景刻意绕开该拦截器，复用
  `src/api/chatStream.ts`/`src/api/publicApi.ts`，不要为这两类场景引入第三套请求封装
- 枚举展示：新增/变更后端枚举时，Tag 颜色与中文标签统一维护在 `src/utils/statusMeta.ts`，
  不在组件内散落硬编码判断
- `kb_refs`（多知识库路由）相关读取：统一走 `src/utils/kbRefs.ts` 的 `resolveKbRefs()`/
  `kbNameOf()`，不要在新调用点重新解析 `config.kb_refs`/`config.kb_id`
- 安全：任何富文本/解析预览类内容一律走 preformatted 纯文本渲染，禁止引入
  `dangerouslySetInnerHTML`
- 不引入 mock 数据：新页面/新功能应直连 `kb-rag-server`，通过 `vite.config.ts` 的 dev proxy
  转发，不要在前端伪造响应数据
- 不引入新的图表/可视化重依赖前请先确认是否可用已有的自实现方案（例如
  `GraphVisualization.tsx` 的 SVG 力导向图），CSP 要求页面自包含、禁止 CDN 加载
- 提交前至少跑一次：

  ```bash
  npm run lint     # oxlint 静态检查
  npm run build    # tsc -b 类型检查 + 生产构建
  ```

## 提交 PR 前自查清单

- [ ] `npm run lint` 通过
- [ ] `npm run build` 通过（tsc 类型检查 + 生产构建）
- [ ] 未提交任何真实密钥/凭据
- [ ] 涉及路由/菜单变更时已同步更新 `README.md` 的路由表
- [ ] 涉及后端契约的变更已在 PR 描述中说明依据的 `M*-CONTRACTS.md` 版本与偏离之处（如有）
- [ ] `CHANGELOG.md` 已补充本次变更条目

## 报告 Bug / 提需求

请使用 `.github/ISSUE_TEMPLATE/` 下的模板；安全漏洞请勿走公开 Issue，见 `SECURITY.md`。
