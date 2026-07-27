# kb-rag es-ik 镜像
#
# 在官方 Elasticsearch 基础镜像上安装 analysis-ik 中文分词插件（infinilabs 官方发布包），
# 用于替换 docker-compose.lite.yml / docker-compose.yml 中默认的 elasticsearch 服务，
# 见 docs/M2-CONTRACTS.md §3 与 README「启用 ik」章节。
#
# 之所以在 deploy 仓库固化为可复现构建的 Dockerfile，而不是让运维手工
# `docker exec ... elasticsearch-plugin install`：容器重建（升级镜像/迁移环境）时
# 手工安装的插件会丢失，版本也容易在不同环境间漂移；构建产物化后插件版本随镜像
# 一起被版本控制。
#
# 用法（与 docker-compose.es-ik.yml 配套，见该文件与 README）：
#   docker compose -f docker-compose.lite.yml -f docker-compose.es-ik.yml up -d --build

# ES_VERSION 需在 FROM 之前声明才能参数化基础镜像 tag；默认与仓库其余 compose 文件
# 固定使用的 elasticsearch:8.11.4 保持一致，升级基础镜像版本时同步改这里的默认值
ARG ES_VERSION=8.11.4
FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}

# analysis-ik 插件版本；8.x 系列发布地址形如
# https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-{version}.zip
# 插件版本需与 ES_VERSION 主版本一致（ik 官方按 ES 版本号发布对应插件包），因此默认值
# 跟随 ES_VERSION 一起为 8.11.4；如需固定安装其他插件版本可在 docker build 时单独覆盖
# --build-arg IK_VERSION=<version>
ARG IK_VERSION=8.11.4
ARG IK_DOWNLOAD_URL=https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-${IK_VERSION}.zip

# --batch：非交互安装，自动同意插件所需的安全策略授权（读文件系统等），
# 是 elasticsearch-plugin 官方支持的参数，不属于绕过校验的防御式编程简化
RUN bin/elasticsearch-plugin install --batch "${IK_DOWNLOAD_URL}"
