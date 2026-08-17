# M23 开发契约：Confluence Cloud 数据源连接器

> 状态：已实现并与代码、控制台和 OpenAPI 对齐（2026-08-14）。
> 官方依据：[Confluence Cloud REST API v2 - Space](https://developer.atlassian.com/cloud/confluence/rest/v2/api-group-space/)、[Page](https://developer.atlassian.com/cloud/confluence/rest/v2/api-group-page/)、[cursor pagination](https://developer.atlassian.com/cloud/confluence/rest/v2/intro/)、[Basic auth with API token](https://developer.atlassian.com/cloud/confluence/basic-auth-for-rest-apis/)。

## 0. 范围与边界

本期在 M14 `ExternalConnector` SPI 上增加 `source_type=confluence`，同步一个 Confluence Cloud Space 中当前可见的页面。页面继续走 `DocumentService.upload`，不新增入库旁路，因此文档版本、审核发布、有效期、回收站、解析切分和索引补偿全部沿用既有语义。

本期只支持 Confluence Cloud REST API v2 的普通 page：不支持 Data Center、blog post、attachment、whiteboard、database、comment 和页面级 label/ancestor 过滤；不下载页面里的远端附件或图片。正文使用 `body-format=storage`，交给现有无网络 I/O 的 HTML parser 提取文本。

## 1. 登记字段映射

沿用 `t_kb_ext_source`，无 Flyway 迁移、无新增配置键和第三方依赖。

| API / 数据库字段 | S3 语义 | Confluence Cloud 语义 |
|---|---|---|
| `source_type` | `s3` | `confluence` |
| `endpoint` | S3/OSS Endpoint | Site URL，例如 `https://example.atlassian.net`；允许末尾 `/wiki`，保存值保持用户输入 |
| `bucket` | Bucket | Space Key，例如 `ENG` |
| `region` | 可选 Region | 不使用，控制台不展示 |
| `prefix` | 可选对象前缀 | 不使用，控制台不展示 |
| `access_key` | Access Key ID | Atlassian 账号邮箱 |
| `secret_key` | Secret Access Key | Atlassian API Token；读 API 仍恒返回 `******`，更新留空仍保留旧值 |

`ExternalConnector.validateConfig` 是登记/更新前唯一的连接器特定校验入口。Confluence Site URL 必须为 HTTPS，不能携带 userinfo、query、fragment 或 `/wiki` 之外的业务路径；配置错误在 HTTP 请求内直接失败，不等待异步首同步。

## 2. 列举、增量与正文

1. `GET /wiki/api/v2/spaces?keys={spaceKey}&status=current&limit=2` 将 Space Key 解析为 Space ID；必须且只能命中一个同 key 空间。
2. `GET /wiki/api/v2/spaces/{spaceId}/pages?status=current&limit={n}` 按 v2 cursor 分页列举。优先读取 body `_links.next`，缺省时兼容 HTTP `Link: <...>; rel="next"`。
3. 单轮只收集 `kb.ext-source.max-objects-per-source + 1` 条：多出的 1 条只用于证明列表被截断。截断时源状态为 PARTIAL，且不执行“远端页面消失”判定。
4. 页面对象键固定为 `confluence/{pageId}.html`，ETag 固定为 `{pageId}:v{version.number}`。首次入库用页面标题生成可读文件名并附对象 key hash；绑定后始终沿用该文档文件名，所以标题变化只会触发同一文档的新版本，不会创建第二篇文档。未变化页面记 UNCHANGED，不请求正文。
5. 变化页面通过 `GET /wiki/api/v2/pages/{pageId}?body-format=storage` 获取标题与 storage body，生成 UTF-8 HTML 后进入普通上传链路。标题做 HTML 转义，storage body 原样置于 `<article>`；现有 parser 不加载脚本、样式、图片或其他网络资源。
6. API 列表缺少 results、页面缺少 id/version、分页 cursor 循环等不完整响应必须使本轮失败，不能把“远端响应异常”误判为“页面已删除”。

## 3. 安全与资源边界

- 认证使用 `Authorization: Basic base64(email:apiToken)`，只发往登记 Site URL 的同 scheme/host/port。JDK HttpClient 禁止自动重定向；绝对 next URL 若跨 origin，在发请求前拒绝，防止 Token 外带。
- 每个请求沿用 `kb.ext-source.fetch-timeout-ms`；元数据响应上限 5 MiB，正文响应受 `kb.upload.max-file-size-mb` 约束。M23 同时将 S3 `readAllBytes` 收敛为同一上传体积上限，避免远端对象无界占用堆内存。
- endpoint 是持 `doc:write` 的管理员配置，不经过面向终端用户 URL 导入的 `UrlGuard`；Confluence Cloud 强制 HTTPS。若部署允许管理员登记恶意代理，风险边界与 M14 的自定义 S3 endpoint 相同。
- API Token 延续 V15/D17 的明文存储决策，仅适合自托管、受控管理员和网络隔离场景。Atlassian 明确建议面向多客户分发的应用使用 OAuth 2.0 3LO；Marketplace 化前必须迁移，不得继续收集客户个人 Token。

## 4. 控制台与兼容性

- “外部数据源”表单新增连接器类型选择。编辑时类型不可变；S3 展示 Endpoint/Region/Bucket/Prefix/AK/SK，Confluence 展示 Site URL/Space Key/邮箱/API Token。
- 列表增加类型列与通用“同步范围”；明细抽屉对 Confluence 显示“页面 Key”。同步、测试连接、定时开关、移除及 secret 掩码端点完全复用 M14 API。
- `source_type=s3` 的持久化形态、接口和同步语义不变。`ExtSourceConfig` 新增上传体积预算属于内部 SPI 变更，无对外 JSON 变化。

## 5. 测试与验收

Java 离线单测覆盖：cursor 分页与 cap+1、版本 ETag、Basic Header、storage HTML 物化、跨 origin next 拒绝、畸形列表不触发消失语义、401 不泄漏 Token、Site URL 归一化，以及登记前 connector-specific fast-fail。控制台执行 `test/lint/build`，部署侧校验 OpenAPI YAML 与文档镜像一致性；里程碑结束仍必须执行仓库全量测试门禁。
