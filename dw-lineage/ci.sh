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
    # 只盯「本产品自己的地址」：三个 UI 的 dev 端口（517x）、三个后端的默认端口（1808x）
    # 与 lineage 历史上的 8080。早先写的是 `localhost:[0-9]` 通配，会把
    # metadata-sources.vue 里 Gravitino(:8090) / DBX(:4224) 的**表单默认值**也判成硬编码
    # —— 那是用户可改的接入地址，不是我们的部署地址，误报会让人开始无视这条检查。
    #
    # 唯一豁免：`127.0.0.1:5171`。它是 config/runtime.ts 里 orgOrigin() 的**第 3 级兜底**
    # （前两级是 `#boot=` 自报的 hostOrigin 与后端答的 orgBaseUrl），是刻意保留的：
    # 三级全落空时总得有个地方跳，好过跳到一个空串。它是**单机开发态**的正确值，
    # 所以只豁免这一个字面量 —— 别的端口写死仍然要报。
    # 两类目标：① 任何**内网 IP**（历史上的真实故障是内网 IP `10.36.218.98` 被烘焙进产物）；
    # ② 回环地址上的本产品端口。私有网段三类都覆盖 —— 只写死当初那一个 IP，
    # 换个网段部署就等于没有这条检查。
    #
    # 用 -o 把命中的**地址本身**取出来（而不是文件名）：产物是 hash 命名的 bundle，
    # 报出「哪个文件」还得自己再去 grep 一遍；报出「哪个地址」直接可定位。
    # 三个网段分支各自凑满**三段**，再统一加第四段。写成
    # `(10|172\.(1[6-9]|…)|192\.168)\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}` 是错的 ——
    # 那个 `192\.168` 一次吃掉两段，于是 `192.168.0.9` 要凑五段才匹配得上，实际漏报。
    # 四段必须写全，否则 `10.1.2.3` 会被截成 `10.1.2` 报出来，看的人还得自己补。
    HITS="$(grep -rohE '(10\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}|192\.168\.[0-9]{1,3})\.[0-9]{1,3}|(localhost|127\.0\.0\.1):(517[0-9]|1808[0-9]|8080)' \
              "$ROOT"/ui/dist/assets/*.js 2>/dev/null \
            | grep -v '^127\.0\.0\.1:5171$' | sort -u || true)"
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
