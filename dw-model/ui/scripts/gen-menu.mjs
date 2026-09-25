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
 * <h2>为什么用 esbuild 而不是直接 import</h2>
 *
 * 源文件是 TypeScript，Node 认不了。esbuild 是 vite 自带的依赖（无需新增），
 * 打包到临时目录后 import 即可 —— 这条路顺带把 `navData.ts` 的 import 链
 * （`./pages` / `./paths` → `./runtime`）也解析掉。
 *
 * <p>`import.meta.env` 必须 define 掉：链上的 `config/runtime.ts` 会读
 * `VITE_RUN_MODE` / `VITE_DEPLOY_MODE`，而在 Node 里 `import.meta.env` 是 `undefined`，
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
  for (const source of mod.MENU_CANDIDATES) {
    if (!SCOPES.includes(source.scope)) {
      throw new Error(`MENU_CANDIDATES 里有非法 scope「${source.scope}」，可用值：${SCOPES.join(' / ')}`);
    }
    let sort = 0;
    for (const group of source.groups) {
      for (const item of group.items) {
        // 已停用的页面不进候选：配进去就是一个点不开的入口。
        if (item.disabled) continue;

        // 同壳同路径在 org 侧是唯一约束，重复的候选会让管理员勾选后拿到一条 400
        // 「已经有指向 … 的菜单项了」，而看不出是清单自己重复了。构建期就拦住。
        const key = `${source.scope} ${item.path}`;
        if (seen.has(key)) throw new Error(`菜单候选重复：${key}`);
        seen.add(key);

        for (const field of ['path', 'label', 'icon']) {
          if (!item[field]) throw new Error(`菜单候选缺 ${field}：${JSON.stringify(item)}`);
        }

        sort += SORT_STEP;
        menus.push({
          id: `${mod.PRODUCT}:${source.scope}:${item.path}`,
          scope: source.scope,
          group: group.title,
          path: item.path,
          label: item.label,
          icon: item.icon,
          perm: item.perm ?? '',
          sort,
        });
      }
    }
  }

  // 权限词表：组织平台用它做两个下拉（菜单挂哪个权限、产品角色勾哪些权限）。
  // 它必须显式声明而不能从 menus 聚合 —— 有些词不在任何菜单上（spec:write /
  // model:write / model:publish 都在页面内判），聚合会漏掉它们，
  // 于是「规范管理员」这个角色永远配不出写权限。
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
  for (const menu of menus) {
    if (menu.perm && !seenPerm.has(menu.perm)) {
      throw new Error(`候选「${menu.scope} ${menu.path}」挂着权限词「${menu.perm}」，`
        + '但它不在 PERM_OPTIONS 里 —— 组织平台会拒掉这条菜单，请在词表里补上它');
    }
  }

  const payload = { product: mod.PRODUCT, version: pkg.version, perms, menus };
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, `${JSON.stringify(payload, null, 2)}\n`);

  console.log(`menu.json 已生成：${payload.product} ${payload.version}，${menus.length} 条候选、`
    + `${perms.length} 个权限词 → ${relative(UI_ROOT, OUT)}`);
} finally {
  rmSync(work, { recursive: true, force: true });
}
