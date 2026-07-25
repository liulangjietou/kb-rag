# Changelog

本文件记录 kb-rag-deploy 仓库的显著变更，格式遵循
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

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

- 本版本对应需求文档 v1.8 / M1-CONTRACTS.md v1.0 的 M1 里程碑交付
- 不含 schema 变更（本仓库不承载数据库 migration，Flyway 脚本在 kb-rag-server 仓库）
