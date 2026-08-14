# kb-rag-web

知识库 RAG 系统的前端管理台。技术栈：Vite 8 + React 18 + TypeScript 6 + Ant Design 5 + react-router-dom 6 + axios；静态检查用 oxlint；不引入 Redux/Zustand 等状态管理库（登录态与模型状态均用 React Context）。

对应 `kb-rag-deploy/docs/ARCHITECTURE.md` 第 5 节（web 架构）与 `docs/M1~M22-CONTRACTS.md` 各里程碑契约。

## 快速开始

依赖后端 `kb-rag-server`（默认监听 `20000`）先启动，本项目开发模式下会将 `/api` 与 `/actuator` 请求代理到 `http://127.0.0.1:20000`（见 `vite.config.ts`）。

```bash
npm install
npm run dev      # 启动开发服务器，默认端口 20002
```

首次启动后端时会在日志中打印随机生成的 `admin` 初始密码，使用该账号登录后系统会强制跳转到修改密码页面（`must_change_password=true`）。

## 常用脚本

| 命令 | 说明 |
|---|---|
| `npm run dev` | 启动开发服务器（含 /api、/actuator 代理） |
| `npm run build` | TypeScript 类型检查（`tsc -b`）+ 生产构建 |
| `npm run preview` | 预览生产构建产物 |
| `npm run lint` | oxlint 静态检查 |
| `npm test` | Vitest 单元测试（单次运行） |
| `npm run test:watch` | Vitest 监听模式 |
| `npx tsc --noEmit` | 仅做类型检查，不产出文件 |

## 页面与路由

守卫嵌套：`RequireAuth → RequirePasswordChanged → AuthenticatedShell(ModelStatusProvider + MainLayout)`。左侧菜单共 6 项，其中「知识库」「应用中心」各带一个不出现在菜单里的详情动态路由，认证壳内共 8 条页面路由；登录与首登改密不挂载主布局，单独 2 条。

| 路由 | 菜单项 | 页面 | 说明 |
|---|---|---|---|
| `/login` | - | 登录页 | 登录成功后若 `must_change_password=true` 强制跳转改密页 |
| `/change-password` | - | 首登改密页 | 修改成功后放行到知识库列表 |
| `/kb` | 知识库 | 知识库列表 | 卡片形式展示，支持新建（弹窗）、删除（二次确认）、空态引导 |
| `/kb/:kbId` | 知识库 | 知识库详情 | 文档拖拽上传、文档列表（状态标签 + 3s 轮询）、失败原因提示、审核与有效期操作（M11）、索引配置编辑与重建、分片抽屉、版本抽屉（pinned 标记）、聊天记录导入向导；知识图谱 / 反馈管理 / 检索洞察 / 回收站 / 网页导入等 Tab |
| `/search` | 检索调试 | 检索调试 | 知识库选择 + 参数面板（改写/召回/融合/重排/过滤/返回分组折叠），结果卡片展示各路原始分/归一化分/`fused_score`/`rerank_score`，`degraded` 非空时顶部告警，可收进评测集 |
| `/chat` | 问答调试 | 问答调试 | 走 `/apps/{id}/chat-preview` SSE 流式问答（JWT 鉴权） |
| `/apps` | 应用中心 | 应用列表 | 应用的新建与列表 |
| `/apps/:appId` | 应用中心 | 应用详情 | 配置 / 版本与门禁对比 / API 调试三个 Tab |
| `/mcp` | MCP 调试 | MCP 双协议调试 | 现代 `server/discover` / 旧版 initialize、工具目录与调用、curl 预览 |
| `/eval` | 评测中心 | 评测中心 | 数据集 / case 标注 / 证据复核 / 运行报告四个 Tab |
| `/settings` | 系统设置 | 系统设置 | 模型状态（embedding/rerank/chat/vision 四卡）/ ik 词典 / 告警 / API Key / 审计日志 / 导入映射六个 Tab |

## 目录结构

```
src/
  api/            # 与后端契约对齐的类型定义 + axios 封装 + 各资源 API 函数
  auth/           # 登录态 Context 与路由守卫（RequireAuth / RequirePasswordChanged）
  context/        # ModelStatusContext：零 Key 模式全局状态
  layout/         # 左侧菜单 + 顶栏的主布局（MainLayout）
  pages/          # 各页面组件，按业务域分子目录（kb / search / chat / apps / eval / settings），组件下沉到各自 components/ 子目录
  router/         # 路由表（AppRouter）
  utils/          # 归一化、格式化、状态展示元信息等纯函数工具
```

## 与后端的三条交互通道

1. **`src/api/request.ts`**（主通道）：axios 实例，`baseURL=/api/v1`，请求拦截器统一注入 `Authorization: Bearer <token>`；响应拦截器与 `unwrap()` 统一拆包后端 `{code, message, data, request_id}` 信封 —— 业务错误码非 `OK`、HTTP 层错误、401（自动清 token 并跳登录）均在此集中处理并通过 `antd.message` 提示（附带 `request_id` 便于排查）。后台内部管理页面（知识库、检索调试、应用中心、评测中心、系统设置）均走此通道。
2. **`src/api/chatStream.ts`**：SSE 流式问答的共享驱动，用原生 `fetch` 而非 `request.ts` 的 axios 实例（刻意绕过其 401 拦截器 —— 一个过期/错误的 API Key 应该在调试 UI 内联展示，而不是把管理员自己的登录态清掉）。同时服务「问答调试」页（JWT 鉴权，`/apps/{id}/chat-preview`）与应用详情「API 调试」Tab（API Key 鉴权，对外 `/api/v1/knowledge/chat`）。
3. **`src/api/publicApi.ts`**：API Key 直连对外端点（`/api/v1/knowledge/search`、`/api/v1/knowledge/chat`），同样绕开 `request.ts`；统一返回 `{ok, data}` / `{ok, error}` 结构，429 限流响应读取 `Retry-After` 头透出给调用方。
4. **`src/api/mcp.ts`**：MCP 调试专用 fetch 客户端；现代版逐请求生成 `_meta` 与动态镜像头，旧版保留 initialize 形态，原样返回 HTTP / JSON-RPC / 工具业务三个结果平面。

## 关键约定

- **`utils/kbRefs.ts`**：`AppVersionConfig.kb_refs` 读侧归一化的**唯一防御点**（兼容旧快照的单 `kb_id` 字段，视为 `[{kb_id, weight:1}]`）。任何需要拿到版本路由知识库列表的调用点都必须走 `resolveKbRefs()`，不得各自解析 `config.kb_refs`/`config.kb_id`（fast-fail 防御式编程只保留这一处，不重复散落）。
- **`utils/statusMeta.ts`**：20+ 后端枚举（`ProcessStatus`/`ScoreType`/`RunStatus`/`GraphTaskStatus` 等）到 Ant Design Tag 颜色与中文标签的唯一真源，所有展示侧统一走 `metaOf` 取值，避免枚举新增值时多处漏改。
- **零 Key 处理**：登录后的应用外壳挂载时调用 `GET /api/v1/system/model-status`；`embedding_configured=false` 时通过 `MainLayout` 顶部常驻 `Alert` 提示，检索调试页与系统设置页同样消费该状态展示相应引导文案。
- **XSS 约束**：解析预览、分片内容等一律走 preformatted 纯文本渲染，全仓不使用 `dangerouslySetInnerHTML`。
- **文案**：一期不引入 i18n 库，枚举类展示文案统一收敛在 `statusMeta.ts`；其余静态 UI 文案暂以组件内字面量为主，作为二期英文界面改造的已知技术债（详见 `kb-rag-deploy/docs/知识库需求文档.md` §8）。
- **不引入 mock 数据**：所有页面直连 `kb-rag-server`，开发态通过 `vite.config.ts` 的 dev proxy 转发。
- **知识图谱可视化**：`pages/kb/components/GraphVisualization.tsx` 自实现 Fruchterman-Reingold SVG 力导向布局（seeded random 防抖动、50 节点上限），零外部依赖，刻意不引入 `@antv/g6`。

## 环境变量

前端本身无需 `.env`；开发代理目标固定为 `http://127.0.0.1:20000`（如需调整请修改 `vite.config.ts` 中的 `BACKEND_ORIGIN`）。生产环境构建产物由反向代理（如 Nginx）转发 `/api`、`/actuator` 到后端服务。

## 文档导航

- 整体架构（web 一节）：`kb-rag-deploy/docs/ARCHITECTURE.md` 第 5 节
- 关键流程时序图（含前端参与的每条链路）：`kb-rag-deploy/docs/FLOWS.md`
- 各里程碑接口契约：`kb-rag-deploy/docs/M1~M22-CONTRACTS.md`
- 参与贡献、提交规范：`CONTRIBUTING.md`
- 安全问题上报：`SECURITY.md`
