#!/usr/bin/env bash
#
# kb-rag 部署前置检查（preflight）
#
# 职责：在 `docker compose up` 之前给出一次性、全链路唯一的防御式校验入口——
#   1. docker / docker compose 是否就绪
#   2. 宿主机可用内存是否满足所选档位（lite 8GB / full 16GB，需求文档 §5 资源要求矩阵）
#   3. 契约端口（§1）是否已被占用
#   4. .env 是否仍在使用仓库自带的占位口令（CHANGE_ME_*），生产前必须替换
#
# 用法：
#   ./scripts/preflight.sh            # 使用 .env 中 DEPLOY_PROFILE（默认 lite）
#   ./scripts/preflight.sh full       # 显式指定档位：lite | full
#
# 退出码：0 = 全部通过；非 0 = 存在阻断项。
# 日志规范：仅 info/error 两级，日志文本用英文，说明性注释用中文。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${REPO_ROOT}/.env"

FAIL_COUNT=0

log_info()  { printf '[INFO]  %s\n' "$1"; }
log_error() { printf '[ERROR] %s\n' "$1" >&2; FAIL_COUNT=$((FAIL_COUNT + 1)); }

# ---------------------------------------------------------------------
# 0. 加载 .env（若存在），未提供则仅用 .env.example 默认值做只读校验
# ---------------------------------------------------------------------
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
  log_info "loaded env file: ${ENV_FILE}"
else
  log_info ".env not found, checking with .env.example defaults only; run: cp .env.example .env"
fi

DEPLOY_PROFILE="${1:-${DEPLOY_PROFILE:-lite}}"
case "${DEPLOY_PROFILE}" in
  lite|full) ;;
  *)
    log_error "unknown profile DEPLOY_PROFILE=${DEPLOY_PROFILE}, only lite|full supported"
    exit 1
    ;;
esac
log_info "checking profile: ${DEPLOY_PROFILE}"

# ---------------------------------------------------------------------
# 1. docker / docker compose
# ---------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  log_error "docker executable not found, install Docker Desktop / Docker Engine first"
else
  log_info "docker available: $(docker --version)"
  if docker info >/dev/null 2>&1; then
    log_info "docker daemon is running"
  else
    log_error "docker daemon is not running, start Docker Desktop / dockerd first"
  fi

  if docker compose version >/dev/null 2>&1; then
    log_info "docker compose plugin available: $(docker compose version --short 2>/dev/null || echo unknown)"
  else
    log_error "docker compose (v2) plugin not found, upgrade Docker Desktop or install the plugin"
  fi
fi

# ---------------------------------------------------------------------
# 2. 内存
# ---------------------------------------------------------------------
get_total_mem_gb() {
  local os
  os="$(uname -s)"
  if [[ "${os}" == "Darwin" ]]; then
    # macOS：hw.memsize 单位字节
    local bytes
    bytes="$(sysctl -n hw.memsize 2>/dev/null || echo 0)"
    echo $(( bytes / 1024 / 1024 / 1024 ))
  elif [[ -r /proc/meminfo ]]; then
    # Linux：MemTotal 单位 kB
    local kb
    kb="$(awk '/MemTotal/ {print $2}' /proc/meminfo)"
    echo $(( kb / 1024 / 1024 ))
  else
    echo 0
  fi
}

REQUIRED_GB=8
[[ "${DEPLOY_PROFILE}" == "full" ]] && REQUIRED_GB=16

TOTAL_GB="$(get_total_mem_gb)"
if [[ "${TOTAL_GB}" -eq 0 ]]; then
  log_info "cannot detect host memory on this OS, please manually confirm >= ${REQUIRED_GB}GB"
elif [[ "${TOTAL_GB}" -lt "${REQUIRED_GB}" ]]; then
  log_error "host memory ~${TOTAL_GB}GB is below ${DEPLOY_PROFILE} profile requirement (${REQUIRED_GB}GB)"
else
  log_info "host memory ~${TOTAL_GB}GB satisfies ${DEPLOY_PROFILE} profile requirement (>= ${REQUIRED_GB}GB)"
fi

# 注：容器实际可用内存还受 Docker Desktop 资源分配上限约束（macOS/Windows），
# 请在 Docker Desktop 设置中同步调高；这里只校验宿主机物理内存。

# ---------------------------------------------------------------------
# 3. 端口占用（契约 §1 端口 + compose 专用端口）
# ---------------------------------------------------------------------
port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 "${port}" >/dev/null 2>&1
  else
    return 1
  fi
}

check_port() {
  local name="$1" port="$2"
  [[ -z "${port}" ]] && return
  if port_in_use "${port}"; then
    log_error "port ${port} (${name}) is already in use, free it or change it in .env"
  else
    log_info "port ${port} (${name}) is free"
  fi
}

check_port "MySQL"           "${MYSQL_PORT:-13306}"
check_port "Elasticsearch"   "${ES_PORT:-9200}"
check_port "MinIO API"       "${MINIO_PORT:-9000}"
check_port "MinIO Console"   "${MINIO_CONSOLE_PORT:-9001}"

if [[ "${DEPLOY_PROFILE}" == "full" ]]; then
  check_port "Qdrant"          "${QDRANT_PORT:-6333}"
  check_port "Qdrant gRPC"     "${QDRANT_GRPC_PORT:-6334}"
  check_port "Redis(optional)" "${REDIS_PORT:-6379}"
fi

# ---------------------------------------------------------------------
# 4. 占位口令检测（唯一的防御式拦截点，覆盖全部密码类变量）
# ---------------------------------------------------------------------
check_secret() {
  local var_name="$1" value="$2"
  if [[ "${value}" == CHANGE_ME_* ]]; then
    log_error "${var_name} still uses the placeholder value (${value}), replace it in .env before deploying"
  else
    log_info "${var_name} has been customized"
  fi
}

check_secret "MYSQL_ROOT_PASSWORD"       "${MYSQL_ROOT_PASSWORD:-CHANGE_ME_mysql_root}"
check_secret "MYSQL_PASSWORD"            "${MYSQL_PASSWORD:-CHANGE_ME_mysql_password}"
check_secret "MINIO_ACCESS_KEY"          "${MINIO_ACCESS_KEY:-CHANGE_ME_minio_access_key}"
check_secret "MINIO_SECRET_KEY"          "${MINIO_SECRET_KEY:-CHANGE_ME_minio_secret_key}"
if [[ "${DEPLOY_PROFILE}" == "full" ]]; then
  # Qdrant 的 API Key 必填：容器只要收到 QDRANT__SERVICE__API_KEY 这个变量就会开启鉴权，
  # 留空反而变成"鉴权已开但没有可用密钥"，届时应用侧每次读写向量都会拿到 401。
  check_secret "QDRANT_API_KEY" "${QDRANT_API_KEY:-CHANGE_ME_qdrant_api_key}"
fi

# ---------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------
echo "------------------------------------------------------------"
if [[ "${FAIL_COUNT}" -gt 0 ]]; then
  log_error "preflight failed with ${FAIL_COUNT} blocking issue(s), fix them and re-run"
  exit 1
fi
log_info "preflight passed, safe to run docker compose up -d"
exit 0
