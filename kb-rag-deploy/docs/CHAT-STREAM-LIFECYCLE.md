# 聊天流式输出、取消与用量结算

控制台预览和开放问答 API 使用同一条生成链路。OpenAI 兼容模型请求现在显式启用 `stream: true`，每收到一个完整的 SSE 数据帧就输出增量文本；正常答案必须收到上游 `[DONE]` 后才发送 `references` 和 `done`。

## 对用户的行为

- 首段文本在模型生成完成前显示；中途断网或上游缺少结束帧时，已显示文本保留，按错误结束，不伪装为完整答案。
- 点击停止或离开页面关闭连接后，服务端取消对应 HTTP 请求。静默期间每 5 秒发送 SSE 注释心跳，使没有新 Token 时的断连也能被发现。检测延迟还取决于网络和反向代理。
- 等待响应头和响应体均受 `kb.chat.generate-timeout-ms` 的完整生成预算约束；应用版本固定的模型继承这一配置。
- 取消后不自动重新发起生成。已有检索子调用按其原有超时结束；进入最终模型生成前再次检查取消状态。
- 这批接口仍是连接生命周期；刷新后的持久化会话和任务恢复属于后续会话功能。

## 调用链与职责

`AppController.chatPreviewStream` / `KnowledgeOpenApiController.chatStream`
→ `SseChatStreamFactory` / `SseChatStreamListener`
→ `KnowledgeApiService`
→ `AnswerGenerationService`
→ `ChatProvider`
→ `DashScopeChatProvider` / `ChatStreamHttp`。

传输层拥有连接和心跳，应用层负责阶段检查及审计，模型适配器持有可取消的上游 HTTP 句柄。取消只绑定该次调用的资源，不通过保存线程引用来中断可能已复用的线程池线程。预览仍在请求线程检查知识库权限；版本、租户和开放 API 访问范围继续由原有服务校验。

解析支持 UTF-8 跨块、BOM、LF/CRLF/CR、注释和多行 data；只派发以空行结束的帧。单行最多 64 KiB、事件累计最多 1 Mi 字符、HTTP 错误正文最多读取 4 KiB。协议错误不记录原始模型正文。异常或取消会终止订阅，服务端终态和心跳停止均只执行一次。

## 用量口径

| 情况 | 状态 | 配额处理 |
| --- | --- | --- |
| 请求未发出前取消 | CANCELLED | 释放预占 |
| 上游明确 HTTP 拒绝 | FAILED | 释放预占 |
| 正常结束且返回 usage | SUCCEEDED | 按上游计数结算 |
| 正常结束但未返回 usage | SUCCEEDED + estimated | 按预占上界结算 |
| 已发出后取消，未获 usage | CANCELLED + estimated | 按预占上界结算 |
| 上游断流、超时或协议错误 | FAILED | 有 usage 按计数，否则按预占上界结算 |

`stream_options.include_usage` 请求末尾的用量块，但取消时可能收不到它。关闭本地 HTTP 连接只能发起取消，不能保证供应商已停止计费。因此状态、估算标记、是否定价分别展示；已结算但失败或取消的调用仍进入月度成本，未定价费用不显示为已知零值。币种分别汇总，查询保留租户、月份和软删除边界。

计量在原生成线程完成，保留异步任务继承的租户计量上下文。计量失败不释放已经可能产生费用的预约，继续由既有过期预约机制处理。

## 发布和回退

没有数据库结构迁移；现有 `status VARCHAR(16)` 可存储新增终态。API 文档和前端类型包含 `CANCELLED`，管理台显示“已停止”。按服务端、前端顺序发布，并确认代理不缓存 SSE 响应，且读超时允许心跳持续传输。

计量与汇总兼容变更先于增量传输单独提交。若需回退流式传输，回退计量提交之后的传输变更并保留计量提交：历史 CANCELLED / FAILED 有费记录必须继续参与汇总。直接回退到旧版只统计 SUCCEEDED 的 Mapper 会漏计历史成本；无需也不应批量改写历史账单状态来配合旧查询。

## 验证

- 真实本地 HTTP 流：首段提前送达、请求头等待取消、部分输出后连接实际关闭、静默响应体超时、401 分类、末尾用量与缺失 DONE，以及网关基础路径和版本查询参数保留。
- 协议边界：中文和 emoji 逐字节分片、三种换行、残缺帧、畸形 JSON、超长帧、终态后数据。
- 嵌入式 Tomcat 与真实上游 HTTP：客户端收到首段后主动断开，心跳触发取消，账单按估算中断结算。
- 应用编排：取消后不开始新的模型工作，不发送引用与成功终态，开放 API 记录 CHAT_CANCELLED。
- 真实 MyBatis + H2 SQL：取消/失败有费调用的汇总、币种隔离、租户/月份/软删除边界。
- 浏览器：已停止、估算、未定价分别显示。

这些验证不替代真实模型内容质量评测；固定业务语料的 RAG 回归和性能基线仍按整体实施计划执行。

协议依据：[OpenAI Chat 流式用量](https://developers.openai.com/api/reference/resources/chat)、[WHATWG SSE 帧规则](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)、[JDK 17 HTTP 取消语义](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html)。
