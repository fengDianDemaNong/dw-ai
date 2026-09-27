#!/usr/bin/env node
/**
 * 从 `src/config/navData.ts` 生成 `public/menu.json` —— 组织平台配菜单时的候选清单。
 *
 * <h2>为什么是这个形状</h2>
 *
 * 组织平台要让管理员「从各服务获取菜单」，而浏览器里的菜单是打包进 bundle 的，
 * 运行期没有任何接口能把它吐出来。这里在**构建前**把那份数据落成一个静态文件，
 * 随产物一起发布，组织平台按服务登记的**前端地址**去拉 `{frontendUrl}/menu.json`。
 *
 * 这样也不需要给服务新增「后端地址」字段：取菜单用的就是已经登记好的前端地址。
 *
 * <h2>清单是一棵树</h2>
 *
 * 分组与页面在清单里是同一件事：**分组就是一层目录节点**（`path` 为空），页面是它的
 * 子节点，深度不限（见 dw-org 的 `menu.json` 契约与 `NavNodeService`）。给 `navData.ts`
 * 里某一项加 `children` 就能多一层 —— org 侧栏是递归渲染的，那边不用改任何配置。
 * 候选 id 的规则见 {@link candidateId}，那是挂载的引用键，**不能随手改**。
 *
 * <h2>为什么用 esbuild 而不是直接 import</h2>
 *
 * 源文件是 TypeScript，Node 认不了。esbuild 是 vite 自带的依赖（无需新增），
 * 打包到临时目录后 import 即可 —— 这条路顺带把 `navData.ts` 的 import 链
 * （`./pages` → `./runtime` → `./api`）也解析掉。
 *
 * <p>`import.meta.env` 必须 define 掉：`config/api.ts` 的顶层会读
 * `VITE_API_BASE_URL`，而在 Node 里 `import.meta.env` 是 `undefined`，
 * 不替换的话打包产物一 import 就炸。这里给个空对象 —— 那些值生成清单用不到。
 */

import { build } from 'esbuild';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const UI_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ENTRY = join(UI_ROOT, 'src/config/navData.ts');
const OUT = join(UI_ROOT, 'public/menu.json');

const pkg = JSON.parse(readFileSync(join(UI_ROOT, 'package.json'), 'utf8'));

/** 同一次菜单展开里，相邻两项的 `sort` 间隔 —— 留空隙方便管理员手工插项。 */
const SORT_STEP = 10;

/** 与后端 `nav_items.scope` 的白名单一致（见 dw-org 的 `NavItemService.SCOPES`）。 */
const SCOPES = ['workbench', 'project'];

/**
 * 权限词的动作白名单，与 `packages/engine/src/iam.ts` 的 `Perm` 联合类型、
 * dw-org 的 `PermWords.ACTIONS` 是同一套。
 */
const ACTIONS = new Set(['read', 'write', 'admin', 'publish', 'member']);

/** 权限词的形状：严格两段式「域:动作」。 */
const PERM_SHAPE = /^[a-z][a-z0-9]*:[a-z]+$/;

/**
 * 候选 id 的规则**不在这里** —— 它是 `navData.ts` 转出的 {@link candidateId} /
 * {@link groupCandidateId}（本体在 `packages/engine/src/embedNav.ts`，下面用 `mod.` 取）。
 *
 * <p>为什么搬走：同一条规则原先在本脚本与 `navData.ts` 里各有一份**逐字相同**的实现，
 * 而运行期的上报（`config/embed.ts` 的 `postNavTree`）还要第三份。id 是挂载的引用键，
 * 任意两处漂移都会让已挂载的节点**静默**变成空目录 —— 不报错，只在有人去看侧栏时才发现。
 * 规则本身与它的完整说明（三条 id 规则、为什么老格式也要兼容）在 engine 那份里。
 */

/** 候选树摊平成一维（只用于构建期校验与计数；写进文件的仍是树）。 */
function flatten(list) {
  return list.flatMap((node) => [node, ...flatten(node.children ?? [])]);
}

const work = mkdtempSync(join(tmpdir(), 'dw-menu-'));
try {
  const bundle = join(work, 'navData.mjs');
  await build({
    entryPoints: [ENTRY],
    outfile: bundle,
    bundle: true,
    format: 'esm',
    platform: 'node',
    define: { 'import.meta.env': '{}' },
    logLevel: 'warning',
  });

  const mod = await import(pathToFileURL(bundle).href);

  const menus = [];
  const seen = new Set();
  const seenGroup = new Set();
  for (const source of mod.MENU_CANDIDATES) {
    if (!SCOPES.includes(source.scope)) {
      throw new Error(`MENU_CANDIDATES 里有非法 scope「${source.scope}」，可用值：${SCOPES.join(' / ')}`);
    }
    let sort = 0;

    /**
     * 一项（连同它的子树）→ 一个候选节点；返回 `null` = 这一支整个不进候选。
     *
     * <p>`children` 非空 = 这是个**目录节点**（见 {@link candidateId} 的 id 规则）。
     * 层级不限深度：`navData.ts` 里给自己的一项加 `children` 就能多一层，
     * 组织平台那边不需要任何改动（侧栏是递归渲染的）。
     */
    const toCandidate = (item, parents) => {
      // 未开放的页面不进候选：配进去就是一个点不开的入口。
      // `adminOnly`（本地账号体系）同理 —— 它在 multi 下不存在，
      // `nav_nodes` 也没有能表达它的列。
      if (item.ready === false || item.adminOnly) return null;

      const kids = (item.children ?? [])
        .map((child) => toCandidate(child, [...parents, item.label]))
        .filter(Boolean);

      for (const field of ['label', 'icon']) {
        if (!item[field]) throw new Error(`菜单候选缺 ${field}：${JSON.stringify(item)}`);
      }
      // 有子菜单的项是目录，它自己不能再是可点的页面：侧栏里目录那一行点不动
      // （org 渲染目录时只认它的子节点），带 path 会让它看起来像能点。
      if (kids.length && item.path) {
        throw new Error(`菜单候选「${item.label}」既有 path 又有子菜单：`
          + '一个节点要么是可点的页面、要么是装子菜单的目录，请把页面挪成它的一个子节点');
      }
      if (!kids.length && !item.path) {
        throw new Error(`菜单候选缺 path：${JSON.stringify(item)}`);
      }

      if (item.path) {
        // 同壳同路径在 org 侧是唯一约束，重复的候选会让管理员勾选后拿到一条 400
        // 「已经有指向 … 的菜单项了」，而看不出是清单自己重复了。构建期就拦住。
        const key = `${source.scope} ${item.path}`;
        if (seen.has(key)) throw new Error(`菜单候选重复：${key}`);
        seen.add(key);
      }

      // 只有**节点**消耗序号，组目录不消耗（它取组内第一项的 sort）——
      // 这样升级到「产品自发目录」这一版时，各菜单项的 sort 与旧版逐字相同，
      // 管理员已经调过的顺序不会因为一次升级而漂移。
      sort += SORT_STEP;
      return {
        id: mod.candidateId(mod.PRODUCT, source.scope, parents, item),
        scope: source.scope,
        path: item.path ?? '',
        label: item.label,
        icon: item.icon,
        perm: item.perm ?? '',
        sort,
        ...(kids.length ? { children: kids } : {}),
      };
    };

    for (const group of source.groups) {
      const items = group.items.map((item) => toCandidate(item, [])).filter(Boolean);
      if (!items.length) continue;

      // 标题为空的分组 = **不分组**：它的项直接成为顶层候选，不套一层目录。
      // `navData.ts` 用这种方式让几个顶层项各自成组（组级过滤是按组分开的，
      // 合成一组会让一个条件挡掉整组，见那边的注释）；org 折老格式清单时同理
      // （`MenuCandidateService.foldGroups` 遇到空 group 直接放顶层）。
      if (!group.title) {
        menus.push(...items);
        continue;
      }

      // 分组在清单里就是树里的一层目录节点，所以同名 = 同一层里两条同名菜单，
      // org 侧会拿 422 拒掉（`uk_nav_node`）——管理员看到的是「勾选后配不进去」。
      const key = `${source.scope} ${group.title}`;
      if (seenGroup.has(key)) {
        throw new Error(`菜单候选里有两个同名分组「${group.title}」（同一个壳下）：${key}`);
      }
      seenGroup.add(key);

      menus.push({
        id: mod.groupCandidateId(mod.PRODUCT, source.scope, group.title),
        scope: source.scope,
        path: '',
        label: group.title,
        // 图标与权限词留空、排序取组内第一项 —— 与 org 把**老格式**清单折成目录时
        // 给的字段逐字一致（见 dw-org 的 `MenuCandidateService.foldGroups`）。
        // 于是「org 自己折」与「产品自己报」两条路产出同一份候选，挂载在产品的
        // id 规则变了之后也不会失配。
        icon: '',
        perm: '',
        sort: items[0].sort,
        children: items,
      });
    }
  }

  // 权限词表：组织平台用它做两个下拉（菜单挂哪个权限、产品角色勾哪些权限）。
  // 它必须显式声明而不能从 menus 聚合 —— `lineage:write` 不在任何菜单上
  // （只用在 SQL 解析页的保存按钮上），聚合会漏掉它，
  // 于是「血缘分析」这个角色永远配不出写权限。
  const perms = mod.PERM_OPTIONS ?? [];
  const seenPerm = new Set();
  for (const option of perms) {
    const value = option == null ? '' : String(option.value ?? '');
    const label = option == null ? '' : String(option.label ?? '');
    // 形状必须在这里拦：组织平台拿到词表后是【整份】校验的
    // （见 dw-org 的 `MenuCandidateService.parsePerms`，「清单里有非法词」与
    // 「该地址上不是本产品的清单」同等对待），一个非法词会让整个产品的菜单候选
    // 都拉不到 —— 不只是少一个词，而是管理员在这一页上什么也配不了。
    if (!PERM_SHAPE.test(value) || !ACTIONS.has(value.slice(value.indexOf(':') + 1))) {
      throw new Error(`PERM_OPTIONS 里有非法权限词「${value}」：格式是「域:动作」，`
        + `动作只能是 ${[...ACTIONS].join(' / ')}`);
    }
    if (!label) {
      throw new Error(`PERM_OPTIONS 里「${value}」缺 label —— 组织平台的下拉要显示它，`
        + '没有 label 管理员看到的就是裸权限词');
    }
    if (seenPerm.has(value)) throw new Error(`PERM_OPTIONS 里「${value}」重复了`);
    seenPerm.add(value);
  }

  // 候选挂的词必须在词表里。不在的话组织平台的写入校验会拒掉那一行，
  // 管理员在「菜单管理」里怎么点都配不进去（400「产品不认这个权限词」），
  // 而那时能看到的只有词表，看不出是清单里的哪一条写了这个词。构建期就拦住。
  // （组级 perm 已由 navData 的 inheritGroupPerm 下沉到每一项，这里拿到的就是最终值。）
  for (const menu of flatten(menus)) {
    if (menu.perm && !seenPerm.has(menu.perm)) {
      throw new Error(`候选「${menu.scope} ${menu.label}」挂着权限词「${menu.perm}」，`
        + '但它不在 PERM_OPTIONS 里 —— 组织平台会拒掉这条菜单，请在词表里补上它');
    }
  }

  const payload = { product: mod.PRODUCT, version: pkg.version, perms, menus };
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, `${JSON.stringify(payload, null, 2)}\n`);

  console.log(`menu.json 已生成：${payload.product} ${payload.version}，`
    + `${flatten(menus).length} 个候选节点（${menus.length} 个顶层分组）、`
    + `${perms.length} 个权限词 → ${relative(UI_ROOT, OUT)}`);
} finally {
  rmSync(work, { recursive: true, force: true });
}
