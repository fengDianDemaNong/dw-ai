#!/usr/bin/env bash
#
# 完整校验：构建 + 测试 + 守卫检查。
#
#   ./ci.sh              全部
#   ./ci.sh backend      仅后端
#   ./ci.sh frontend     仅前端
#
set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-all}"

# 测试数下限。历史上出现过整个测试套件被静默跳过、构建却是绿的情况
# （sql-tools 因缺 junit-vintage-engine），这类问题必须由流水线兜住。
MIN_TESTS_SQLTOOLS="${MIN_TESTS_SQLTOOLS:-100}"

pass=0
fail=0

step()  { echo; echo "==> $*"; }
ok()    { echo "    OK  $*"; pass=$((pass+1)); }
bad()   { echo "    !!  $*"; fail=$((fail+1)); }

# 统计 surefire 报告中实际执行的测试数
count_tests() {
  local dir="$1/target/surefire-reports"
  [ -d "$dir" ] || { echo 0; return; }
  grep -ho 'tests="[0-9]*"' "$dir"/*.xml 2>/dev/null \
    | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}'
}

check_min_tests() {
  local name="$1" dir="$2" min="$3"
  local n; n="$(count_tests "$dir")"
  if [ "$n" -lt "$min" ]; then
    bad "$name 只执行了 $n 个测试（下限 $min），测试很可能被静默跳过"
  else
    ok "$name 执行了 $n 个测试"
  fi
}

# ---------------------------------------------------------------
# 后端
# ---------------------------------------------------------------
if [ "$TARGET" = "all" ] || [ "$TARGET" = "backend" ]; then

  step "构建并测试 sql-tools"
  if (cd "$ROOT/api" && mvn -B -ntp clean test -q); then
    ok "sql-tools 测试通过"
  else
    bad "sql-tools 测试失败"
  fi
  check_min_tests "sql-tools" "$ROOT/api" "$MIN_TESTS_SQLTOOLS"
fi

# ---------------------------------------------------------------
# 前端
# ---------------------------------------------------------------
if [ "$TARGET" = "all" ] || [ "$TARGET" = "frontend" ]; then

  step "构建前端"
  # 依赖装在仓库根（ui 已并入根 npm workspaces，见 ADR-0014），所以 install 要在根做；
  # 构建仍用 build:no-check（vite build，不含 vue-tsc），与迁移前口径一致。
  if (cd "$ROOT/.." && {
        [ -d node_modules ] || CI=true npm install
        CI=true npm run build:no-check -w sql-tools
      } > /tmp/ci-frontend.log 2>&1); then
    ok "前端构建通过"
  else
    bad "前端构建失败，日志: /tmp/ci-frontend.log"; tail -20 /tmp/ci-frontend.log
  fi

  step "检查前端产物是否含硬编码后端地址"
  if [ -d "$ROOT/ui/dist" ]; then
    HITS="$(grep -rlE '10\.36\.218\.98|192\.168\.|localhost:8080' \
              "$ROOT"/ui/dist/assets/*.js 2>/dev/null || true)"
    if [ -n "$HITS" ]; then
      bad "产物中出现硬编码地址，应改用 VITE_API_BASE_URL + nginx 反代"
      echo "$HITS" | sed 's/^/        /'
    else
      ok "产物无硬编码地址"
    fi
  else
    bad "未找到前端产物"
  fi
fi

# ---------------------------------------------------------------
echo
echo "================================"
echo "  通过 $pass 项，失败 $fail 项"
echo "================================"
[ "$fail" -eq 0 ] || exit 1
