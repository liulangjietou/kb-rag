# 升级指引

本文档面向自托管部署者，说明升级 kb-rag 各组件（镜像、应用、数据库 schema）
的原则与步骤。对应 [知识库需求文档 §5「升级与迁移」](docs/知识库需求文档.md)
条款——自建部署的开源项目没有云厂商托管的迁移机制，升级即需要部署者自己按
本文档的顺序操作。语义化版本变更见 [CHANGELOG.md](CHANGELOG.md)。

## 升级前：先备份

任何升级前，先跑一次全量备份：

```bash
./scripts/backup.sh
```

产物落在 `.env` 中 `BACKUP_DIR`（默认 `./backup`）下的 `<UTC 时间戳>/` 子目录，
含 MySQL 全量 dump、ES 快照与 MinIO 镜像三段，`manifest.json` 汇总各段
status 与体积——升级前确认三段均为 `ok`。备份脚本、恢复脚本与演练步骤见
README「备份与恢复（M6）」一节与 [`docs/backup-restore.md`](docs/backup-restore.md)。

## compose 镜像 tag 固定原则

`docker-compose.lite.yml` / `docker-compose.yml` / `docker-compose.es-ik.yml`
里的全部镜像 tag 均固定到具体版本号，**禁止使用 `latest`**（避免同一份 compose
文件在不同时间 `up` 出不同版本，升级与回退都失去可预期性）。

升级某个中间件版本的标准做法：

1. 修改该服务的镜像 tag（例如把 `elasticsearch:8.11.4` 改成新版本号）
2. `docker compose pull <service>` 拉取新镜像
3. `docker compose up -d <service>` 重建该容器，观察 `docker compose ps` 与
   healthcheck 状态
4. 跑一次「上传 → 检索」冒烟用例确认功能正常

**逐个中间件独立升级、独立验证**，不要一次性批量升级全部服务；升级失败可以
直接把 tag 改回旧版本号回退（本地镜像缓存未清理时无需重新 pull）。

## MySQL：Flyway 自动迁移

- MySQL schema 变更一律走 **Flyway** 版本化迁移脚本（脚本在 kb-rag-server
  仓库），应用启动时自动执行，**禁止任何手工 DDL**
- 迁移脚本约定**向后兼容一个版本**（优先只加列；允许 `VARCHAR` 等不改变既有值语义的兼容性
  扩宽，但不删列、不收窄类型、不改列名/含义、不重命名表）——这意味着按 CHANGELOG 顺序逐个
  里程碑升级是安全的；**不建议跨版本跳升**
  （例如从 M5 直接跳到 M8），请按顺序逐个里程碑升级并在每步跑一次冒烟检索
- 升级失败时可以把 kb-rag-server 镜像直接回退到旧版本；因为迁移向后兼容，
  回退后的旧代码仍可正常读写新 schema（多出的列被忽略，不会报错）

## v1.0.0 → v1.1.0：控制台登录改用 Sa-Token（会话底座更换）

`v1.1.0` 把控制台会话底座换成了 Sa-Token，是从 `v1.0.0` 升级时唯一需要额外注意的一次变更
（本项目的 MAJOR 判定范围限于两条开放 API，故该变更随 MINOR 发布，说明见
[CHANGELOG.md](CHANGELOG.md) 头部）：

- **所有人需要重新登录一次。** 令牌格式与请求头都变了，旧会话无法沿用。没有办法避免，
  也不需要任何操作——用户重新登录即可。
- **调用管理 API 的脚本必须改请求头**：`Authorization: Bearer <token>` → `satoken: <token>`。
  影响 178 个受认证保护的端点。**两条开放 API 不受影响**（`/api/v1/knowledge/*` 的 API Key、
  `/api/v1/memory/*` 的 Memory Key 仍用 `Authorization: Bearer`），用它们做集成的脚本无需改动。
  控制台前端已同步，用打包产物的部署者不必操作。
- **Flyway V25 只加表不删表**，符合"向后兼容一个版本"，因此**回退路径依然成立**：把 server 镜像
  换回旧版本即可，旧代码读的 `t_kb_auth_token` 仍在（只是所有人要再登一次）。
- **新增配置键 `KB_CACHE_PROVIDER`，默认 `local`，单实例部署零操作**：会话写进 MySQL，
  行为与升级前一致（重启不掉线），**仍然不需要 Redis**。
- 确认新版本稳定、不再打算回退之后，可以手工清掉那张不再有读者的旧表：

  ```sql
  DROP TABLE t_kb_auth_token;
  ```

- **跑多副本切 `KB_CACHE_PROVIDER=redis`**：该开关同时把会话与 RBAC 权限缓存放进 Redis，两者同步
  切换，不可分开配置。单副本保持默认 `local` 即可，不需要 Redis。详见 `docs/ARCHITECTURE.md` §7.2。
### V26 邮箱身份声明的单版本切换例外

V26 的列和表在语法上仍向后兼容，但它新增的 `t_kb_email_identity_claim` 是跨 `username/email`
两列的业务唯一性账本，V25 及更早代码不会维护该表。因此 V26 **不能让新旧 server 混写账号身份**，
也不能在回退旧镜像后继续执行账号创建、联系邮箱修改、SSO 首次自动建号或注册审批。标准步骤是：

1. 冻结上述四类写操作，现有账号登录和知识库读写可继续；执行
   [`docs/M26-CONTRACTS.md`](docs/M26-CONTRACTS.md) §5 的冲突预检并完成备份。
2. 一次性替换全部 server 实例并确认 Flyway V26 成功，再解除冻结；禁止滚动窗口内新旧版本同时建号。
3. 若必须回退旧镜像，保持身份写入冻结。再次升级前重跑同一预检，并核对账号表与声明表差异，
   不能把“旧代码能忽略新表”误当成仍满足邮箱唯一性。
## ES / Qdrant：schema 变更走"从事实源重建 + 别名切换"

ES / Qdrant 的索引结构变更**不走迁移脚本**，官方迁移路径是"从 MySQL 事实源
全量重建索引 + 别名原子切换"（`IndexAliasManager`，见
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §3.3）：

- 触发方式：知识库详情页的"重建索引"入口，或管理台 API
  `POST /api/v1/kb/{kbId}/rebuild`
- 重建期间旧索引持续对外提供服务，别名切换是原子操作，读侧不中断
- 索引 metadata 记录 `schema_version`；kb-rag-server 启动时会做兼容性校验，
  发现版本不兼容时任务中心会提示"需重建索引"

嵌入模型切换、lite → full 迁移同样复用这套"建新物理索引 → 回填 → 别名切换"
的原语，具体流程见 README「快速启动」与 `docs/FLOWS.md` §5。

## 升级步骤建议

1. `./scripts/backup.sh` 全量备份
2. 阅读目标版本对应的 [CHANGELOG.md](CHANGELOG.md) 条目，确认是否含 schema
   变更（见下一节）以及是否有需要手工触发的动作（如索引重建）
3. 按里程碑顺序升级，不要跳版本；每步替换对应的 kb-rag-server /
   kb-rag-parser / kb-rag-web 镜像或代码，中间件版本按上方"镜像 tag"流程处理
4. `docker compose ... up -d`，观察 Flyway 迁移日志（kb-rag-server 启动日志）
   与各服务 healthcheck
5. 跑一次"上传 → 检索"冒烟用例；若该版本涉及 ES/Qdrant schema 变更，额外触发
   一次索引重建并确认检索结果符合预期
6. 确认无误后，本次备份按 `scripts/backup.sh` 的 `BACKUP_KEEP_COUNT` 滚动策略
   自动保留

## CHANGELOG 条目如何标注 schema 变更

每个里程碑在 [CHANGELOG.md](CHANGELOG.md) 的正文或「Notes」小节中会说明该次
变更**是否包含 schema 变更**及具体内容，例如：

- `V7（M6）应用版本加 visible_version_ids + index_snapshots`
- `V9（M8）建表 t_kb_source_mapping`
- `V26（M26）建邮箱注册、幂等声明、审核角色快照、outbox 与身份声明六表，并把登录链路 username 兼容性扩宽至 254`
- 或明确写"不含 schema 变更（本仓库不承载数据库 migration，Flyway 脚本在
  kb-rag-server 仓库）"

看到条目里出现 `V<n>` Flyway 版本号或"建表"/"加列"字样，代表该版本的
kb-rag-server 启动时会自动执行对应迁移——按上方"MySQL：Flyway 自动迁移"一节
的向后兼容保证操作即可，无需额外协调数据库迁移窗口；若条目同时提到 ES/Qdrant
字段或索引变更（如新增可过滤字段），需要在升级后额外触发一次索引重建（见上一
节）。不含 schema 变更的条目（多数为部署脚本/文档/纯应用逻辑改动）可以直接
升级，无需考虑数据库兼容性。
