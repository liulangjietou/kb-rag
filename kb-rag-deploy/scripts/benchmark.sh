#!/usr/bin/env bash
#
# kb-rag 检索压测脚本（scripts/benchmark.sh）
#
# 覆盖 docs/M2-CONTRACTS.md §6 与 §7 验收清单第 6 条：对指定知识库跑 N 次检索
# （默认 200 次、并发 5，query 从内置/自定义语料文件里轮换），统计 P50/P95/P99
# 延迟（毫秒）与错误数；验收口径：基础链路 P95 < 2s。
#
# 纯 bash + curl + awk + sort 实现，不引入 jq / python 等新依赖（与
# scripts/preflight.sh、scripts/backup.sh 的实现风格保持一致）。
#
# 用法：
#   KB_ID=kb_xxx TOKEN=xxx ./scripts/benchmark.sh                 # 其余参数用默认值 + 内置 10 条查询
#   BASE_URL=http://127.0.0.1:20000 KB_ID=kb_xxx TOKEN=xxx \
#     QUERY_FILE=./my_queries.txt TOTAL=500 CONCURRENCY=10 ./scripts/benchmark.sh
#
# 参数（均为环境变量，可选项已标注默认值）：
#   BASE_URL      kb-rag-server 地址，默认 http://127.0.0.1:20000
#   TOKEN         登录 Bearer Token（POST /api/v1/auth/login 获取）；留空则请求会
#                 收到 401，属于真实信号而非脚本 bug，不在此拦截
#   KB_ID         必填，压测目标知识库业务主键
#   QUERY_FILE    每行一条查询的 UTF-8 文本文件；不提供则使用脚本内置 10 条中文查询
#   TOTAL         压测总请求数，默认 200
#   CONCURRENCY   并发数（每一批同时发出的请求数），默认 5
#
# 退出码：0 = 全部请求 HTTP 2xx；1 = 前置校验失败（依赖缺失/必填参数缺失/参数非法）；
#         2 = 压测已完整跑完但存在请求错误（连接失败或非 2xx，含服务未启动的场景）。
#
# 日志规范：仅 info/error 两级，日志文本用英文，说明性注释用中文。

set -uo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:20000}"
TOKEN="${TOKEN:-}"
KB_ID="${KB_ID:-}"
QUERY_FILE="${QUERY_FILE:-}"
TOTAL="${TOTAL:-200}"
CONCURRENCY="${CONCURRENCY:-5}"

log_info()  { printf '[INFO]  %s\n' "$1"; }
log_error() { printf '[ERROR] %s\n' "$1" >&2; }

# ---------------------------------------------------------------------
# 唯一防御式检查点：命令依赖 + 必填参数 + 参数合法性，全部在压测开始前一次性拦截，
# 后续压测主流程 fast-fail（不重复校验）
# ---------------------------------------------------------------------
require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log_error "required command not found: $1"
    exit 1
  fi
}
require_cmd curl
require_cmd awk
require_cmd sort
require_cmd mktemp

if [[ -z "${KB_ID}" ]]; then
  log_error "KB_ID is required, e.g. KB_ID=kb_xxx ./scripts/benchmark.sh"
  exit 1
fi

if [[ -z "${TOKEN}" ]]; then
  log_info "TOKEN is empty, requests will hit auth and likely return 401 (this is a real signal, not a script bug)"
fi

if ! [[ "${TOTAL}" =~ ^[0-9]+$ ]] || [[ "${TOTAL}" -le 0 ]]; then
  log_error "TOTAL must be a positive integer, got: ${TOTAL}"
  exit 1
fi
if ! [[ "${CONCURRENCY}" =~ ^[0-9]+$ ]] || [[ "${CONCURRENCY}" -le 0 ]]; then
  log_error "CONCURRENCY must be a positive integer, got: ${CONCURRENCY}"
  exit 1
fi

# ---------------------------------------------------------------------
# 内置 10 条中文查询（QUERY_FILE 未提供时使用），覆盖常见知识库业务问法
# ---------------------------------------------------------------------
DEFAULT_QUERIES=(
  "如何重置账号密码"
  "退款流程需要多久"
  "发票怎么申请开具"
  "会员权益包含哪些内容"
  "订单发货后多久能收到"
  "如何联系人工客服"
  "优惠券使用规则说明"
  "账号被冻结怎么办"
  "如何注销账号"
  "售后服务政策是什么"
)

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kb-rag-benchmark.XXXXXX")"
trap 'rm -rf "${WORK_DIR}"' EXIT

QUERY_LIST_FILE="${WORK_DIR}/queries.txt"
if [[ -n "${QUERY_FILE}" ]]; then
  if [[ ! -f "${QUERY_FILE}" ]]; then
    log_error "QUERY_FILE not found: ${QUERY_FILE}"
    exit 1
  fi
  # 过滤空行，语料需为 UTF-8（curl -d 直接透传字节，不做转码）
  grep -v '^[[:space:]]*$' "${QUERY_FILE}" > "${QUERY_LIST_FILE}"
  if [[ ! -s "${QUERY_LIST_FILE}" ]]; then
    log_error "QUERY_FILE has no non-blank line: ${QUERY_FILE}"
    exit 1
  fi
else
  printf '%s\n' "${DEFAULT_QUERIES[@]}" > "${QUERY_LIST_FILE}"
fi

QUERY_COUNT="$(wc -l < "${QUERY_LIST_FILE}" | tr -d ' ')"
QUERY_SOURCE_DESC="built-in defaults"
[[ -n "${QUERY_FILE}" ]] && QUERY_SOURCE_DESC="${QUERY_FILE}"
log_info "loaded ${QUERY_COUNT} queries from ${QUERY_SOURCE_DESC}"

SEARCH_URL="${BASE_URL%/}/api/v1/kb/${KB_ID}/search"
log_info "target: POST ${SEARCH_URL}"
log_info "plan: total=${TOTAL} concurrency=${CONCURRENCY}"

# ---------------------------------------------------------------------
# 单次请求：取第 (idx % QUERY_COUNT) 行 query，记录 http_code 与 time_total(秒)，
# 结果写各自独立文件（并发写同一文件会导致行交错，独立文件后统一合并最简单可靠）
# ---------------------------------------------------------------------
run_one() {
  local idx="$1" out_file="$2"
  local line_no query escaped_query resp

  line_no=$(( (idx % QUERY_COUNT) + 1 ))
  query="$(sed -n "${line_no}p" "${QUERY_LIST_FILE}")"

  # JSON 字符串转义：仅处理反斜杠与双引号，压测语料默认不含控制字符
  escaped_query="${query//\\/\\\\}"
  escaped_query="${escaped_query//\"/\\\"}"

  # curl 请求失败（连接拒绝/超时/DNS 失败等）时 http_code 落 000，time_total 为
  # 失败前的耗时；-w 的输出与 curl 自身的非零退出码无关，即使服务不在也能拿到
  # 结构化结果，这是本脚本"服务未启动仍给出清晰报错"的关键
  resp="$(curl -s -o /dev/null --max-time 10 \
    -w '%{http_code} %{time_total}' \
    -X POST "${SEARCH_URL}" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{\"query\":\"${escaped_query}\"}" 2>/dev/null)"

  # 极端情况（curl 内部错误导致 -w 都没输出）兜底，避免下游统计因空行崩溃
  [[ -z "${resp}" ]] && resp="000 0"
  echo "${resp}" > "${out_file}"
}

RESULT_FILE="${WORK_DIR}/results.txt"
: > "${RESULT_FILE}"

# ---------------------------------------------------------------------
# 并发调度：分批执行——每批同时拉起 CONCURRENCY 个后台请求，等这批全部结束再拉
# 下一批。刻意不用 bash 4.3+ 的 `wait -n`（macOS 默认 /bin/bash 是 3.2），
# 保证脚本在 macOS/Linux 默认 bash 下都能跑
# ---------------------------------------------------------------------
i=0
while [[ "${i}" -lt "${TOTAL}" ]]; do
  batch_end=$(( i + CONCURRENCY ))
  [[ "${batch_end}" -gt "${TOTAL}" ]] && batch_end="${TOTAL}"

  j="${i}"
  while [[ "${j}" -lt "${batch_end}" ]]; do
    run_one "${j}" "${WORK_DIR}/res_${j}.txt" &
    j=$(( j + 1 ))
  done
  wait

  i="${batch_end}"
done

cat "${WORK_DIR}"/res_*.txt > "${RESULT_FILE}" 2>/dev/null

ACTUAL_LINES="$(wc -l < "${RESULT_FILE}" | tr -d ' ')"
if [[ "${ACTUAL_LINES}" -ne "${TOTAL}" ]]; then
  log_error "expected ${TOTAL} results but collected ${ACTUAL_LINES}, some background requests may not have completed"
fi

# ---------------------------------------------------------------------
# 统计：错误数（http_code 非 2xx，含连接失败的 000）+ 成功请求延迟 P50/P95/P99（毫秒）
# ---------------------------------------------------------------------
OK_LATENCIES_MS="${WORK_DIR}/ok_latencies_ms.txt"
awk '{
  code = $1
  if (code ~ /^2[0-9][0-9]$/) {
    printf "%.3f\n", $2 * 1000
  }
}' "${RESULT_FILE}" > "${OK_LATENCIES_MS}"

OK_COUNT="$(wc -l < "${OK_LATENCIES_MS}" | tr -d ' ')"
ERR_COUNT=$(( ACTUAL_LINES - OK_COUNT ))

log_info "completed: total=${ACTUAL_LINES} ok=${OK_COUNT} error=${ERR_COUNT}"

if [[ "${ERR_COUNT}" -gt 0 ]]; then
  log_info "error breakdown by http_code (000 = connection/timeout failure, not an HTTP response):"
  awk '{ if ($1 !~ /^2[0-9][0-9]$/) print $1 }' "${RESULT_FILE}" | sort | uniq -c | sort -rn | \
    while read -r cnt code; do log_info "  http_code=${code}: ${cnt} time(s)"; done
fi

percentile() {
  local p="$1" sorted_file="$2" n="$3" idx
  # 向上取整的最近秩百分位：idx = ceil(p/100 * n)，用整数运算 ceil(a/b)=(a+b-1)/b 避免浮点
  idx=$(( (p * n + 99) / 100 ))
  [[ "${idx}" -lt 1 ]] && idx=1
  [[ "${idx}" -gt "${n}" ]] && idx="${n}"
  sed -n "${idx}p" "${sorted_file}"
}

echo "------------------------------------------------------------"
if [[ "${OK_COUNT}" -eq 0 ]]; then
  log_error "no successful (2xx) response out of ${ACTUAL_LINES} requests"
  log_error "check: is kb-rag-server reachable at ${BASE_URL}? is KB_ID=${KB_ID} correct? is TOKEN valid?"
  log_error "latency percentiles: N/A (no successful samples)"
  exit 2
fi

SORTED_LATENCIES="${WORK_DIR}/ok_latencies_sorted.txt"
sort -n "${OK_LATENCIES_MS}" > "${SORTED_LATENCIES}"

P50="$(percentile 50 "${SORTED_LATENCIES}" "${OK_COUNT}")"
P95="$(percentile 95 "${SORTED_LATENCIES}" "${OK_COUNT}")"
P99="$(percentile 99 "${SORTED_LATENCIES}" "${OK_COUNT}")"

log_info "latency (ms) over ${OK_COUNT} successful requests: P50=${P50} P95=${P95} P99=${P99}"
log_info "M2 acceptance bar (M2-CONTRACTS.md §7): baseline chain P95 < 2000ms"

if [[ "${ERR_COUNT}" -gt 0 ]]; then
  log_error "benchmark finished with ${ERR_COUNT} error(s) out of ${ACTUAL_LINES} request(s)"
  exit 2
fi

log_info "benchmark finished, all ${ACTUAL_LINES} requests returned 2xx"
exit 0
