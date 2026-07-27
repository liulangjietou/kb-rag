# 备份与恢复（M6）

> 覆盖 docs/M6-CONTRACTS.md §3（deploy 侧脚本）与 §4 验收⑥/⑦。本篇只讲运维操作；
> 快照原语/发布流程/检索调用上下文等 kb-rag-server 内部设计见 M6-CONTRACTS.md §0。

## 1. 备份策略与 RPO

`scripts/backup.sh` 每次运行产出一份**全量、自描述**的备份，落地
`${BACKUP_DIR}/<UTC 时间戳>/`（默认 `BACKUP_DIR=./backup`，可在 `.env` 覆盖）：

| 组件 | 方式 | 产物 |
|---|---|---|
| MySQL | `mysqldump --single-transaction`（InnoDB 一致性快照，不锁表，kb_rag 全库） | `mysql/kb_rag.sql.gz` |
| Elasticsearch | `_snapshot` API，仓库类型 `fs`，仅覆盖 `kb_*` 业务索引 | `es-snapshot.json`（仓库名/快照名指针）+ 快照物理数据写入共享仓库目录 |
| MinIO | `mc mirror`，`kb-files` 桶全量镜像 | `minio/kb-files/...` |
| 汇总 | — | `manifest.json`（三段各自 status + 体积） |

**RPO 目标 ≤24h**：建议 cron 每日调度（脚本头注释含示例）：

```bash
0 2 * * * cd /path/to/kb-rag-deploy && ./scripts/backup.sh >> /var/log/kb-rag-backup.log 2>&1
```

**ES 快照的存储模型（务必先理解，否则会误删数据）**：ES `fs` 仓库按段（segment）
去重增量存储，因此 backup.sh **不会**把快照字节复制进每次的时间戳目录，而是持续写入
`docker-compose.lite.yml` 里挂载的共享目录 `./backup/es-repo`（与 `path.repo` 绑定）。
每次运行只新增一个快照名，时间戳目录里的 `es-snapshot.json` 记录这次备份对应哪个
快照名。**离线归档时必须把整个 `./backup` 目录（含 `es-repo/`）一起搬走**，只复制
时间戳子目录会丢失 ES 快照的实际数据。

**RTO**：不设硬指标，但每次恢复演练需把端到端耗时记入本文档「演练记录」一节（见 §3）。

## 2. 脚本用法

### 2.1 备份

```bash
./scripts/backup.sh                          # 用 .env 中的配置
BACKUP_DIR=/mnt/backup ./scripts/backup.sh   # 临时覆盖备份根目录
BACKUP_KEEP_COUNT=14 ./scripts/backup.sh     # 临时调整轮转保留份数（默认 7）
```

前置条件（脚本会 fast-fail 并给出可操作提示，不会挂起或静默失败）：
- `docker`、`curl`、`gzip` 已安装
- `kb-rag-mysql` / `kb-rag-es` / `kb-rag-minio` 三个容器都在跑
  （`docker compose -f docker-compose.lite.yml up -d`，full 模式用 `docker-compose.yml`）

退出码：`0` 全部成功；`1` 至少一段失败（`manifest.json` 里对应 `status` 会是
`"failed"`，日志会指出具体原因，如 mysqldump 报错文件、ES 快照响应体、mc 日志路径）。

### 2.2 恢复

```bash
./scripts/restore.sh ./backup/20260726T120000Z          # 交互确认后恢复
./scripts/restore.sh ./backup/20260726T120000Z --yes    # 跳过确认（演练/CI 自动化用）
```

恢复顺序固定 **MySQL → Elasticsearch → MinIO**：
1. `DROP DATABASE IF EXISTS kb_rag` 后从 `mysql/*.sql.gz` 全量重建（先 DROP 是为了
   清掉 dump 之后新增、dump 里没有的表，恢复到备份时点而不是与当前状态合并）；
2. 删除当前所有 `kb_*` 索引（`DELETE /kb_*`，`action.destructive_requires_name=false`
   已在 compose 里配置），必要时重新注册 ES 仓库，再按 `es-snapshot.json` 里记录的
   仓库名/快照名执行 `_restore`；
3. `mc mirror --remove` 把 `minio/<bucket>/` 逆向镜像回桶（`--remove` 会删除桶里
   多出来、备份时点不存在的对象，做到真正的「恢复到备份时点」）。

任一段失败立即中止（fast-fail），不会继续跑下一段。恢复完成后脚本会打印验证提示
（chunk 行数、`_cat/indices/kb_*`、建议跑一次 `scripts/benchmark.sh` 或直接调用
search 接口确认召回非空）。

### 2.3 常用环境变量

与 `scripts/preflight.sh` / compose 同源，均可在 `.env` 覆盖（见脚本头注释获取完整列表）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `BACKUP_DIR` | `./backup` | 备份根目录，建议指向独立磁盘/外部挂载点 |
| `BACKUP_KEEP_COUNT` | `7` | 按时间戳目录轮转保留份数 |
| `ES_SNAPSHOT_REPO` | `kb_rag_repo` | ES 快照仓库名 |
| `ES_SNAPSHOT_REPO_PATH` | `/usr/share/elasticsearch/snapshot_repo` | 容器内仓库路径，必须与 compose `path.repo` 一致 |
| `MC_IMAGE` | `minio/mc:RELEASE.2024-05-09T17-04-24Z` | 一次性 mc 客户端镜像版本（与 compose minio server 同期） |

## 3. 恢复演练步骤清单（M6 验收⑥）

按顺序执行，每步都有明确的期望结果；**建议先在非生产环境跑一遍**，因为第 3 步会
真实删除当前数据：

1. **确认基线**：记录当前 chunk 行数与某个已知 kb_id 的检索能返回非空结果，作为
   恢复后比对的基准。
   ```bash
   docker exec kb-rag-mysql mysql -uroot -p<pwd> -N -B -e "SELECT COUNT(*) FROM kb_rag.t_kb_chunk;"
   ```
2. **执行一次备份**，记下产出的时间戳目录路径。
   ```bash
   ./scripts/backup.sh
   ```
3. **模拟灾难**（删库删索引，制造需要恢复的场景）。
   ```bash
   docker exec kb-rag-mysql mysql -uroot -p<pwd> -e "DROP DATABASE kb_rag;"
   curl -X DELETE http://127.0.0.1:9200/kb_*
   ```
4. **计时执行恢复**（记录开始/结束时间，即本次 RTO 实测值）。
   ```bash
   date -u; ./scripts/restore.sh ./backup/<第 2 步的时间戳> --yes; date -u
   ```
5. **验证检索可用且召回非空**：对照第 1 步的基准 kb_id 重新发起检索，确认结果
   与灾难前一致（chunk 行数相同、召回非空）——这是 M6 主验收②「回滚后检索恢复
   历史状态且召回非空」在基础设施层面的前置保证。
   ```bash
   docker exec kb-rag-mysql mysql -uroot -p<pwd> -N -B -e "SELECT COUNT(*) FROM kb_rag.t_kb_chunk;"
   curl http://127.0.0.1:9200/_cat/indices/kb_*?v
   KB_ID=<基准 kb_id> TOKEN=<登录 token> ./scripts/benchmark.sh
   ```
6. **记录本次演练**：把执行日期、RTO 实测耗时、数据规模（chunk 行数/ES 文档数/
   MinIO 对象数）追加到下方「演练记录」表格。

### 演练记录

| 日期(UTC) | chunk 行数 | RTO 实测 | 备注 |
|---|---|---|---|
| （首次演练待补） | | | |

## 4. 压测种子数据用法（M6 验收⑦）

`scripts/seed-bench.py` 零 Key 直写 MySQL + Elasticsearch bulk，灌入一个独立的
「种子知识库」（默认 `kb_id=kb_benchseed`），用于 10 万分片规模的检索压测，不依赖
任何模型 Key（BM25 路径足以验证 P95）。种子分片的主题短语与 `scripts/benchmark.sh`
内置的 10 条查询同题材，因此可以直接互相搭配使用。

```bash
# 生成默认 10 万分片（50 篇文档，每篇 2000 片），幂等：重复运行会先清理同 kb_id 的旧数据
python3 scripts/seed-bench.py

# 自定义规模/批大小
python3 scripts/seed-bench.py --chunk-count 200000 --batch-size 5000 --docs-count 100

# 压测（P95 对照 M2-CONTRACTS.md §7 基线口径）
KB_ID=kb_benchseed TOKEN=<登录 token> ./scripts/benchmark.sh

# 压测结束后清理种子数据（不影响其他知识库）
python3 scripts/seed-bench.py --clean-only
```

依赖：优先使用 PyMySQL（若已安装）；未安装则自动回退到宿主机 `mysql` 命令行客户端
管道执行 SQL，二选一即可，无需都装。Elasticsearch 交互只用 Python 标准库
（`urllib` + `json`），不引入额外依赖。

**验收口径记录**：10 万分片压测的 P50/P95/P99（`scripts/benchmark.sh` 输出）与
M2 同口径基线的对比，按 M6-CONTRACTS.md §4 验收⑦要求（P95 劣化 ≤20%）填入
`docs/M6-CONTRACTS.md` §5「实现期修订」。
