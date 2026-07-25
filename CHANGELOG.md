# 变更记录

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。
标注 `[schema]` 的条目包含数据库迁移脚本，升级时会自动执行 Flyway。

## [未发布]

### 新增（M1）

- `[schema]` Flyway `V1__baseline.sql`：建立 10 张基线表（知识库、文档、文档版本、分片、分片×物理索引同步状态、索引注册表、异步任务、管理员、系统设置、登录审计）
- 启动初始化：数据库无管理员时创建 admin 账号，随机密码打印一次并强制首登改密
- 登录鉴权：BCrypt 校验 + Bearer Token（默认 24h）+ 基于登录审计表的防爆破锁定（默认 5 次锁 15 分钟）
- 知识库 CRUD，创建时同步建物理索引、绑定别名并登记 `t_kb_index_registry`
- 文档上传：扩展名 + 文件头（magic number）+ 大小三重校验，原件存 MinIO 私有桶
- 异步索引管线：调用 parser 解析 → 按长度切分（默认 600 token / 重叠 100）→ 分片落库（含 `chunk_text_hash`）→ 写全文索引 →（有 Key 时）嵌入并写向量索引 → 按物理索引登记同步状态 → 版本激活
- 检索 API：有 Key 走向量 + BM25 双路 RRF（k=60）融合，零 Key 走 BM25 单路并返回 `degraded=[vector_route_unavailable]`；两路均在引擎侧强制过滤激活版本与启用状态
- Provider 抽象：嵌入（DashScope，OpenAI 兼容 HTTP 端点）落地，重排 / 对话 / 视觉三类接口与未配置占位实现就位
- 引擎抽象：`VectorStore` 双实现（Elasticsearch dense_vector 与 Milvus），`FulltextStore` Elasticsearch 实现；向量分统一换算为标准 cosine 后线性映射到 `[0,1]`
- `GET /api/v1/system/model-status`：向管理台透出是否配置嵌入模型与当前向量引擎
- `GET /actuator/health`：含 MySQL、Elasticsearch、MinIO 探活，配置 Milvus 时增加 Milvus 探活
- 统一响应包装、错误码枚举与 `request_id` 全链路透传（入口 filter 生成，写入 MDC 并透传至 parser）
