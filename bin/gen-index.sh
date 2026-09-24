#!/usr/bin/env bash
#
# 生成本地代码索引：docs/index/*.md
#
# 目的：让「找文件 / 认类」不必每次全仓扫描。本仓有 300+ 个后端类、200+ 个前端
# 文件、40+ 个迁移脚本，全扫一遍要把大量 token 花在无用文件上。索引按模块分片，
# 需要哪片读哪片（典型一片 3~8K token）。
#
# 用法：
#   bin/gen-index.sh
#
# 改完代码重跑一次即可 —— 索引是机械生成的，**不要手工编辑**（会被覆盖）。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/docs/index"
mkdir -p "$OUT"

perl - "$ROOT" "$OUT" <<'PERL'
use strict;
use warnings;
use File::Find;
use POSIX qw(strftime);
use utf8;
binmode(STDOUT, ':encoding(UTF-8)');

my ($ROOT, $OUT) = @ARGV;
my $NOW  = strftime('%Y-%m-%d %H:%M:%S', localtime);
my $HEAD = `git -C "$ROOT" rev-parse --short HEAD 2>/dev/null`;
chomp $HEAD;
$HEAD = 'unknown' unless length $HEAD;

# ---------------------------------------------------------------------------
# 通用工具
# ---------------------------------------------------------------------------

# 收集相对 ROOT 的文件列表；$dir 为相对目录，$re 为文件名过滤
sub collect_files {
  my ($dir, $re) = @_;
  my $abs = "$ROOT/$dir";
  return () unless -d $abs;
  my @out;
  find({ no_chdir => 1, wanted => sub {
    return unless -f $File::Find::name;
    return if $File::Find::name =~ m{/(node_modules|dist|target|\.git)/};
    my $rel = $File::Find::name;
    $rel =~ s{^\Q$ROOT/\E}{};
    push @out, $rel if $rel =~ $re;
  } }, $abs);
  return sort @out;
}

# 从一段说明里取第一句，去掉 Javadoc / JSDoc 标记
sub first_sentence {
  my ($s) = @_;
  return '' unless defined $s && length $s;
  # 用 / 而非 { } 作定界符：模式里的 [^}]* 含 }，s{}{} 会把它当定界符提前收尾
  # @ 在替换/模式里都是插值上下文，必须转义，否则被当成数组 @link / @code
  $s =~ s/\{\@link\s+([\w.\$#]+)(?:\s+[^}]*)?\}/$1/g;
  $s =~ s/\{\@code\s+([^}]*)\}/$1/g;
  $s =~ s{</?p>}{}g;
  $s =~ s/\s+/ /g;
  $s =~ s/^\s+|\s+$//g;
  return '' unless length $s;
  if    ($s =~ /^(.{2,200}?。)/s)   { $s = $1 }
  elsif ($s =~ /^(.{2,200}?\.\s)/s) { $s = $1 }
  elsif (length($s) > 160)          { $s = substr($s, 0, 160) . '…' }
  $s =~ s/\s+$//;
  return $s;
}

# Java：取类型声明上方的 Javadoc / 行注释首句
sub java_doc {
  my ($lines, $tidx) = @_;
  my $j = $tidx - 1;
  while ($j >= 0 && ($lines->[$j] =~ /^\s*$/ || $lines->[$j] =~ /^\s*\@/)) { $j-- }
  return '' if $j < 0;

  if ($lines->[$j] =~ m{^\s*\*/\s*$}) {
    my $k = $j;
    $k-- while $k >= 0 && $lines->[$k] !~ m{^\s*/\*\*};
    return '' if $k < 0;
    my @txt;
    for my $l (@{$lines}[$k .. $j]) {
      $l =~ s{^\s*/\*\*}{};
      $l =~ s{\*/\s*$}{};
      $l =~ s/^\s*\*\s?//;
      next if $l =~ /^\s*\@/;
      push @txt, $l;
    }
    return first_sentence(join(' ', @txt));
  }

  if ($lines->[$j] =~ m{^\s*//}) {
    my @txt;
    my $k = $j;
    while ($k >= 0 && $lines->[$k] =~ m{^\s*//}) { unshift @txt, $lines->[$k]; $k-- }
    s{^\s*//\s?}{} for @txt;
    return first_sentence(join(' ', @txt));
  }
  return '';
}

# Java：从注解/继承关系推断分层
sub java_kind {
  my ($src, $name) = @_;
  return '测试'       if $name =~ /Tests?$/;
  return 'HTTP 接口'  if $src =~ /\@(?:Rest)?Controller\b/;
  return '配置绑定'   if $src =~ /\@ConfigurationProperties\b/;
  return '启动期处理' if $src =~ /EnvironmentPostProcessor\b/;
  return '实体'       if $src =~ /\@Entity\b|\@TableName\b/;
  return '数据访问'   if $src =~ /\@Mapper\b|BaseMapper</;
  return '装配'       if $src =~ /\@Configuration\b/;
  return '启动类'     if $src =~ /\@SpringBootApplication\b/;
  return '业务服务'   if $src =~ /\@Service\b/;
  return '组件'       if $src =~ /\@Component\b/;
  return '';
}

# Java：关键入口（HTTP 路由 / main / 配置前缀），返回已渲染好的文本
sub java_entry {
  my ($src) = @_;
  my @ep;
  if ($src =~ /\@(?:Rest)?Controller\b/) {
    # 只认类级 @RequestMapping：它必须出现在第一个方法级 mapping 注解之前。
    # 否则方法上的 @RequestMapping 会被误当类前缀，把所有路由都带偏。
    my @prefixes;
    my $pc = index($src, '@RequestMapping');
    my $pm = ($src =~ /\@(?:Get|Post|Put|Delete|Patch)Mapping/) ? $-[0] : -1;
    if ($pc >= 0 && ($pm < 0 || $pc < $pm)) {
      my $rest = substr($src, $pc);
      if ($rest =~ /^\@RequestMapping\s*\(([^)]*)\)/s) {
        @prefixes = grep { length } ($1 =~ /"([^"]*)"/g);
      }
    }
    @prefixes = @prefixes[0 .. 2] if scalar(@prefixes) > 3;

    my @routes;
    while ($src =~ /\@(Get|Post|Put|Delete|Patch)Mapping\s*\(([^)]*)\)/g) {
      my ($m, $args) = (uc $1, $2);
      my ($p) = ($args =~ /"([^"]*)"/);
      next unless defined $p;
      push @routes, "$m $p";
    }

    # /api 与 /api/v1 这类多前缀不展开：前缀列一次，路由相对第一个前缀写，
    # 否则每条路由都要重复一遍前缀，控制器一多索引就被路径撑爆。
    my $base = @prefixes ? $prefixes[0] : '';
    my @rendered;
    for my $r (@routes) {
      my ($m, $p) = split / /, $r, 2;
      if (length $base && index($p, $base) == 0) {
        $p = substr($p, length $base);
        $p = '/' if $p eq '';
      }
      $p = "/$p" unless $p =~ m{^/};
      push @rendered, "\`$m $p\`";
    }

    my $total = scalar @rendered;
    if ($total > 8) {
      @rendered = @rendered[0 .. 7];
      push @rendered, "…(共 $total 条)";
    }
    if (@rendered) {
      my $head = @prefixes ? '前缀 ' . join(', ', map { "\`$_\`" } @prefixes) . ' → ' : '';
      push @ep, $head . join(', ', @rendered);
    }
  }
  if ($src =~ /\@ConfigurationProperties\s*\(\s*(?:prefix\s*=\s*)?"([^"]*)"/) {
    push @ep, "\`\@ConfigurationProperties($1)\`";
  }
  if ($src =~ /\bstatic\s+void\s+main\s*\(/) { push @ep, "\`main\`" }
  return join(', ', @ep);
}

# 解析一个 Java 文件 → { name, kind, duty, entry }
sub analyze_java {
  my ($rel) = @_;
  open my $fh, '<:encoding(UTF-8)', "$ROOT/$rel" or return undef;
  local $/;
  my $src = <$fh>;
  close $fh;
  return undef unless defined $src;

  my @lines = split /\n/, $src, -1;
  my ($kw, $name, $tidx);
  for my $i (0 .. $#lines) {
    my $l = $lines[$i];
    next if $l =~ m{^\s*(?://|\*|/\*)};   # 注释行
    if ($l =~ /^\s*(?:(?:public|final|abstract|sealed|non-sealed|static|strictfp)\s+)*(class|interface|enum|record)\s+([A-Za-z_\$][\w\$]*)/) {
      ($kw, $name, $tidx) = ($1, $2, $i);
      last;
    }
  }
  return undef unless defined $name;

  return {
    name  => $name,
    kind  => java_kind($src, $name),
    duty  => java_doc(\@lines, $tidx),
    entry => java_entry($src),
  };
}

# 渲染一行索引条目
sub render_row {
  my ($rel, $name, $kind, $duty, $entry) = @_;
  my $head = (defined $name && $name ne '') ? " — $name" : '';
  my @tail;
  push @tail, "[$kind]" if defined $kind && length $kind;
  push @tail, $duty     if defined $duty && length $duty;
  my $tail = '';
  if (@tail) {
    # 有类名时接在类名后，没类名（前端/测试片）时自己起一段，别和路径黏在一起
    $tail = length($head) ? ' ' . join(' ', @tail) : ' — ' . join(' ', @tail);
  }
  # $entry 由 java_entry 渲染好（已含反引号），这里只做拼接
  my $ep = (defined $entry && length $entry) ? " · $entry" : '';
  return "- \`$rel\`$head$tail$ep\n";
}

sub write_file {
  my ($file, $content) = @_;
  open my $fh, '>:encoding(UTF-8)', "$OUT/$file" or die "无法写入 $OUT/$file: $!";
  print $fh $content;
  close $fh;
  my $n = () = $content =~ /^- /mg;
  printf "  %-16s %4d 条\n", $file, $n;
}

sub header {
  my ($title, $note) = @_;
  my $s = "# $title\n\n";
  $s .= "> 由 \`bin/gen-index.sh\` 于 $NOW 生成（HEAD \`$HEAD\`）。";
  $s .= "**不要手工编辑**，改完代码重跑脚本即可。\n";
  $s .= "> $note\n\n" if defined $note && length $note;
  return $s;
}

# ---------------------------------------------------------------------------
# 1. 后端 Java 片（按包分组）
# ---------------------------------------------------------------------------

my @JAVA_SLICES = (
  ['dw-common',  'dw-common（共享 DTO / 公共契约）', ['dw-common/src/main/java']],
  ['dw-org',     'dw-org（组织平台 · 后端）',        ['dw-org/api/src/main/java']],
  ['dw-model',   'dw-model（仓建设 · 后端）',        ['dw-model/api/src/main/java']],
  ['dw-lineage', 'dw-lineage（数据地图 · 后端）',    ['dw-lineage/api/src/main/java']],
);

print "生成索引：\n";

for my $s (@JAVA_SLICES) {
  my ($id, $title, $dirs) = @$s;
  my @files = map { collect_files($_, qr/\.java$/) } @$dirs;

  my $srcdir = $dirs->[0];
  my %groups;
  for my $rel (@files) {
    my $info = analyze_java($rel);
    next unless $info;
    # 条目写相对源码根的路径（含包路径），拼完整路径只需一步，避免每行重复 40 字符前缀
    my $short = $rel;
    $short =~ s{^\Q$srcdir\E/}{};
    my ($pkg) = $short =~ m{^(.*)/[^/]+\.java$};
    $pkg = '' unless defined $pkg;
    my $group = $pkg;
    $group =~ s{^com/dwai/(?:platform|lineage)/?}{};
    $group = '(根包)' unless length $group;
    push @{ $groups{$group} }, { path => $short, %$info };
  }

  my $body = '';
  for my $pkg (sort keys %groups) {
    $body .= "## $pkg\n\n";
    for my $e (@{ $groups{$pkg} }) {
      $body .= render_row($e->{path}, $e->{name}, $e->{kind}, $e->{duty}, $e->{entry});
    }
    $body .= "\n";
  }

  my $note = '共 ' . scalar(@files) . " 个类。**路径 = 源码根 \`$srcdir/\` + 下表路径**；"
           . '分组标题是包名（已省略 `com/dwai/platform/` 这类公共前缀）。测试清单见 [tests.md](tests.md)。';
  write_file("$id.md", header($title, $note) . "\n" . $body);
}

# ---------------------------------------------------------------------------
# 2. 前端 / TypeScript 片
# ---------------------------------------------------------------------------

my %UI_KIND = (
  router => '路由', pages => '页面', layouts => '布局', components => '组件',
  api => 'API 封装', stores => '状态', services => '服务', config => '配置',
  auth => '鉴权', utils => '工具', types => '类型', mock => '模拟数据',
  engine => '引擎', styles => '样式', shims => '垫片', test => '测试',
  directives => '指令', composables => '组合式函数',
);
my @UI_ORDER = qw(router pages layouts components api services stores engine
                  config auth utils types mock styles shims directives composables test);

# 读文件开头若干行里的第一处注释，作为职责
sub js_doc {
  my ($rel) = @_;
  open my $fh, '<:encoding(UTF-8)', "$ROOT/$rel" or return '';
  my @lines;
  while (my $l = <$fh>) {
    push @lines, $l;
    last if @lines >= 20;
  }
  close $fh;
  for my $i (0 .. $#lines) {
    if ($lines[$i] =~ m{^\s*/\*\*}) {
      my @txt;
      for my $j ($i .. $#lines) {
        my $l = $lines[$j];
        $l =~ s{^\s*/\*\*}{};
        $l =~ s{\*/\s*$}{};
        $l =~ s/^\s*\*\s?//;
        last if $lines[$j] =~ m{\*/};
        push @txt, $l;
      }
      my $d = first_sentence(join(' ', @txt));
      return $d if length $d;
    }
    if ($lines[$i] =~ m{^\s*//}) {
      my @txt;
      for my $j ($i .. $#lines) {
        last unless $lines[$j] =~ m{^\s*//};
        my $l = $lines[$j];
        $l =~ s{^\s*//\s?}{};
        push @txt, $l;
      }
      my $d = first_sentence(join(' ', @txt));
      return $d if length $d;
    }
  }
  return '';
}

my @UI_SLICES = (
  ['dw-org',     ['dw-org/ui/src']],
  ['dw-model',   ['dw-model/ui/src']],
  ['dw-lineage', ['dw-lineage/ui/src']],
  ['engine',     ['packages/engine/src']],
);

my $ui_body = '';
my $ui_total = 0;
for my $s (@UI_SLICES) {
  my ($id, $dirs) = @$s;
  my @files;
  for my $d (@$dirs) {
    push @files, collect_files($d, qr/\.(?:vue|ts|js|mjs)$/);
  }
  $ui_total += scalar @files;

  my %groups;
  for my $rel (@files) {
    my ($top) = $rel =~ m{/(?:ui/src|engine/src)/([^/]+)};
    $top = '(顶层)' unless defined $top;
    $top = '(顶层)' if $rel =~ m{/(?:ui/src|engine/src)/[^/]+$};
    push @{ $groups{$top} }, $rel;
  }

  my @order = (@UI_ORDER, sort grep { my $t = $_; !grep { $_ eq $t } @UI_ORDER } keys %groups);
  my %rank; my $r = 0;
  $rank{$_} = $r++ for @order;

  $ui_body .= "## $id\n\n";
  $ui_body .= '源码根 ' . join('、', map { "\`$_\`" } @$dirs) . '，共 ' . scalar(@files) . " 个文件。\n\n";

  for my $top (sort { $rank{$a} <=> $rank{$b} } keys %groups) {
    my $label  = $UI_KIND{$top} || '';
    my $header = $label ? "$top（$label）" : $top;
    $ui_body .= "### $id · $header\n\n";
    for my $rel (sort @{ $groups{$top} }) {
      my $duty = js_doc($rel);
      my $short = $rel;
      $short =~ s{^(?:dw-[\w-]+/ui/src|packages/engine/src)/}{};
      # 不带类名与分层标签：路径已含文件名，小节标题已含类别
      $ui_body .= render_row($short, '', '', $duty, '');
    }
    $ui_body .= "\n";
  }
}

write_file('frontend.md',
  header('前端 / TypeScript（三个 UI + 共享引擎）',
         '**路径 = 小节标题里的模块名 + `/ui/src/`（`engine` 片段为 `packages/engine/src/`） + 下表路径**。'
         . '路由表在各 UI 的 `ui/src/router/` 下，需要完整 path → 组件映射时直接读那个文件。')
  . "\n" . $ui_body);

# ---------------------------------------------------------------------------
# 3. 测试片
# ---------------------------------------------------------------------------

my @TEST_SLICES = (
  ['dw-org',     'dw-org/api/src/test/java'],
  ['dw-model',   'dw-model/api/src/test/java'],
  ['dw-lineage', 'dw-lineage/api/src/test/java'],
);

my $test_body = '';
for my $s (@TEST_SLICES) {
  my ($id, $dir) = @$s;
  my @files = collect_files($dir, qr/\.java$/);
  $test_body .= "## $id（" . scalar(@files) . " 个）\n\n";
  for my $rel (@files) {
    my $short = $rel;
    $short =~ s{^\Q$id\E/}{};
    # 不带类名：路径末尾就是类名
    my $info = analyze_java($rel);
    my $duty = $info ? $info->{duty} : '';
    $test_body .= render_row($short, '', '', $duty, '');
  }
  $test_body .= "\n";
}

write_file('tests.md',
  header('测试清单',
         '只列类名与一句话职责；具体断言请打开文件。'
         . '**路径 = 小节标题里的模块名 + `/` + 下表路径**。跑测试见各模块 `api/pom.xml`。')
  . "\n" . $test_body);

# ---------------------------------------------------------------------------
# 4. 迁移脚本片
# ---------------------------------------------------------------------------

my %DIALECT_NOTE = (
  mysql      => 'H2（MODE=MySQL，测试）与真 MySQL（部署）共用同一份 —— 写法必须两边都认',
  postgresql => '仅 PostgreSQL',
  h2         => '仅 H2',
);

my $mig_body = '';
my @mig_files = collect_files('.', qr{/db/migration/(?:mysql|postgresql|h2)/[^/]+\.sql$});
my %mig;
for my $rel (@mig_files) {
  my ($mod, $dialect) = $rel =~ m{^([^/]+)/.*?/db/migration/(\w+)/};
  next unless defined $mod;
  push @{ $mig{$mod}{$dialect} }, $rel;
}

for my $mod (sort keys %mig) {
  $mig_body .= "## $mod\n\n";
  for my $dialect (sort keys %{ $mig{$mod} }) {
    my $note = $DIALECT_NOTE{$dialect} || '';
    $mig_body .= "### $dialect" . ($note ? "（$note）" : '') . "\n\n";
    $mig_body .= "路径 = \`$mod/api/src/main/resources/db/migration/$dialect/\` + 文件名。\n\n";
    for my $rel (sort @{ $mig{$mod}{$dialect} }) {
      my ($base) = $rel =~ m{/([^/]+)$};
      my ($ver, $desc) = $base =~ /^V(\d+)__(.+)\.sql$/;
      $desc = $base unless defined $desc;
      $desc =~ s/_/ /g;
      $mig_body .= "- \`$base\` — V$ver · $desc\n";
    }
    $mig_body .= "\n";
  }
}

my @other_sql;
for my $d (['release/sql', qr{\.sql$}], ['infra/db', qr{\.sql$}]) {
  push @other_sql, collect_files($d->[0], $d->[1]);
}
if (@other_sql) {
  $mig_body .= "## 安装包 / 初始化脚本（非 Flyway 托管）\n\n";
  $mig_body .= "- \`$_\`\n" for @other_sql;
  $mig_body .= "\n";
}

my $mig_note = <<'TXT';
**改迁移前必读**：`mysql/` 那份同时喂 H2（测试）与真 MySQL（部署），H2 认而 MySQL 不认的写法在测试里查不出来。
已知坑与替代写法写在 `dw-org/api/src/main/resources/db/migration/mysql/V8__grant_project_roles.sql` 顶部注释里；
约定本身见 `docs/adr/0002-schema-single-source.md`。
TXT
write_file('migrations.md',
  header('数据库迁移（Flyway）', $mig_note) . "\n" . $mig_body);

# ---------------------------------------------------------------------------
# 5. README
# ---------------------------------------------------------------------------

my $readme = <<"TXT";
# 代码索引

> 由 `bin/gen-index.sh` 于 $NOW 生成（HEAD `$HEAD`）。**不要手工编辑**，会被下次重跑覆盖。

本目录是为了「找文件」不必全仓扫描 —— 本仓 300+ 后端类、200+ 前端文件、40+ 迁移脚本，
全扫一遍很贵。需要哪片读哪片即可。

## 分片

| 文件 | 内容 |
|---|---|
| [dw-common.md](dw-common.md) | 共享 DTO / 公共契约（Java，按包分组） |
| [dw-org.md](dw-org.md) | 组织平台后端（Java，按包分组） |
| [dw-model.md](dw-model.md) | 仓建设后端（Java，按包分组） |
| [dw-lineage.md](dw-lineage.md) | 数据地图后端（Java，按包分组） |
| [frontend.md](frontend.md) | 三个 UI + `packages/engine` 的页面 / 组件 / API / 状态清单 |
| [tests.md](tests.md) | 三个模块的测试类清单 |
| [migrations.md](migrations.md) | Flyway 迁移脚本，按模块与方言分组 |

每行格式：`路径 — 类名 [分层] 一句话职责 · 关键入口`。
职责取自源码里类型/文件上方的注释首句；没写注释就没有这一节（空着比编一句更诚实）。
关键入口对后端是 HTTP 路由 / `main` / 配置前缀。

## 重新生成

```bash
bin/gen-index.sh
```

没有增量逻辑，每次全量重写，跑一次几秒钟。改完代码重跑即可。

## 索引不上算的地方

- 类内的方法签名、字段，索引里没有 —— 那是「读文件」的活，索引只负责**让你知道该读哪个文件**。
- 前端路由的完整 `path → 组件` 映射在各 `ui/src/router/` 源文件里，索引只标出这些文件的位置。
- **索引会过期**：文件头的时间戳与 HEAD 是判断依据。跨了几天或切了分支，先重跑再信它。
TXT

write_file('README.md', $readme);

print "完成：$OUT\n";
PERL

echo "索引目录：$OUT"
