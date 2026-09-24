#!/usr/bin/env bash
#
# 写入一套演示数据，供试玩与联调。
#
# 为什么走 REST 而不是直接灌 SQL：
#   血缘数据是一张图 —— lineage_edge 引用的是 lineage_column 的自增 id，
#   手写 INSERT 既要自己维护这些 id，又要在 H2 / MySQL / PostgreSQL 三套方言里各写一份，
#   稍有不一致就会造出一张应用根本渲染不出来的图。
#   走接口则由解析器自己生成，数据必然与真实使用产生的一模一样，且三种后端通吃。
#   附带好处：这个脚本本身就是一次端到端冒烟测试。
#
# 三种运行模式下会发生什么：
#
#   模式        鉴权                  建「项目二」    建「演示租户」
#   standalone  无（/api/** 全放行）   ✓               ✓
#   standard    要 JWT（脚本自动登录） ✓（需管理员）   ✗ 403，自动跳过
#   multi       要 JWT（组织签发）     ✗ 403           ✗ 403，自动跳过
#
#   standard 下脚本会自己用 SEED_USERNAME / SEED_PASSWORD（默认 admin / 123456）
#   调 /api/auth/login 换令牌，不用手工准备；也可以直接给 AUTH_TOKEN 跳过登录那一步。
#   multi 下租户与项目都归组织平台管，本脚本只负责往里头灌元数据与血缘 —— 先在组织
#   那边建好租户与项目，再把 TENANT_ID / PROJECT_ID 指过去。
#
#   403 的判据在服务端：租户走 TenantAdminController.assertTenantWritable()（standard /
#   multi 一律 403，管理员也不行），项目走 assertLocalAdmin()（standard 要求管理员，
#   standalone 放行，multi 403）。所以「跳过建租户」不是脚本偷懒，是接口本就不允许。
#
# 接口权限（只有 multi 下真的会校验）：
#   这些接口要求产品码 metadata 下的角色 ——
#     POST /api/meta/ddl      需要 catalog:admin
#     POST /api/lineage/save  需要 lineage:write
#   角色定义的唯一真源是 dw-common 的 Perms（前端镜像在 engine 的 iam.ts）：
#   admin 两个都有，modeler 只有 lineage:write，viewer 只有读。
#   standard / standalone 下 LineageAuthz.require 直接 return、不校验，
#   所以那两个模式里不会因为权限被拒 —— 别把那里的失败当成权限问题去查。
#
# 用法：
#   bin/seed-demo.sh                     # 灌进默认租户/项目
#   BASE_URL=http://host:8080 bin/seed-demo.sh
#   TENANT_ID=2 PROJECT_ID=3 bin/seed-demo.sh
#   TENANT_ID=tenant02 PROJECT_ID=pj02 bin/seed-demo.sh   # multi：填组织侧的编码。
#                                          # 服务端对能 parseLong 的值按本地 id 解析、否则按
#                                          # 编码查库（TenantInterceptor.resolveTenant），
#                                          # 所以编码填进这两个变量就能寻址 —— 变量名叫 ID，但值可以是编码
#   AUTH_TOKEN=xxx bin/seed-demo.sh      # 用现成令牌，跳过自动登录
#   SEED_USERNAME=admin SEED_PASSWORD=123456 bin/seed-demo.sh
#   SEED_SECOND_TENANT=1 bin/seed-demo.sh   # 额外造隔离演示域：
#                                          #   ① 同租户下的第二个项目「项目二」
#                                          #   ② 第二个租户「演示租户」（仅 standalone）
#                                          # 重新打包会清空 data/，靠它一条命令重建
#   RUN_MODE=standard bin/seed-demo.sh     # 跳过模式探测。探测不出会打印提示，并按最保守
#                                          # 的方式处理：不建租户、不建项目
#
# 可重复执行：元数据是按表名 upsert 的；血缘每跑一次会给目标表新增一个版本
# （这正好也能用来看版本切换）。
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_ID="${TENANT_ID:-1}"
PROJECT_ID="${PROJECT_ID:-1}"
SEED_SECOND_TENANT="${SEED_SECOND_TENANT:-0}"

# 令牌：给了就用；没给则 standard 模式下自动登录换一个（见 login_if_needed）。
AUTH_TOKEN="${AUTH_TOKEN:-}"
SEED_USERNAME="${SEED_USERNAME:-admin}"
SEED_PASSWORD="${SEED_PASSWORD:-123456}"
# 运行模式：启动时从 /api/auth/config 探测，也可显式给以省掉那次请求。
RUN_MODE="${RUN_MODE:-}"

DDL_OK=0
DDL_FAIL=0
LINEAGE_OK=0
LINEAGE_FAIL=0

# ---------------------------------------------------------------
# 基础设施
# ---------------------------------------------------------------

# 把字符串转成 JSON 字符串字面量（处理反斜杠、引号、换行）。
# 演示 SQL 里全是中文注释和引号，不转义会拼出非法 JSON。
json_str() {
  local s=$1
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  s=${s//$'\n'/\\n}
  s=${s//$'\t'/\\t}
  s=${s//$'\r'/}
  printf '"%s"' "$s"
}

api() {
  local method=$1 path=$2 body=${3:-}
  # --max-time：BASE_URL 指向一个不回包的主机时，没有它 curl 会一直挂着。
  local args=(-s -w '\n%{http_code}' --max-time "${API_MAX_TIME:-30}" -X "$method" "$BASE_URL$path"
              -H "X-Tenant-Id: $TENANT_ID" -H "X-Project-Id: $PROJECT_ID")
  # standard / multi 下 /api/** 要 JWT，没带就是一片 401（standalone 全放行，带了也无害）。
  if [ -n "$AUTH_TOKEN" ]; then
    args+=(-H "Authorization: Bearer $AUTH_TOKEN")
  fi
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' -d "$body")
  fi
  curl "${args[@]}"
}

# 取响应体里某个字符串字段的值（第一个出现）。与 field() 同样的理由：不依赖 jq。
str_field() {
  grep -o "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" <<<"$1" | head -1 \
    | sed 's/^[^:]*:[[:space:]]*"//; s/"$//'
}

# 探测运行模式。用 /api/runtime 而不是 /api/auth/config —— 后者只在单租户下随便问，
# multi 下 TenantInterceptor 会以「必须带 X-Tenant-Code」把它打成 400，而探测模式时
# 恰恰还不知道该不该带租户头：这是个循环依赖。服务端已经用 NO_TENANT_CONTEXT 把
# /api/runtime 排除在租户解析之外（名单里的注释写的就是这件事），脚本要走对门。
detect_run_mode() {
  # 写成 if 而不是 `[ -n "$RUN_MODE" ] && return`：后者在变量为空时整条复合命令返回 1，
  # set -e 会当场把脚本杀掉 —— 而「变量为空」恰恰是最常见的首次执行路径。
  if [ -n "$RUN_MODE" ]; then
    return
  fi
  local cfg
  cfg=$(curl -s --max-time 5 "$BASE_URL/api/runtime" 2>/dev/null || true)
  # 走 str_field 而不是手写 grep：手写的 `grep -o '"runMode":"[^"]*"'` 不容忍冒号后的
  # 空格，换个不吐紧凑 JSON 的对端（代理、别的实现）就会静默落成 unknown。
  RUN_MODE=$(str_field "$cfg" runMode || true)
  if [ -z "$RUN_MODE" ]; then
    # 不猜一个默认值：猜错的方向是「往生产库写演示数据」。unknown 在下游一律按最保守处理，
    # 与 dw-common 的 RunModes 同一条口径（认不出的值朝「要认证」倒）。
    RUN_MODE=unknown
    echo "提示：读不出 $BASE_URL/api/auth/config 的 runMode，按最保守方式处理（不建租户、不建项目）。" >&2
    echo "      可用 RUN_MODE=standalone|standard|multi 显式指定。" >&2
  fi
}

# standard 模式下 /api/** 要求令牌，而令牌只能靠登录拿 —— 这里自动换一个。
# 没这一步，standard 部署跑本脚本会得到满屏 401，而脚本头部此前对此只字未提。
login_if_needed() {
  if [ -n "$AUTH_TOKEN" ] || [ "$RUN_MODE" != "standard" ]; then
    return
  fi
  local resp code body
  resp=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":$(json_str "$SEED_USERNAME"),\"password\":$(json_str "$SEED_PASSWORD")}")
  code=$(tail -n1 <<<"$resp")
  body=$(sed '$d' <<<"$resp")
  if [ "$code" != "200" ]; then
    echo "自动登录失败（HTTP $code）：$body" >&2
    echo "  standard 模式下本脚本需要管理员令牌。请核对 SEED_USERNAME / SEED_PASSWORD" >&2
    echo "  （默认 admin / 123456），或直接给一个现成的：AUTH_TOKEN=xxx $0" >&2
    exit 1
  fi
  AUTH_TOKEN=$(str_field "$body" token)
  if [ -z "$AUTH_TOKEN" ]; then
    echo "登录响应里没有 token 字段：$body" >&2
    exit 1
  fi
  echo "已用 $SEED_USERNAME 登录换取令牌（standard 模式）"
}

# 取响应体里某个数字字段的<b>第一个</b>出现，避免为了读一个计数就依赖 jq / python。
#
# 这里必须用 grep -o 逐个取再拿第一个，不能用 `sed "s/.*\"id\"...\)/\1/"` ——
# sed 的 .* 是贪婪匹配，会一路吃到<b>最后</b>一个同名字段。
# 建租户的响应是 {"id":2,...,"projects":[{"id":3,...}]}，
# 贪婪写法取到的是嵌套项目的 id 3，于是脚本把演示数据写进了并不存在的「租户 3」——
# 拦截器不校验租户是否存在（见 docs/KNOWN_ISSUES.md 第 6 条），
# 这种错误不会报错，只会安静地造出一批没人管得着的孤儿数据。
field() {
  grep -o "\"$2\"[[:space:]]*:[[:space:]]*[0-9]*" <<<"$1" | head -1 | grep -o '[0-9]*$'
}

check_service() {
  local resp code ctx
  # /api/context 不在 SecurityConfig 的公开路径里，standard / multi 下同样要令牌，
  # 所以这里必须走 api()，裸 curl 在 standard 下会稳定地拿一个 401 再报成「连不上」。
  resp=$(api GET /api/context)
  code=$(tail -n1 <<<"$resp")
  if [ "$code" = "401" ]; then
    echo "$BASE_URL 返回 401：$RUN_MODE 模式下 /api/** 需要有效令牌。" >&2
    echo "  standard 会由本脚本自动登录；其余模式请用 AUTH_TOKEN 给一个组织签发的令牌。" >&2
    exit 1
  fi
  if [ "$code" != "200" ]; then
    echo "连不上 $BASE_URL（HTTP $code）—— 先执行 bin/start.sh 启动服务" >&2
    exit 1
  fi
  ctx=$(sed '$d' <<<"$resp")
  case "$ctx" in
    *'"tenantExists":false'*)
      echo "租户 $TENANT_ID 不存在，先在「租户管理」页面建好再来" >&2; exit 1 ;;
    *'"projectExists":false'*)
      echo "项目 $PROJECT_ID 不存在（租户 $TENANT_ID 下）" >&2; exit 1 ;;
  esac
}

# ---------------------------------------------------------------
# 元数据：贴建表语句
# ---------------------------------------------------------------

ddl() {
  local label=$1 statement=$2
  local payload resp code failed
  payload="{\"dbType\":\"hive\",\"ddl\":$(json_str "$statement"),\"overwriteManual\":false}"
  resp=$(api POST /api/meta/ddl "$payload")
  code=$(tail -n1 <<<"$resp")
  resp=$(sed '$d' <<<"$resp")

  if [ "$code" != "200" ]; then
    DDL_FAIL=$((DDL_FAIL + 1))
    printf '  ✗ %-28s HTTP %s %s\n' "$label" "$code" "$resp"
    return
  fi

  # 200 也可能是「部分失败」：一张表解析不了不影响其它表，接口照样返回 200，
  # 明细在 failures 里（见 MetaSyncResult）。只看状态码会把「9 张里坏了 3 张」
  # 整条记成成功，末尾那句「元数据 N 张表」就成了假账。
  failed=$(field "$resp" failed)
  if [ "${failed:-0}" != "0" ]; then
    DDL_FAIL=$((DDL_FAIL + 1))
    printf '  ✗ %-28s %s 张失败（成功新增 %s / 更新 %s）\n' "$label" "$failed" \
      "$(field "$resp" created)" "$(field "$resp" updated)"
    grep -o '"failures":\[[^]]*\]' <<<"$resp" | sed 's/^/      /' || true
    return
  fi

  DDL_OK=$((DDL_OK + 1))
  printf '  ✓ %-28s 新增 %s / 更新 %s\n' "$label" "$(field "$resp" created)" "$(field "$resp" updated)"
}

seed_metadata() {
  echo "==> 元数据目录（建表语句导入）"

  ddl "ods.ods_user 用户表" \
"create table ods.ods_user (
  user_id   bigint comment '用户ID',
  user_name string comment '用户名',
  phone     string comment '手机号',
  city      string comment '所在城市',
  reg_time  string comment '注册时间'
) comment '用户基础表'
partitioned by (dt string comment '日期分区')"

  ddl "ods.ods_order 订单表" \
"create table ods.ods_order (
  order_id   bigint        comment '订单ID',
  user_id    bigint        comment '用户ID',
  product_id bigint        comment '商品ID',
  amount     decimal(12,2) comment '订单金额',
  status     string        comment '订单状态',
  pay_time   string        comment '支付时间'
) comment '订单表'
partitioned by (dt string comment '日期分区')"

  ddl "ods.ods_product 商品表" \
"create table ods.ods_product (
  product_id   bigint        comment '商品ID',
  product_name string        comment '商品名称',
  category_id  bigint        comment '类目ID',
  price        decimal(12,2) comment '商品单价'
) comment '商品基础表'
partitioned by (dt string comment '日期分区')"

  ddl "ods.ods_category 类目表" \
"create table ods.ods_category (
  category_id   bigint comment '类目ID',
  category_name string comment '类目名称',
  parent_id     bigint comment '父类目ID'
) comment '商品类目表'
partitioned by (dt string comment '日期分区')"

  ddl "dwd.dwd_order_detail 订单明细" \
"create table dwd.dwd_order_detail (
  order_id      bigint        comment '订单ID',
  user_id       bigint        comment '用户ID',
  user_name     string        comment '用户名',
  city          string        comment '所在城市',
  product_id    bigint        comment '商品ID',
  product_name  string        comment '商品名称',
  category_id   bigint        comment '类目ID',
  category_name string        comment '类目名称',
  amount        decimal(12,2) comment '订单金额',
  pay_time      string        comment '支付时间'
) comment '订单明细宽表'
partitioned by (dt string comment '日期分区')"

  ddl "dws.dws_user_order_1d 用户日汇总" \
"create table dws.dws_user_order_1d (
  user_id      bigint        comment '用户ID',
  user_name    string        comment '用户名',
  city         string        comment '所在城市',
  order_cnt    bigint        comment '下单笔数',
  total_amount decimal(16,2) comment '下单总金额'
) comment '用户维度日汇总'
partitioned by (dt string comment '日期分区')"

  ddl "dws.dws_category_sale_1d 类目日汇总" \
"create table dws.dws_category_sale_1d (
  category_id   bigint        comment '类目ID',
  category_name string        comment '类目名称',
  order_cnt     bigint        comment '下单笔数',
  total_amount  decimal(16,2) comment '销售总金额'
) comment '类目维度日汇总'
partitioned by (dt string comment '日期分区')"

  ddl "ads.ads_sale_overview 销售总览" \
"create table ads.ads_sale_overview (
  city         string        comment '所在城市',
  user_cnt     bigint        comment '下单用户数',
  order_cnt    bigint        comment '订单总数',
  total_amount decimal(16,2) comment '销售总额'
) comment '分城市销售总览报表'"

  ddl "ads.ads_category_rank 类目排行" \
"create table ads.ads_category_rank (
  category_name string        comment '类目名称',
  order_cnt     bigint        comment '订单总数',
  total_amount  decimal(16,2) comment '销售总额'
) comment '类目销售排行报表'"
}

# ---------------------------------------------------------------
# 血缘：解析 SQL 并保存
# ---------------------------------------------------------------

lineage() {
  local label=$1 sql=$2
  local payload resp code
  payload="{\"dbType\":\"hive\",\"querySql\":$(json_str "$sql")}"
  resp=$(api POST /api/lineage/save "$payload")
  code=$(tail -n1 <<<"$resp")
  resp=$(sed '$d' <<<"$resp")

  if [ "$code" = "200" ]; then
    LINEAGE_OK=$((LINEAGE_OK + 1))
    printf '  ✓ %-28s 表 %s / 字段 %s / 边 %s\n' "$label" \
      "$(field "$resp" tables)" "$(field "$resp" columns)" "$(field "$resp" edges)"
  else
    LINEAGE_FAIL=$((LINEAGE_FAIL + 1))
    printf '  ✗ %-28s HTTP %s %s\n' "$label" "$code" "$resp"
  fi
}

seed_lineage() {
  echo
  echo "==> 血缘（解析 SQL 并入库）"

  # 多表 join：只用「别名 + 直传字段」，不在 join 语句里出现函数。
  # 这是实测下来唯一能保证列级归属正确的写法，原因见 docs/KNOWN_ISSUES.md。
  lineage "ods → dwd 订单明细" \
"insert into dwd.dwd_order_detail
select o.order_id,
       o.user_id,
       u.user_name,
       u.city,
       o.product_id,
       p.product_name,
       p.category_id,
       c.category_name,
       o.amount,
       o.pay_time
from ods.ods_order o
join ods.ods_user u     on o.user_id = u.user_id
join ods.ods_product p  on o.product_id = p.product_id
join ods.ods_category c on p.category_id = c.category_id
where o.status = 'PAID'"

  # 聚合：只从<b>单表</b>取数、字段一律不加限定前缀。
  # 「join + 函数」两者同时出现时，函数参数会被归属到错误的源表（不是查不到，是查错），
  # 所以汇总层刻意设计成单表聚合 —— 这也是正经数仓的常见做法（宽表已在 dwd 层打好）。
  lineage "dwd → dws 用户汇总" \
"insert into dws.dws_user_order_1d
select user_id,
       max(user_name)  as user_name,
       max(city)       as city,
       count(order_id) as order_cnt,
       sum(amount)     as total_amount
from dwd.dwd_order_detail
group by user_id"

  lineage "dwd → dws 类目汇总" \
"insert into dws.dws_category_sale_1d
select category_id,
       max(category_name) as category_name,
       count(order_id)    as order_cnt,
       sum(amount)        as total_amount
from dwd.dwd_order_detail
group by category_id"

  lineage "dws → ads 城市总览" \
"insert into ads.ads_sale_overview
select city,
       count(user_id)    as user_cnt,
       sum(order_cnt)    as order_cnt,
       sum(total_amount) as total_amount
from dws.dws_user_order_1d
group by city"

  lineage "dws → ads 类目排行" \
"insert into ads.ads_category_rank
select category_name,
       sum(order_cnt)    as order_cnt,
       sum(total_amount) as total_amount
from dws.dws_category_sale_1d
group by category_name"

  # 同一张目标表再存一次、且口径变了 —— 用来演示「版本与目标表挂钩、后面覆盖前面」：
  # dwd_order_detail 会因此有第 2 个版本，旧版本仍可在血缘关系页里选回来对比。
  lineage "dwd 订单明细（第 2 版）" \
"insert into dwd.dwd_order_detail
select o.order_id,
       o.user_id,
       u.user_name,
       u.city,
       o.product_id,
       p.product_name,
       p.category_id,
       c.category_name,
       o.amount,
       o.pay_time
from ods.ods_order o
left join ods.ods_user u     on o.user_id = u.user_id
left join ods.ods_product p  on o.product_id = p.product_id
left join ods.ods_category c on p.category_id = c.category_id"
}

# ---------------------------------------------------------------
# 额外的隔离演示域
#
# 造两个域，覆盖隔离的两个维度 —— 只造「另一个租户」是不够的，
# 「同租户下的另一个项目」才是更常用、也更容易写漏过滤条件的那一维。
# ---------------------------------------------------------------

# 在指定域灌一张一眼能认出归属的表 + 一段血缘。
#
# slug 必须是 ASCII —— 它要拼进库名表名；变量一律用 ${} 包起来，
# 否则紧跟其后的中文标点会被 bash 当成变量名的一部分（`$slug」` → 变量 `slug」`）。
seed_marker() {
  local slug=$1 label=$2
  ddl "${label} 专属表" \
"create table only_${slug}.marker_${slug} (
  id   bigint comment '仅此域可见',
  note string comment '切到别的租户或项目就看不到这张表了'
) comment '隔离演示表 · ${label}'"
  lineage "${label} 血缘" \
"insert into only_${slug}.target select id, note from only_${slug}.marker_${slug}"
}

seed_extra_domains() {
  local resp code tid pid

  echo
  echo "==> 同租户下的第二个项目（演示项目级隔离）"
  # multi 下项目归组织平台管：assertLocalAdmin() 对本模式一律 403，不必白跑一趟。
  # 只认「明确是单租户模式」的这两种，而不是「排除 multi」—— 模式探测失败时（unknown）
  # 排除法会把演示项目写进一个认不出的环境，那可能是生产库。同一条口径见 detect_run_mode。
  if [ "$RUN_MODE" = "standalone" ] || [ "$RUN_MODE" = "standard" ]; then
    resp=$(api POST "/api/tenants/$TENANT_ID/projects" '{"code":"proj2","name":"项目二"}')
    code=$(tail -n1 <<<"$resp"); resp=$(sed '$d' <<<"$resp")
    if [ "$code" != "200" ]; then
      echo "  · 跳过：HTTP $code $resp"
    else
      pid=$(field "$resp" id)
      echo "  ✓ 已建项目「项目二」tenantId=$TENANT_ID projectId=$pid"
      local keep_p=$PROJECT_ID
      PROJECT_ID=$pid
      # 切域后立刻自检：确认这个 (租户,项目) 真的存在于库里。
      # 少了这一步，id 解析出错时会安静地把数据写进一个不存在的租户（孤儿数据）。
      check_service
      seed_metadata
      seed_lineage
      seed_marker proj2 "项目二"
      PROJECT_ID=$keep_p
    fi
  else
    echo "  · 跳过：$RUN_MODE 下项目不由本脚本创建（multi 归组织平台；先在那边建好，"
    echo "    再把 PROJECT_ID 指过来）"
  fi

  echo
  echo "==> 第二个租户（演示租户级隔离）"
  # 租户接口对 standard / multi 一律 403（assertTenantWritable，管理员也不行），
  # 只有 standalone 建得动 —— 那两种模式下租户由「租户管理」页或组织平台维护。
  if [ "$RUN_MODE" != "standalone" ]; then
    echo "  · 跳过：$RUN_MODE 下租户由「租户管理」或组织平台维护（本接口对该模式一律 403）"
    return
  fi
  resp=$(api POST /api/tenants '{"code":"demo","name":"演示租户"}')
  code=$(tail -n1 <<<"$resp"); resp=$(sed '$d' <<<"$resp")
  if [ "$code" != "200" ]; then
    echo "  · 跳过：HTTP $code $resp"
    return
  fi
  tid=$(field "$resp" id)
  pid=$(sed -n 's/.*"projects":\[{"id":\([0-9]*\).*/\1/p' <<<"$resp")
  echo "  ✓ 已建租户「演示租户」tenantId=$tid projectId=$pid"

  # 后续 ddl/lineage 走全局变量，改掉它们即可切到新租户
  TENANT_ID=$tid
  PROJECT_ID=$pid
  check_service
  seed_metadata
  seed_lineage
  seed_marker demo "演示租户"
}

# ---------------------------------------------------------------

main() {
  echo "目标: $BASE_URL  租户=$TENANT_ID 项目=$PROJECT_ID"
  detect_run_mode
  echo "模式: $RUN_MODE"
  echo
  # 顺序不能换：standard 下 /api/context 也要令牌，先登录再自检，
  # 否则自检会把 401 报成「连不上」，把人往「服务没起来」上引。
  login_if_needed
  check_service
  seed_metadata
  seed_lineage
  [ "$SEED_SECOND_TENANT" = "1" ] && seed_extra_domains

  echo
  echo "==> 完成"
  echo "  元数据 $DDL_OK 张表，血缘 $LINEAGE_OK 段 SQL"
  if [ $((DDL_FAIL + LINEAGE_FAIL)) -gt 0 ]; then
    echo "  失败: 元数据 $DDL_FAIL / 血缘 $LINEAGE_FAIL"
    exit 1
  fi
  echo
  echo "  现在可以去看："
  echo "    表基础信息  $BASE_URL/catalog/tables?schema=dwd&table=dwd_order_detail"
  echo "    血缘关系    $BASE_URL/lineage/graph?start=ads.ads_sale_overview"
  echo "    全局搜索    $BASE_URL/search  （试试搜「金额」或「类目」）"
  echo "    元数据管理  $BASE_URL/meta"
}

main "$@"
