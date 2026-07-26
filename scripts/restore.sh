#!/usr/bin/env bash
#
# kb-rag 恢复脚本
# Author: owlzhangfq@gmail.com
#
# 与 scripts/backup.sh 配对使用：读取一份 backup.sh 产出的 <备份目录>（形如
# ./backup/20260726T120000Z/，含 mysql/*.sql.gz + es-snapshot.json + minio/<bucket>/
# + manifest.json），按 MySQL -> Elasticsearch -> MinIO 顺序原地恢复，覆盖当前环境的
# 全部数据（docs/M6-CONTRACTS.md §3 / §4 验收⑥「备份-删库-恢复-检索可用」演练用）。
#
# 恢复顺序固定为 MySQL -> ES -> MinIO 的原因：
#   - MySQL 是事实源（chunk 内容/文档元数据），先恢复它保证后续校验有依据；
#   - ES 快照恢复前必须先清掉当前 kb_* 索引，否则 _restore 会因索引已存在而报错；
#   - MinIO 最后恢复：原件/解析产物只在人工重新下载原文件时才会被访问，顺序对
#     检索链路可用性没有影响，放在最后不阻塞前两步验证。
#
# 用法：
#   ./scripts/restore.sh ./backup/20260726T120000Z            # 交互确认后恢复
#   ./scripts/restore.sh ./backup/20260726T120000Z --yes       # 跳过交互确认（自动化演练用）
#
# 退出码：0 = 三段全部恢复成功；1 = 前置校验失败或某一段恢复失败（fast-fail，不继续下一段）。

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

log_info()  { printf '[INFO]  %s\n' "$1"; }
log_error() { printf '[ERROR] %s\n' "$1" >&2; }

# ---------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------
BACKUP_ARG=""
ASSUME_YES=0
for arg in "$@"; do
  case "${arg}" in
    --yes|-y) ASSUME_YES=1 ;;
    -*)
      log_error "unknown option: ${arg}"
      exit 1
      ;;
    *) BACKUP_ARG="${arg}" ;;
  esac
done

if [[ -z "${BACKUP_ARG}" ]]; then
  log_error "usage: $0 <backup_dir> [--yes]   e.g. $0 ./backup/20260726T120000Z"
  exit 1
fi

# ---- 可配置项（与 scripts/backup.sh 保持同源变量命名）------------------------
MYSQL_CONTAINER="${MYSQL_CONTAINER:-kb-rag-mysql}"
MYSQL_DB="${MYSQL_DB:-kb_rag}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-CHANGE_ME_mysql_root}"

ES_CONTAINER="${ES_CONTAINER:-kb-rag-es}"
ES_URI="${ES_URI:-http://127.0.0.1:9200}"
ES_SNAPSHOT_REPO_PATH="${ES_SNAPSHOT_REPO_PATH:-/usr/share/elasticsearch/snapshot_repo}"
# ES 索引删除 pattern：只清业务索引，绝不波及 ES 系统索引
ES_DELETE_INDEX_PATTERN="${ES_DELETE_INDEX_PATTERN:-kb_*}"
ES_RESTORE_WAIT_TIMEOUT_SEC="${ES_RESTORE_WAIT_TIMEOUT_SEC:-1800}"

MINIO_CONTAINER="${MINIO_CONTAINER:-kb-rag-minio}"
MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-CHANGE_ME_minio_access_key}"
MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-CHANGE_ME_minio_secret_key}"
MC_IMAGE="${MC_IMAGE:-minio/mc:RELEASE.2024-05-09T17-04-24Z}"
KB_RAG_NETWORK="${KB_RAG_NETWORK:-kb-rag-net}"

# ---------------------------------------------------------------------
# 唯一防御式检查点：命令依赖 + 备份目录完整性 + 三个基础设施容器是否在跑
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

BACKUP_ROOT="$(cd "${BACKUP_ARG}" 2>/dev/null && pwd)"
if [[ -z "${BACKUP_ROOT}" ]]; then
  log_error "backup dir not found: ${BACKUP_ARG}"
  exit 1
fi
if [[ ! -f "${BACKUP_ROOT}/manifest.json" ]]; then
  log_error "manifest.json not found under ${BACKUP_ROOT} — is this a directory produced by scripts/backup.sh?"
  exit 1
fi
log_info "using backup: ${BACKUP_ROOT}"

MYSQL_DUMP_FILE="$(ls "${BACKUP_ROOT}"/mysql/*.sql.gz 2>/dev/null | head -1)"
if [[ -z "${MYSQL_DUMP_FILE}" || ! -s "${MYSQL_DUMP_FILE}" ]]; then
  log_error "no non-empty mysql/*.sql.gz found under ${BACKUP_ROOT}, cannot restore MySQL from this backup"
  exit 1
fi

ES_SNAPSHOT_FILE="${BACKUP_ROOT}/es-snapshot.json"
if [[ ! -s "${ES_SNAPSHOT_FILE}" ]]; then
  log_error "es-snapshot.json not found under ${BACKUP_ROOT}, cannot restore Elasticsearch from this backup"
  exit 1
fi

MINIO_BUCKET_DIR="$(find "${BACKUP_ROOT}/minio" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1)"
if [[ -z "${MINIO_BUCKET_DIR}" ]]; then
  log_error "no bucket directory found under ${BACKUP_ROOT}/minio, cannot restore MinIO from this backup"
  exit 1
fi
MINIO_BUCKET="$(basename "${MINIO_BUCKET_DIR}")"

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

# ---------------------------------------------------------------------
# 交互确认（--yes 跳过，供演练/CI 自动化使用）
# ---------------------------------------------------------------------
if [[ "${ASSUME_YES}" != "1" ]]; then
  echo "------------------------------------------------------------"
  echo "This will OVERWRITE current data:"
  echo "  - DROP and reload MySQL database [${MYSQL_DB}] from ${MYSQL_DUMP_FILE}"
  echo "  - DELETE all ES indices matching [${ES_DELETE_INDEX_PATTERN}] then restore snapshot from ${ES_SNAPSHOT_FILE}"
  echo "  - Mirror-restore MinIO bucket [${MINIO_BUCKET}] from ${MINIO_BUCKET_DIR} (removes files not in backup)"
  echo "------------------------------------------------------------"
  read -r -p "Continue? [y/N] " reply
  case "${reply}" in
    y|Y|yes|YES) ;;
    *) log_info "aborted by user"; exit 0 ;;
  esac
fi

# ---------------------------------------------------------------------
# 1. MySQL：DROP DATABASE 后从全量 dump 重建（dump 内含 CREATE DATABASE，
#    这里显式先 DROP 是为了清掉 dump 之后新增、dump 里没有的表，做到真正的
#    "恢复到备份时点"而不是"合并当前状态"）
# ---------------------------------------------------------------------
restore_mysql() {
  log_info "dropping database [${MYSQL_DB}] before reload"
  if ! docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${MYSQL_CONTAINER}" \
    mysql -uroot -e "DROP DATABASE IF EXISTS \`${MYSQL_DB}\`;"; then
    log_error "failed to drop database ${MYSQL_DB} before restore"
    return 1
  fi

  log_info "loading ${MYSQL_DUMP_FILE} into MySQL (this streams the full dump, may take a while for large datasets)"
  if ! gunzip -c "${MYSQL_DUMP_FILE}" | docker exec -i -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${MYSQL_CONTAINER}" \
    mysql -uroot; then
    log_error "mysql restore failed while streaming ${MYSQL_DUMP_FILE}"
    return 1
  fi

  local chunk_count
  chunk_count="$(docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${MYSQL_CONTAINER}" \
    mysql -uroot -N -B -e "SELECT COUNT(*) FROM \`${MYSQL_DB}\`.t_kb_chunk;" 2>/dev/null)"
  log_info "mysql restore done, t_kb_chunk row count = ${chunk_count:-unknown}"
}

# ---------------------------------------------------------------------
# 2. Elasticsearch：先删现有 kb_* 索引（未来任何新建索引都会保留，restore 只
#    覆盖同 pattern 命中的），仓库不存在则重新注册（应对 ES 数据卷被清空的场景），
#    再从快照原地恢复
# ---------------------------------------------------------------------
json_field() {
  # 提取形如 "field": "value" 的字符串字段（es-snapshot.json 是本仓库 backup.sh
  # 自己生成的扁平结构，不含嵌套，正则足够，不必引入 jq）
  local field="$1" file="$2"
  grep -o "\"${field}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "${file}" | head -1 | sed -E 's/.*:[[:space:]]*"(.*)"/\1/'
}

restore_es() {
  local repo snapshot indices_pattern
  repo="$(json_field repo "${ES_SNAPSHOT_FILE}")"
  snapshot="$(json_field snapshot "${ES_SNAPSHOT_FILE}")"
  indices_pattern="$(json_field indices_pattern "${ES_SNAPSHOT_FILE}")"
  if [[ -z "${repo}" || -z "${snapshot}" ]]; then
    log_error "failed to parse repo/snapshot name from ${ES_SNAPSHOT_FILE}"
    return 1
  fi
  indices_pattern="${indices_pattern:-kb_*}"
  log_info "restoring from ES snapshot repo=${repo} snapshot=${snapshot} indices=${indices_pattern}"

  log_info "deleting current ES indices matching [${ES_DELETE_INDEX_PATTERN}]"
  local delete_http_code
  delete_http_code="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "${ES_URI}/${ES_DELETE_INDEX_PATTERN}")"
  if [[ "${delete_http_code}" != "200" && "${delete_http_code}" != "404" ]]; then
    log_error "failed to delete existing indices [${ES_DELETE_INDEX_PATTERN}] (http ${delete_http_code})"
    return 1
  fi

  # 仓库可能因 ES 数据卷被清空而丢失注册信息（fs 仓库数据在 snapshot_repo 卷里还在，
  # 但 ES 集群内存中的仓库注册表已丢），幂等重新注册一次
  local repo_http_code
  repo_http_code="$(curl -s -o /dev/null -w '%{http_code}' "${ES_URI}/_snapshot/${repo}")"
  if [[ "${repo_http_code}" != "200" ]]; then
    log_info "re-registering ES snapshot repository [${repo}]"
    docker exec -u root "${ES_CONTAINER}" \
      chown -R elasticsearch:elasticsearch "${ES_SNAPSHOT_REPO_PATH}" >/dev/null 2>&1 || true
    curl -s -o /dev/null -X PUT "${ES_URI}/_snapshot/${repo}" \
      -H 'Content-Type: application/json' \
      -d "{\"type\":\"fs\",\"settings\":{\"location\":\"${ES_SNAPSHOT_REPO_PATH}\"}}"
  fi

  local restore_resp="${BACKUP_ROOT}/es-restore-response.json"
  local restore_http_code
  restore_http_code="$(curl -s -o "${restore_resp}" -w '%{http_code}' \
    --max-time "${ES_RESTORE_WAIT_TIMEOUT_SEC}" \
    -X POST "${ES_URI}/_snapshot/${repo}/${snapshot}/_restore?wait_for_completion=true" \
    -H 'Content-Type: application/json' \
    -d "{\"indices\":\"${indices_pattern}\",\"include_global_state\":false}")"

  if [[ "${restore_http_code}" != "200" ]]; then
    log_error "ES snapshot restore failed (http ${restore_http_code}), see ${restore_resp}"
    return 1
  fi

  local restored_indices
  restored_indices="$(curl -s "${ES_URI}/_cat/indices/${indices_pattern}?h=index" | tr '\n' ' ')"
  if [[ -z "${restored_indices// /}" ]]; then
    log_error "ES restore reported success but no indices matching [${indices_pattern}] exist afterward"
    return 1
  fi
  log_info "es restore done, indices present: ${restored_indices}"
}

# ---------------------------------------------------------------------
# 3. MinIO：mirror --remove 做逆向镜像（本地备份 -> 桶），bucket 不存在则先建
# ---------------------------------------------------------------------
restore_minio() {
  log_info "mirroring ${MINIO_BUCKET_DIR} back into MinIO bucket [${MINIO_BUCKET}]"
  if ! docker run --rm \
    --network "${KB_RAG_NETWORK}" \
    -v "$(cd "${BACKUP_ROOT}/minio" && pwd):/backup" \
    -e MC_HOST_dst="http://${MINIO_ACCESS_KEY}:${MINIO_SECRET_KEY}@${MINIO_CONTAINER}:9000" \
    --entrypoint /bin/sh \
    "${MC_IMAGE}" \
    -c "mc mb --ignore-existing dst/${MINIO_BUCKET} && mc mirror --overwrite --remove /backup/${MINIO_BUCKET} dst/${MINIO_BUCKET}" \
    > "${BACKUP_ROOT}/minio-restore.log" 2>&1; then
    log_error "minio restore failed, see ${BACKUP_ROOT}/minio-restore.log"
    return 1
  fi
  log_info "minio restore done"
}

restore_mysql || { log_error "restore aborted at MySQL stage"; exit 1; }
restore_es    || { log_error "restore aborted at Elasticsearch stage"; exit 1; }
restore_minio || { log_error "restore aborted at MinIO stage"; exit 1; }

echo "------------------------------------------------------------"
log_info "restore finished, verify with:"
log_info "  docker exec ${MYSQL_CONTAINER} mysql -uroot -p<pwd> -e \"SELECT COUNT(*) FROM ${MYSQL_DB}.t_kb_chunk;\""
log_info "  curl ${ES_URI}/_cat/indices/kb_*?v"
log_info "  KB_ID=<kb_id> TOKEN=<token> ./scripts/benchmark.sh   # 或直接调用 search 接口，确认召回非空"
exit 0
