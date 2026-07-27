#!/usr/bin/env bash
#
# kb-rag 备份脚本
# Author: owlzhangfq@gmail.com
#
# 覆盖 docs/M6-CONTRACTS.md §3 与需求文档 §5「备份与恢复」：每次运行产出一份完整、
# 自描述的备份，落地 ${BACKUP_DIR}/<UTC 时间戳>/，包含：
#   - mysql/kb_rag.sql.gz          mysqldump 全量导出（--single-transaction，非增量）
#   - es-snapshot.json             本次 ES 快照的仓库名/快照名/覆盖索引 pattern 等元信息
#   - minio/<bucket>/...           MinIO kb-files 桶镜像（mc mirror 全量同步）
#   - manifest.json                三段各自的 status（ok/failed）与体积，供恢复前核对
#
# ES 快照说明：物理快照数据不逐份复制进时间戳目录（ES fs 仓库本身按段去重、增量存储，
# 强行复制等于放弃这个特性），而是持续写入 docker-compose.lite.yml 挂载的共享仓库目录
# ./backup/es-repo；每次 backup.sh 只新增一个快照名。对 ./backup 整个目录做离线归档
# （rsync/异地盘）即同时归档了 mysqldump + minio 镜像 + es-repo 全部快照——这是本脚本
# 备份目录必须与 compose 的 path.repo bind mount 保持同一路径的原因。
#
# RPO 目标 <=24h：建议用 cron 每日调度本脚本（示例见文末）。
# RTO 无硬指标，但每次演练需把恢复耗时记入 docs/backup-restore.md（M6 验收⑥）。
#
# 用法：
#   ./scripts/backup.sh                          # 使用 .env 中的配置
#   BACKUP_DIR=/mnt/backup ./scripts/backup.sh   # 临时覆盖备份根目录
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

# ---- 可配置项（均可由 .env 或环境变量覆盖，无魔法值散落在流程里）--------------
MYSQL_CONTAINER="${MYSQL_CONTAINER:-kb-rag-mysql}"
MYSQL_DB="${MYSQL_DB:-kb_rag}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-CHANGE_ME_mysql_root}"

ES_CONTAINER="${ES_CONTAINER:-kb-rag-es}"
ES_URI="${ES_URI:-http://127.0.0.1:9200}"
# 必须与 docker-compose.lite.yml 里 elasticsearch 服务的 path.repo 一致
ES_SNAPSHOT_REPO_PATH="${ES_SNAPSHOT_REPO_PATH:-/usr/share/elasticsearch/snapshot_repo}"
ES_SNAPSHOT_REPO="${ES_SNAPSHOT_REPO:-kb_rag_repo}"
# 只快照业务索引（kb_* 三段命名），不覆盖 ES 系统索引
ES_SNAPSHOT_INDICES_PATTERN="${ES_SNAPSHOT_INDICES_PATTERN:-kb_*}"
# 单次快照等待完成的超时秒数（curl --max-time），10 万分片规模默认 30 分钟足够
ES_SNAPSHOT_WAIT_TIMEOUT_SEC="${ES_SNAPSHOT_WAIT_TIMEOUT_SEC:-1800}"

MINIO_CONTAINER="${MINIO_CONTAINER:-kb-rag-minio}"
MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-CHANGE_ME_minio_access_key}"
MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-CHANGE_ME_minio_secret_key}"
MINIO_BUCKET="${MINIO_BUCKET:-kb-files}"
# mc 客户端镜像版本固定（与 compose minio server RELEASE.2024-05-10 同期，避免协议漂移）
MC_IMAGE="${MC_IMAGE:-minio/mc:RELEASE.2024-05-09T17-04-24Z}"
# docker-compose.lite.yml 里固定的用户自定义网络名，backup 用 mc 容器需加入才能按服务名寻址 MinIO
KB_RAG_NETWORK="${KB_RAG_NETWORK:-kb-rag-net}"

BACKUP_DIR="${BACKUP_DIR:-./backup}"
BACKUP_KEEP_COUNT="${BACKUP_KEEP_COUNT:-7}"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
# ES 快照名要求纯小写，复用同一时间戳保证一次 backup.sh 运行内三段产物一一对应
ES_SNAPSHOT_NAME="kb_rag_$(printf '%s' "${TIMESTAMP}" | tr '[:upper:]' '[:lower:]')"
RUN_DIR="${BACKUP_DIR}/${TIMESTAMP}"

log_info()  { printf '[INFO]  %s\n' "$1"; }
log_error() { printf '[ERROR] %s\n' "$1" >&2; }

# ---------------------------------------------------------------------
# 唯一防御式检查点：命令依赖 + 三个基础设施容器是否在跑，其余环节 fast-fail
# ---------------------------------------------------------------------
require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log_error "required command not found: $1"
    exit 1
  fi
}
require_cmd docker
require_cmd curl
require_cmd gzip

container_running() {
  docker ps --format '{{.Names}}' | grep -qx "$1"
}

check_container() {
  local name="$1" label="$2"
  if ! container_running "${name}"; then
    log_error "${label} container not running: ${name} — start it first: docker compose -f docker-compose.lite.yml up -d (or docker-compose.yml for full mode)"
    exit 1
  fi
  log_info "${label} container is running: ${name}"
}
check_container "${MYSQL_CONTAINER}" "MySQL"
check_container "${ES_CONTAINER}"    "Elasticsearch"
check_container "${MINIO_CONTAINER}" "MinIO"

mkdir -p "${RUN_DIR}/mysql" "${RUN_DIR}/minio"

# ---------------------------------------------------------------------
# 体积统计辅助：跨 macOS/Linux 的字节数（不依赖 stat 参数差异）
# ---------------------------------------------------------------------
file_size_bytes() {
  wc -c < "$1" 2>/dev/null | tr -d ' '
}
dir_size_bytes() {
  # du -sk 输出 KB 块数，统一换算为字节（近似值，足够 manifest 展示用）
  local kb
  kb="$(du -sk "$1" 2>/dev/null | awk '{print $1}')"
  echo $(( ${kb:-0} * 1024 ))
}

MYSQL_STATUS="failed"; MYSQL_SIZE=0; MYSQL_REL_PATH="mysql/${MYSQL_DB}.sql.gz"
ES_STATUS="failed"; ES_SIZE=0
MINIO_STATUS="failed"; MINIO_SIZE=0; MINIO_REL_PATH="minio/${MINIO_BUCKET}"

# ---------------------------------------------------------------------
# 1. MySQL 全量备份（--single-transaction：InnoDB 一致性快照，不锁表）
# ---------------------------------------------------------------------
backup_mysql() {
  local out_file="${RUN_DIR}/${MYSQL_REL_PATH}"
  log_info "dumping MySQL database [${MYSQL_DB}] to ${out_file}"

  if ! docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${MYSQL_CONTAINER}" \
    mysqldump -uroot --single-transaction --routines --triggers --databases "${MYSQL_DB}" \
    2> "${RUN_DIR}/mysql/mysqldump.err" | gzip -c > "${out_file}"; then
    log_error "mysqldump failed, see ${RUN_DIR}/mysql/mysqldump.err"
    return 1
  fi

  if [[ ! -s "${out_file}" ]]; then
    log_error "mysqldump produced empty file: ${out_file}"
    return 1
  fi
  MYSQL_SIZE="$(file_size_bytes "${out_file}")"
  MYSQL_STATUS="ok"
  log_info "mysql backup done: ${out_file} (${MYSQL_SIZE} bytes)"
}

# ---------------------------------------------------------------------
# 2. Elasticsearch 快照（_snapshot API）：仓库不存在则先注册，
#    wait_for_completion 阻塞到快照落盘，随后读 _status 拿体积写进 manifest
# ---------------------------------------------------------------------
ensure_es_repo() {
  local repo_http_code
  repo_http_code="$(curl -s -o /dev/null -w '%{http_code}' "${ES_URI}/_snapshot/${ES_SNAPSHOT_REPO}")"
  if [[ "${repo_http_code}" == "200" ]]; then
    log_info "ES snapshot repository already registered: ${ES_SNAPSHOT_REPO}"
    return 0
  fi

  log_info "registering ES snapshot repository [${ES_SNAPSHOT_REPO}] at ${ES_SNAPSHOT_REPO_PATH}"
  # fs 仓库的 location 必须落在 ES path.repo 白名单内（docker-compose.lite.yml 已配置），
  # 目录属主可能是 root（bind mount 首次创建默认属主），显式修正为 elasticsearch 运行用户，
  # 否则快照写入会因权限被拒（repository_verification_exception）
  docker exec -u root "${ES_CONTAINER}" \
    chown -R elasticsearch:elasticsearch "${ES_SNAPSHOT_REPO_PATH}" >/dev/null 2>&1 || true

  local register_http_code
  register_http_code="$(curl -s -o "${RUN_DIR}/es-repo-register.json" -w '%{http_code}' \
    -X PUT "${ES_URI}/_snapshot/${ES_SNAPSHOT_REPO}" \
    -H 'Content-Type: application/json' \
    -d "{\"type\":\"fs\",\"settings\":{\"location\":\"${ES_SNAPSHOT_REPO_PATH}\"}}")"
  if [[ "${register_http_code}" != "200" ]]; then
    log_error "failed to register ES snapshot repository (http ${register_http_code}), see ${RUN_DIR}/es-repo-register.json"
    return 1
  fi
  log_info "ES snapshot repository registered: ${ES_SNAPSHOT_REPO}"
}

backup_es_snapshot() {
  ensure_es_repo || return 1

  local snapshot_resp="${RUN_DIR}/es-snapshot-create.json"
  log_info "creating ES snapshot [${ES_SNAPSHOT_NAME}] over indices matching '${ES_SNAPSHOT_INDICES_PATTERN}' (this blocks until done)"
  local create_http_code
  create_http_code="$(curl -s -o "${snapshot_resp}" -w '%{http_code}' \
    --max-time "${ES_SNAPSHOT_WAIT_TIMEOUT_SEC}" \
    -X PUT "${ES_URI}/_snapshot/${ES_SNAPSHOT_REPO}/${ES_SNAPSHOT_NAME}?wait_for_completion=true" \
    -H 'Content-Type: application/json' \
    -d "{\"indices\":\"${ES_SNAPSHOT_INDICES_PATTERN}\",\"ignore_unavailable\":true,\"include_global_state\":false}")"

  if [[ "${create_http_code}" != "200" ]]; then
    log_error "ES snapshot request failed (http ${create_http_code}), see ${snapshot_resp}"
    return 1
  fi
  if ! grep -q '"state":"SUCCESS"' "${snapshot_resp}"; then
    log_error "ES snapshot did not report SUCCESS, see ${snapshot_resp}"
    return 1
  fi

  local status_resp="${RUN_DIR}/es-snapshot-status.json"
  curl -s -o "${status_resp}" "${ES_URI}/_snapshot/${ES_SNAPSHOT_REPO}/${ES_SNAPSHOT_NAME}/_status"
  # 只取 total 汇总段的 size_in_bytes，粗粒度正则足够（不引入 jq，沿用本仓库脚本风格）
  ES_SIZE="$(grep -o '"total":{[^}]*"size_in_bytes":[0-9]*' "${status_resp}" | grep -o '[0-9]*$' | head -1)"
  ES_SIZE="${ES_SIZE:-0}"

  cat > "${RUN_DIR}/es-snapshot.json" <<EOF
{
  "repo": "${ES_SNAPSHOT_REPO}",
  "repo_path": "${ES_SNAPSHOT_REPO_PATH}",
  "snapshot": "${ES_SNAPSHOT_NAME}",
  "indices_pattern": "${ES_SNAPSHOT_INDICES_PATTERN}",
  "size_bytes": ${ES_SIZE}
}
EOF

  ES_STATUS="ok"
  log_info "es snapshot done: repo=${ES_SNAPSHOT_REPO} snapshot=${ES_SNAPSHOT_NAME} (${ES_SIZE} bytes)"
}

# ---------------------------------------------------------------------
# 3. MinIO 备份：一次性 mc 容器加入 kb-rag-net，按服务名寻址，
#    mirror 整个 kb-files 桶到本次备份目录（等价全量同步，非增量 rsync）
# ---------------------------------------------------------------------
backup_minio() {
  local out_dir="${RUN_DIR}/${MINIO_REL_PATH}"
  mkdir -p "${out_dir}"
  log_info "mirroring MinIO bucket [${MINIO_BUCKET}] to ${out_dir}"

  if ! docker run --rm \
    --network "${KB_RAG_NETWORK}" \
    -v "$(cd "${RUN_DIR}/minio" && pwd):/backup" \
    -e MC_HOST_src="http://${MINIO_ACCESS_KEY}:${MINIO_SECRET_KEY}@${MINIO_CONTAINER}:9000" \
    "${MC_IMAGE}" \
    mirror --overwrite --quiet "src/${MINIO_BUCKET}" "/backup/${MINIO_BUCKET}" \
    > "${RUN_DIR}/minio/mc-mirror.log" 2>&1; then
    log_error "minio mirror failed, see ${RUN_DIR}/minio/mc-mirror.log (bucket not created yet is a common cause on a fresh deployment)"
    return 1
  fi

  MINIO_SIZE="$(dir_size_bytes "${out_dir}")"
  MINIO_STATUS="ok"
  log_info "minio backup done: ${out_dir} (${MINIO_SIZE} bytes)"
}

# ---------------------------------------------------------------------
# 4. manifest.json：三段状态 + 体积，恢复前先读它核对完整性
# ---------------------------------------------------------------------
write_manifest() {
  cat > "${RUN_DIR}/manifest.json" <<EOF
{
  "backup_id": "${TIMESTAMP}",
  "created_at_utc": "${TIMESTAMP}",
  "components": {
    "mysql": {
      "status": "${MYSQL_STATUS}",
      "path": "${MYSQL_REL_PATH}",
      "size_bytes": ${MYSQL_SIZE},
      "database": "${MYSQL_DB}"
    },
    "elasticsearch": {
      "status": "${ES_STATUS}",
      "repo": "${ES_SNAPSHOT_REPO}",
      "snapshot": "${ES_SNAPSHOT_NAME}",
      "size_bytes": ${ES_SIZE}
    },
    "minio": {
      "status": "${MINIO_STATUS}",
      "path": "${MINIO_REL_PATH}",
      "size_bytes": ${MINIO_SIZE},
      "bucket": "${MINIO_BUCKET}"
    }
  }
}
EOF
  log_info "manifest written: ${RUN_DIR}/manifest.json"
}

# ---------------------------------------------------------------------
# 5. 轮转：只保留最近 BACKUP_KEEP_COUNT 份时间戳目录
# ---------------------------------------------------------------------
rotate() {
  local -a dirs
  dirs=()
  while IFS= read -r d; do dirs+=("$d"); done < <(ls -1dt "${BACKUP_DIR}"/*/ 2>/dev/null)

  local total="${#dirs[@]}"
  if (( total <= BACKUP_KEEP_COUNT )); then
    return
  fi
  local i
  for (( i = BACKUP_KEEP_COUNT; i < total; i++ )); do
    log_info "rotating out old backup: ${dirs[$i]}"
    rm -rf -- "${dirs[$i]}"
  done
}

FAILED=0
backup_mysql       || FAILED=1
backup_es_snapshot || FAILED=1
backup_minio       || FAILED=1

write_manifest
rotate

if (( FAILED != 0 )); then
  log_error "backup finished with errors, inspect ${RUN_DIR}/manifest.json and per-component logs above"
  exit 1
fi

log_info "backup finished successfully: ${RUN_DIR} (keep_count=${BACKUP_KEEP_COUNT})"
exit 0
