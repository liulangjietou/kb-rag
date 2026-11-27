#!/usr/bin/env bash
#
# 本地质量门禁：对齐 CONTRIBUTING.md / CI 的常用命令。
# 不含 CI 中的 web e2e、Compose 组合校验与 OpenAPI / Shell 语法校验（见 .github/workflows/ci.yml）。
# 用法：
#   ./scripts/dev-gate.sh                 # 全部模块
#   ./scripts/dev-gate.sh server web      # 子集：server | parser | parse-java | web | deploy
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULES=("$@")
[[ ${#MODULES[@]} -eq 0 ]] && MODULES=(server parser parse-java web deploy)

# 未显式设置 JAVA_HOME 时，macOS 上借系统的 java_home 选 JDK 17；其他平台沿用 PATH 中的 java
if [[ -z "${JAVA_HOME:-}" ]] && [[ -x /usr/libexec/java_home ]]; then
  if jdk17_home="$(/usr/libexec/java_home -v 17 2>/dev/null)"; then
    export JAVA_HOME="${jdk17_home}"
  fi
fi

run_server() {
  echo "== kb-rag-server =="
  (cd "${ROOT}/kb-rag-server" && mvn -B -ntp verify -DexcludedGroups=browser)
}

run_parser() {
  echo "== kb-rag-parser =="
  local parser_dir="${ROOT}/kb-rag-parser"
  local pytest_cmd=""
  if [[ -x "${parser_dir}/.venv/bin/pytest" ]]; then
    pytest_cmd="${parser_dir}/.venv/bin/pytest"
  elif command -v pytest >/dev/null 2>&1; then
    pytest_cmd="pytest"
  else
    echo "pytest not found; create kb-rag-parser/.venv (see kb-rag-parser/README.md)" >&2
    exit 127
  fi
  (cd "${parser_dir}" && "${pytest_cmd}" -q)
}

run_parse_java() {
  echo "== kb-rag-parse-java =="
  (cd "${ROOT}/kb-rag-parse-java" && mvn -B -ntp verify)
}

run_web() {
  echo "== kb-rag-web =="
  (cd "${ROOT}/kb-rag-web" && npm test && npm run lint && npm run build)
}

run_deploy() {
  echo "== kb-rag-deploy =="
  (cd "${ROOT}/kb-rag-deploy" && python3 -m unittest discover -s tests -p 'test_*.py')
  (cd "${ROOT}/kb-rag-deploy" && python3 scripts/validate_config.py)
}

for m in "${MODULES[@]}"; do
  case "${m}" in
    server) run_server ;;
    parser) run_parser ;;
    parse-java) run_parse_java ;;
    web) run_web ;;
    deploy) run_deploy ;;
    *)
      echo "unknown module: ${m} (use: server parser parse-java web deploy)" >&2
      exit 2
      ;;
  esac
done

echo "dev-gate: all requested modules passed"