#!/usr/bin/env bash
#
# kb-rag 备份脚本（骨架）
#
# 覆盖需求文档 §5「备份与恢复」：
#   - MySQL：mysqldump 全量导出 kb_rag 库（非增量，每次跑一份完整快照）
#   - MinIO：导出应用侧 kb-rag-minio 命名卷（原件 + 解析产物）的全量数据
#   - 两者各自按 BACKUP_KEEP_COUNT 保留份数轮转，超出的旧备份自动清理
#   - RPO 目标 <=24h：建议用 cron 每日调度本脚本（示例见文末注释）
#
# 恢复顺序（详见 README「备份与恢复」章节）：MySQL -> MinIO -> 触发"从事实源重建索引"。
#
# 说明：MinIO 数据落在 Docker 具名卷（kb_rag_minio_data），而非宿主机可直接访问的路径，
# 因此这里没有直接对宿主机目录跑 rsync，而是启动一次性 alpine 容器把卷内容 tar 流式导出到
# BACKUP_DIR——效果等价于全量同步，且不依赖宿主机上是否安装 rsync/是否为 Linux。
# 如果后续把 MinIO 数据目录改造为 bind mount，可将 backup_minio() 替换为真正的 `rsync -a`。
#
# 用法：
#   ./scripts/backup.sh                 # 使用 .env 中的配置
#   BACKUP_DIR=/mnt/backup ./scripts/backup.sh   # 临时覆盖备份目录
#
# crontab 示例（每日凌晨 2 点执行）：
#   0 2 * * * cd /path/to/kb-rag-deploy && ./scripts/backup.sh >> /var/log/kb-rag-backup.log 2>&1

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${REPO_ROOT}/.env"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

# ---- 可配置项（均可由 .env 或环境变量覆盖）--------------------------------
MYSQL_CONTAINER="${MYSQL_CONTAINER:-kb-rag-mysql}"
MYSQL_DB="${MYSQL_DB:-kb_rag}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-CHANGE_ME_mysql_root}"
MINIO_VOLUME="${MINIO_VOLUME:-kb_rag_minio_data}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_KEEP_COUNT="${BACKUP_KEEP_COUNT:-7}"

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
MYSQL_BACKUP_DIR="${BACKUP_DIR}/mysql"
MINIO_BACKUP_DIR="${BACKUP_DIR}/minio"

log_info()  { printf '[INFO]  %s\n' "$1"; }
log_error() { printf '[ERROR] %s\n' "$1" >&2; }

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log_error "required command not found: $1"
    exit 1
  fi
}

# 唯一防御式检查点：命令依赖 + 目标容器是否在跑，其余环节 fast-fail（`set -e` 语义交由调用处处理）
require_cmd docker
require_cmd gzip

mkdir -p "${MYSQL_BACKUP_DIR}" "${MINIO_BACKUP_DIR}"

# ---------------------------------------------------------------------
# 1. MySQL 全量备份
# ---------------------------------------------------------------------
backup_mysql() {
  if ! docker ps --format '{{.Names}}' | grep -qx "${MYSQL_CONTAINER}"; then
    log_error "mysql container not running: ${MYSQL_CONTAINER}"
    return 1
  fi

  local out_file="${MYSQL_BACKUP_DIR}/mysql_${MYSQL_DB}_${TIMESTAMP}.sql.gz"
  log_info "dumping MySQL database [${MYSQL_DB}] to ${out_file}"

  docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${MYSQL_CONTAINER}" \
    mysqldump -uroot --single-transaction --routines --triggers --databases "${MYSQL_DB}" \
    | gzip -c > "${out_file}"

  if [[ ! -s "${out_file}" ]]; then
    log_error "mysqldump produced empty file: ${out_file}"
    return 1
  fi
  log_info "mysql backup done: ${out_file} ($(du -h "${out_file}" | cut -f1))"
}

# ---------------------------------------------------------------------
# 2. MinIO 数据全量导出（具名卷 -> tar.gz），效果等价于目录级 rsync 全量同步
# ---------------------------------------------------------------------
backup_minio() {
  if ! docker volume inspect "${MINIO_VOLUME}" >/dev/null 2>&1; then
    log_error "minio volume not found: ${MINIO_VOLUME}"
    return 1
  fi

  local out_file="minio_${TIMESTAMP}.tar.gz"
  log_info "exporting MinIO volume [${MINIO_VOLUME}] to ${MINIO_BACKUP_DIR}/${out_file}"

  docker run --rm \
    -v "${MINIO_VOLUME}:/from:ro" \
    -v "$(cd "${MINIO_BACKUP_DIR}" && pwd):/to" \
    alpine:3.19 \
    sh -c "tar czf /to/${out_file} -C /from ."

  if [[ ! -s "${MINIO_BACKUP_DIR}/${out_file}" ]]; then
    log_error "minio export produced empty file: ${MINIO_BACKUP_DIR}/${out_file}"
    return 1
  fi
  log_info "minio backup done: ${MINIO_BACKUP_DIR}/${out_file} ($(du -h "${MINIO_BACKUP_DIR}/${out_file}" | cut -f1))"
}

# ---------------------------------------------------------------------
# 3. 轮转：每类备份仅保留最近 BACKUP_KEEP_COUNT 份
# ---------------------------------------------------------------------
rotate() {
  local dir="$1" pattern="$2"
  local -a files
  # macOS/BSD 与 GNU find 的 -printf 不通用，统一用 ls -t 按修改时间倒序
  files=()
  while IFS= read -r f; do files+=("$f"); done < <(ls -1t "${dir}"/${pattern} 2>/dev/null)

  local total="${#files[@]}"
  if (( total <= BACKUP_KEEP_COUNT )); then
    return
  fi

  local i
  for (( i = BACKUP_KEEP_COUNT; i < total; i++ )); do
    log_info "rotating out old backup: ${files[$i]}"
    rm -f -- "${files[$i]}"
  done
}

FAILED=0
backup_mysql || FAILED=1
backup_minio || FAILED=1

rotate "${MYSQL_BACKUP_DIR}" "mysql_*.sql.gz"
rotate "${MINIO_BACKUP_DIR}" "minio_*.tar.gz"

if (( FAILED != 0 )); then
  log_error "backup finished with errors, check log above"
  exit 1
fi

log_info "backup finished successfully, keep_count=${BACKUP_KEEP_COUNT}, dir=${BACKUP_DIR}"
exit 0
