# Changelog

本文件记录 kb-rag-deploy 仓库的显著变更，格式遵循
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- （M2）`es-ik/Dockerfile`：基于 `docker.elastic.co/elasticsearch/elasticsearch:8.11.4`
  安装 analysis-ik 插件（infinilabs 官方发布 zip，`IK_VERSION` 构建参数化，默认
  `8.11.4`）+ `docker-compose.es-ik.yml` override（build 该镜像替换 elasticsearch
  服务、挂载 `es-ik/config/IKAnalyzer.cfg.xml` 对接 kb-rag-server 词典热更新通道、
  Linux 下用 `extra_hosts: host.docker.internal:host-gateway` 补齐 macOS Docker
  Desktop 自带的 host 域名解析），README 增补「启用 ik」章节（M2-CONTRACTS.md §3）
- （M2）`scripts/benchmark.sh`：对指定知识库并发跑检索压测（`BASE_URL`/`TOKEN`/
  `KB_ID`/`QUERY_FILE`/`TOTAL`/`CONCURRENCY` 均可配置，默认内置 10 条中文查询、
  200 次、并发 5），纯 bash + curl + awk + sort 实现（不引入 jq/python 依赖），
  输出 P50/P95/P99 与错误数（含连接失败 `000` 的清晰提示），对应验收口径
  M2-CONTRACTS.md §7「基础链路 P95<2s」
- （M2）`docs/openapi/kb-server.yaml` 同步 M2-CONTRACTS.md §1.5/§3/§4 契约：search
  新入参（score_threshold/fusion/rerank_enabled/rewrite_enabled/messages/
  metadata_filter）与出参（`applied` 信息条、`RetrievalNode.metadata` 新增各路
  归一化分/rerank 分/child_ids）、`score_type`/`degraded` 枚举扩展、新增
  ik 词典 CRUD（`/api/v1/dict/ik`、`/api/v1/dict/ik/{dictId}`）与索引配置/重建
  端点（`PUT /api/v1/kb/{kbId}/index-config`、`POST /api/v1/kb/{kbId}/rebuild`）
- `docker-compose.lite.yml`：轻量模式中间件编排（MySQL 8.0 + Elasticsearch 8.11.4 单节点
  关闭安全模块 + MinIO），全部服务带 healthcheck / restart: unless-stopped / 固定镜像 tag /
  命名 volume
- `docker-compose.yml`：完整模式编排，在 lite 基础上（通过 Compose `include` 复用，避免
  重复维护）叠加 Milvus 2.4.x standalone（独立 etcd + 独立 milvus-minio，与应用侧 MinIO
  隔离）与 Redis 7.2.x（`--profile redis` 显式开启，标注 optional）
- `.env.example`：契约 §1 全部环境变量 + docker-compose 专用变量，中文注释标注零 Key 模式
  下可空的变量
- `scripts/preflight.sh`：部署前置检查（docker/内存/端口占用/占位口令检测）
- `scripts/backup.sh`：MySQL 全量 mysqldump + MinIO 数据卷全量导出，按份数轮转
- `docs/openapi/kb-server.yaml`、`docs/openapi/kb-parser.yaml`：M1 端点 OpenAPI 3.0 契约
  （含 RetrievalNode、统一错误响应、degraded 枚举）
- 开源工程基线文件：LICENSE (Apache-2.0)、NOTICE（MySQL/ES/Milvus/MinIO/Redis/MinerU
  许可声明）、SECURITY.md、CONTRIBUTING.md、Issue/PR 模板

### Notes

- 本版本对应需求文档 v1.8 / M2-CONTRACTS.md v1.0 的 M2 里程碑增量（本仓库范围：
  es-ik 镜像与 compose override、benchmark 压测脚本、OpenAPI 契约同步）；
  `t_kb_ik_dict`/`retrieval_config` 等 Flyway V2 迁移脚本在 kb-rag-server 仓库，
  不在本仓库交付范围
- 本版本对应需求文档 v1.8 / M1-CONTRACTS.md v1.0 的 M1 里程碑交付
- 不含 schema 变更（本仓库不承载数据库 migration，Flyway 脚本在 kb-rag-server 仓库）
