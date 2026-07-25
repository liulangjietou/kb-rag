# kb-rag-web

知识库 RAG 系统的前端管理台（M1 基础框架）。技术栈：Vite + React 18 + TypeScript + Ant Design 5 + react-router v6 + axios。

对应契约：`kb-rag-deploy/docs/M1-CONTRACTS.md` 第 5 节（REST API）与第 7 节（前端页面范围）。

## 快速开始

依赖后端 `kb-rag-server`（默认监听 `8080`）先启动，本项目开发模式下会将 `/api` 与 `/actuator` 请求代理到 `http://127.0.0.1:8080`（见 `vite.config.ts`）。

```bash
npm install
npm run dev      # 启动开发服务器，默认端口 5173
```

首次启动后端时会在日志中打印随机生成的 `admin` 初始密码，使用该账号登录后系统会强制跳转到修改密码页面。

## 常用脚本

| 命令 | 说明 |
|---|---|
| `npm run dev` | 启动开发服务器（含 /api、/actuator 代理） |
| `npm run build` | TypeScript 类型检查（`tsc -b`）+ 生产构建 |
| `npm run preview` | 预览生产构建产物 |
| `npm run lint` | oxlint 静态检查 |
| `npx tsc --noEmit` | 仅做类型检查，不产出文件 |

## 页面与路由

| 路由 | 页面 | 说明 |
|---|---|---|
| `/login` | 登录页 | 登录成功后若 `must_change_password=true` 强制跳转改密页 |
| `/change-password` | 首登改密页 | 修改成功后放行到知识库列表 |
| `/kb` | 知识库列表 | 卡片形式展示，支持新建（弹窗）、删除（二次确认）、空态引导 |
| `/kb/:kbId` | 知识库详情 | 文档拖拽上传、文档列表（状态标签 + 3s 轮询）、失败原因提示、重建按钮、分片抽屉 |
| `/search` | 检索调试 | 选择知识库 + query + recall_top_k/top_n，结果卡片展示 score/score_type/retrieval_source，`degraded` 非空时顶部告警 |
| `/settings` | 系统设置（占位） | 展示只读的嵌入模型状态，功能页面留待后续里程碑 |

## 目录结构

```
src/
  api/            # 与后端契约对齐的类型定义 + axios 封装 + 各资源 API 函数
  auth/           # 登录态 Context 与路由守卫（RequireAuth / RequirePasswordChanged）
  context/        # ModelStatusContext：零 Key 模式全局状态
  layout/         # 左侧菜单 + 顶栏的主布局（MainLayout）
  pages/          # 各页面组件，按业务域分子目录（kb / search / settings）
  router/         # 路由表（AppRouter）
  utils/          # 格式化、状态展示元信息等纯函数工具
```

## 工程约定

- **统一响应处理**：`src/api/request.ts` 中的 axios 实例统一注入 `Authorization: Bearer <token>`；后端 `{code, message, data, request_id}` 信封由 `unwrap()` 统一拆包，业务错误码非 `OK`、HTTP 层错误、401 均在拦截器中集中处理并通过 `antd.message` 提示（附带 `request_id` 便于排查）。
- **零 Key 处理**：登录后的应用外壳挂载时调用 `GET /api/v1/system/model-status`；`embedding_configured=false` 时通过 `MainLayout` 顶部常驻 `Alert` 提示，检索调试页与系统设置页同样消费该状态展示相应引导文案。
- **不引入状态管理库**：登录态与模型状态均使用 React Context + hooks 实现，未引入 Redux/Zustand 等三方状态库。
- **不引入 mock 数据**：所有页面直连 `kb-rag-server`，通过 `vite.config.ts` 的 dev proxy 转发。

## 环境变量

前端本身无需 `.env`；开发代理目标固定为 `http://127.0.0.1:8080`（如需调整请修改 `vite.config.ts` 中的 `BACKEND_ORIGIN`）。生产环境构建产物由反向代理（如 Nginx）转发 `/api`、`/actuator` 到后端服务。
