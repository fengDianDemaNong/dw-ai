import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { expect, type Locator, type Page, test } from '@playwright/test';

const stamp = Date.now().toString(36);

/**
 * 打开着的那个下拉里的选项。
 *
 * <p>antd 把关闭的下拉留在 DOM 里（且未必带 `ant-select-dropdown-hidden`，而是靠外层容器收起来），
 * 直接选 `.ant-select-item-option` 会命中隐藏的那一份 —— 等到超时也不可见。`:visible` 是
 * Playwright 自己的可见性判定。
 */
const OPEN_OPTION = '.ant-select-item-option:visible';

/** Ant Design Vue 把两字按钮写成「登 录」这类中间空格。 */
function buttonName(label: string) {
  return new RegExp(`^${label.split('').join('\\s*')}$`);
}

function btn(root: Page | Locator, label: string) {
  return root.getByRole('button', { name: buttonName(label) });
}

function field(root: Page | Locator, label: string) {
  return root.locator('.ant-form-item').filter({ hasText: label }).locator('input, textarea').first();
}

/**
 * 按**字段标签**定位表单项（{@link field} 是「按整项文字过滤」，这里换了个口径）。
 *
 * <p>为什么要分两种：`hasText` 匹配的是**整项**（标签 + 输入框 + 帮助文字），而菜单表单里
 * 「图标」那一项的帮助文字就写着「…真正决定可见性的是上面的权限词」，按 `hasText: '权限词'`
 * 会一次命中两项（实测 count=2，strict mode 直接报错，不会静默选错）。
 *
 * <p>用 CSS `:has(label[title=…])` 而不是 `filter({ has: root.locator(…) })`：后者在
 * `root` 本身就是个过滤过的 locator 时**不会**把内层 locator 重新落根，实测 count=0
 * （同一个内层换成 `page.locator(…)` 就中了）。
 */
function item(root: Page | Locator, label: string) {
  return root.locator(`.ant-form-item:has(label[title="${label}"])`).first();
}

/**
 * 菜单表单里的「路径」输入框。
 *
 * <p>不能走 {@link field}：那个按**整项文字**过滤，而表单里「从产品清单里取」那一项的
 * 帮助文字也含「路径」二字（「…权限词与真实路径…」），会一次命中两项 —— 拿到的可能是
 * 另一项的输入框（严格模式下倒是会直接报出来，但报的是「多命中」而不是「值不对」，
 * 排查方向会被带偏）。
 */
function pathInputOf(root: Page | Locator) {
  return item(root, '路径').locator('input').first();
}

async function login(page: Page, username: string, password: string) {
  await page.goto('/org/login');
  await expect(page.getByRole('heading', { name: /登录/ })).toBeVisible();
  await field(page, '用户名').fill(username);
  await field(page, '密码').fill(password);
  await btn(page, '登录').click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });
  if (page.url().includes('/select-tenant')) {
    const xinghe = page.locator('.tenant').filter({ hasText: '星河' }).first();
    if (await xinghe.count()) {
      await xinghe.click();
      await btn(page, '进入租户').click();
      await page.waitForURL((url) => !url.pathname.includes('/select-tenant'), { timeout: 20_000 });
    }
  }
}

/**
 * 点侧栏里的一项。
 *
 * <p>传了 `href` 就按链接地址定位：产品菜单是管理员配的，组名与项名都能叫任何东西
 * （「数仓建模 / 设置」就是一个），只按文本找会先撞上门户自己的同名菜单。
 */
async function openNav(page: Page, label: string, href?: string) {
  const nav = page.locator('aside nav');
  const side = href
    ? nav.locator(`a[href="${href}"]`).first()
    : nav.getByText(label, { exact: true }).first();
  if (await side.isVisible().catch(() => false)) {
    await side.click();
    return;
  }
  const top = page.locator('nav .main').first();
  if (await top.isVisible().catch(() => false)) {
    await top.click();
    await page.getByRole('link', { name: new RegExp(label) }).first().click();
    return;
  }
  await page.getByText(label, { exact: true }).first().click();
}

async function waitDrawerClosed(page: Page, cls: string) {
  await expect(page.locator(`${cls}.ant-drawer-open`)).toHaveCount(0, { timeout: 15_000 });
}

/**
 * 一个平台管理员令牌。
 *
 * <p>单独发一次登录请求，不碰浏览器里的会话：`/api/v1/auth/login` 返回的是 token
 * 而不是种 cookie，所以不会把当前登录的用户换掉。
 */
async function adminToken(page: Page): Promise<string> {
  const res = await page.request.post('/api/v1/auth/login', {
    data: { username: 'admin', password: '123456' },
  });
  expect(res.ok(), `平台管理员登录失败：${res.status()}`).toBeTruthy();
  return (await res.json()).token as string;
}

/** 建一条菜单节点（走真实的管理面接口，与「菜单管理」页同一个）。返回它的 id。 */
async function createNavNode(page: Page, body: Record<string, unknown>): Promise<string> {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const res = await page.request.post('/api/v1/platform/nav-nodes', { headers, data: body });
  expect(res.ok(), `建菜单节点「${body.title}」失败：${res.status()} ${await res.text()}`).toBeTruthy();
  return (await res.json()).id as string;
}

/**
 * 确保某个壳的顶层有这样一个**目录**，返回它的 id。
 *
 * <p>V23 之前菜单分「分组」与「菜单项」两张表，配菜单时要先登记分组；现在分组就是一个
 * `path` 为空的普通节点（用户 2026-09-27：「统一都是菜单，菜单下面还有菜单」），
 * 于是「确保它在」变成一次「先查后建」—— 服务端有「同层不能重名」的唯一约束，
 * 直接建第二条会 422。
 */
async function ensureNavDir(page: Page, scope: string, title: string, sortOrder = 0): Promise<string> {
  const found = (await navItemRows(page)).find(
    (r) => r.scope === scope && !r.parentId && r.label === title
  );
  if (found) return found.id;
  return createNavNode(page, { scope, parentId: '', title, path: '', sortOrder });
}

/**
 * 以平台管理员身份配一批菜单项（走真实的接口，与「菜单管理」页同一个）。
 *
 * <p>用接口而不是点界面：菜单是这些用例的**前置条件**，不是被测对象 ——
 * 用界面配一遍会把「菜单管理页能不能用」的失败混进「项目壳对不对」的失败里。
 *
 * <p>`groupTitle` 现在落成**一个同名目录节点**（菜单挂到它下面），这样「同一组的菜单
 * 收在一起」的观感与断言都还能用；层级就是节点之间的父子关系。
 */
async function configureNavItems(
  page: Page,
  items: {
    product: string;
    scope: string;
    label: string;
    path: string;
    groupTitle?: string;
    perm?: string;
    sortOrder?: number;
  }[]
) {
  for (const it of items) {
    const parentId = it.groupTitle ? await ensureNavDir(page, it.scope, it.groupTitle) : '';
    await createNavNode(page, {
      scope: it.scope,
      parentId,
      title: it.label,
      path: it.path,
      perm: it.perm ?? '',
      sortOrder: it.sortOrder ?? 0,
      product: it.product,
      mounted: false,
    });
  }
}

/**
 * 登记一批**目录**节点（顶层），返回 id 供 {@link deleteNavNodes} 清理。
 *
 * <p>尽量在本用例里建**自己标题**的目录（标题带 {@link stamp}）并只清自己建的：
 * 一次性删光会把别人（或上一次跑残留）的配置一起带走。
 */
async function createNavDirs(
  page: Page,
  dirs: {
    scope: string;
    title: string;
    sortOrder?: number;
    /** `always` = 这一支一个可用入口都没有时仍保留并置灰（见服务端的空目录策略）。 */
    emptyPolicy?: string;
  }[]
): Promise<string[]> {
  const ids: string[] = [];
  for (const d of dirs) {
    ids.push(
      await createNavNode(page, {
        scope: d.scope,
        parentId: '',
        title: d.title,
        path: '',
        sortOrder: d.sortOrder ?? 0,
        emptyPolicy: d.emptyPolicy ?? 'hide',
      })
    );
  }
  return ids;
}

async function deleteNavNodes(page: Page, ids: string[]) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  for (const id of ids) {
    await page.request.delete(`/api/v1/platform/nav-nodes/${encodeURIComponent(id)}`, { headers });
  }
}

/** 删掉这些 path 上的菜单项（同一条路径可能挂在两个壳上，两个都清）。 */
async function clearNavItems(page: Page, paths: string[]) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  for (const row of await navItemRows(page)) {
    if (paths.includes(row.path)) {
      await page.request.delete(`/api/v1/platform/nav-nodes/${encodeURIComponent(row.id)}`, { headers });
    }
  }
}

/** 菜单树里的一个节点（{@link navItemRows} 把整棵树展平成一维，断言好写）。 */
type NavRow = {
  id: string;
  parentId: string;
  product: string;
  scope: string;
  label: string;
  path: string;
  icon: string;
  perm: string;
  mounted: boolean;
  ref: string;
  children?: NavRow[];
};

/** 管理面读整棵菜单树（展平成一维 —— 层级本身另有专门的用例验）。 */
async function navItemRows(page: Page): Promise<NavRow[]> {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const res = await page.request.get('/api/v1/platform/nav-nodes', { headers });
  expect(res.ok(), `读菜单树失败：${res.status()}`).toBeTruthy();
  const out: NavRow[] = [];
  const walk = (list: NavRow[]) => {
    for (const n of list) {
      out.push(n);
      walk(n.children ?? []);
    }
  };
  walk((await res.json()) as NavRow[]);
  return out;
}

async function deleteNavItemIds(page: Page, ids: string[]) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  for (const id of ids) {
    await page.request.delete(`/api/v1/platform/nav-nodes/${encodeURIComponent(id)}`, { headers });
  }
}

/** 菜单树的 label 展平 —— 消费面 `/api/v1/nav` 返回的是树，层级不限。 */
function flattenLabels(list: { label: string; children?: unknown[] }[]): string[] {
  const out: string[] = [];
  const walk = (nodes: { label: string; children?: unknown[] }[]) => {
    for (const n of nodes) {
      out.push(n.label);
      walk((n.children ?? []) as { label: string; children?: unknown[] }[]);
    }
  };
  walk(list);
  return out;
}

/**
 * 删掉某个产品里这些**自己建的**角色码（按先过滤后定位，不用「全删」）。
 *
 * <p>内置角色删不掉（服务端 400），所以按 code 精确点名比清空安全 —— 一次跑残留的
 * 自定义角色不该被后一次跑顺手带走别人的东西。
 */
type RoleRow = {
  id: string;
  product: string;
  code: string;
  label: string;
  isAdmin: boolean;
  builtin: boolean;
  perms: { value: string; label: string }[];
};

/** 某个产品的角色列表（含每个角色落库的权限词）。 */
async function productRoles(page: Page, product: string): Promise<RoleRow[]> {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const res = await page.request.get(`/api/v1/platform/product-roles?product=${product}`, { headers });
  expect(res.ok(), `读产品角色失败：${res.status()}`).toBeTruthy();
  return (await res.json()) as RoleRow[];
}

async function deleteProductRoles(page: Page, product: string, codes: string[]) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  for (const row of await productRoles(page, product)) {
    if (codes.includes(row.code)) {
      await page.request.delete(`/api/v1/platform/product-roles/${encodeURIComponent(row.id)}`, { headers });
    }
  }
}

/**
 * 一个**租户管理员**（张三）的令牌 —— 租户内的管理端点要用它。
 *
 * <p>为什么不复用 {@link adminToken}：平台管理员拿 `X-Tenant-Code` 直接进租户会被 403
 * 「无权进入该组织」（他没有该租户的平台授权）。分工是：平台面的接口用 `adminToken`，
 * 租户面的（成员、项目、许可）用这个。
 */
async function tenantAdminToken(page: Page): Promise<string> {
  const res = await page.request.post('/api/v1/auth/login', {
    data: { username: '张三', password: '123456' },
  });
  expect(res.ok(), `租户管理员登录失败：${res.status()}`).toBeTruthy();
  return (await res.json()).token as string;
}

/** 星河租户的 id（平台管理员的租户列表里取）。 */
async function xingheTenantId(page: Page): Promise<string> {
  const res = await page.request.get('/api/v1/platform/tenants', {
    headers: { Authorization: `Bearer ${await adminToken(page)}` },
  });
  const tenant = ((await res.json()) as { id: string; code: string }[]).find((t) => t.code === 'xinghe');
  expect(tenant, '找不到星河租户').toBeTruthy();
  return tenant!.id;
}

/**
 * 改星河租户**某个壳**的外观（走真实接口，与设置页那个 PUT 同一个）。
 *
 * <p>用**张三**的令牌而不是平台管理员：这是租户管理员面的接口，multi 下平台管理员
 * 不凭 `X-Tenant-Code` 进租户。而 `X-Tenant-Code` 本身是必须的 —— 少了它服务端判不出
 * 这是哪个组织，直接 403「请先选择组织」（实测）。
 *
 * <p>`shell` **必填**：工作台壳与项目壳各存各的那一行。漏传会落到老口径
 * （服务端为 dw-model 保留的兼容面）—— 表现是「改了没生效」，而且两个壳互相看不见对方
 * 的改动。所以这里不给默认值，调用方必须写清改的是哪个壳。
 *
 * <p>改完服务端就生效了，但**页面要重新加载**才看得到 —— 外观是在 bootstrap 时读进
 * `stores/prefs` 的（{@code loadTenantAppearance}），调用方自己负责再 `login`/`goto`。
 */
async function setAppearance(
  page: Page,
  shell: 'workbench' | 'project',
  appearance: { theme?: string; menuPos?: 'drawer' | 'left' | 'top'; menuColor?: string } = {}
) {
  const res = await page.request.put(
    `/api/v1/tenants/${await xingheTenantId(page)}/appearance?shell=${shell}`,
    {
      headers: {
        Authorization: `Bearer ${await tenantAdminToken(page)}`,
        'X-Tenant-Code': 'xinghe',
      },
      data: { theme: 'cyan', menuPos: 'left', menuColor: 'ink', ...appearance },
    }
  );
  expect(res.ok(), `改外观失败（${shell}）：${res.status()} ${await res.text()}`).toBeTruthy();
}

/**
 * 造一个**普通租户成员**，并给他在该租户第一个项目下派好产品角色。
 *
 * <p>为什么必须真造一个成员：管理员在该产品里是管理角色，拿管理员验「侧栏按角色过滤」
 * 等于没验（他本来就什么都看得见）。
 *
 * <p>派的是**内置**角色码（`viewer`/`modeler`/`admin`）而不是自定义的：组织壳判断
 * 「这个人能不能进项目」用的是它自己那份硬编码矩阵（`stores/app.ts` 的 `can`），
 * 自定义角色码在那一层认不出来，成员根本进不了项目 —— 用例就退化成在验别的东西。
 */
async function createTenantMember(
  page: Page,
  tenantCode: string,
  username: string,
  product: string,
  role: string
): Promise<{ id: string; tenantId: string; projectCode: string }> {
  const headers = { Authorization: `Bearer ${await tenantAdminToken(page)}` };
  const tenantsRes = await page.request.get('/api/v1/platform/tenants', {
    headers: { Authorization: `Bearer ${await adminToken(page)}` },
  });
  const tenant = ((await tenantsRes.json()) as { id: string; code: string }[]).find(
    (t) => t.code === tenantCode
  );
  expect(tenant, `找不到租户 ${tenantCode}`).toBeTruthy();
  const tenantId = tenant!.id;

  const tenantHeaders = { ...headers, 'X-Tenant-Code': tenantCode };
  const projectsRes = await page.request.get(`/api/v1/tenants/${tenantId}/projects`, {
    headers: tenantHeaders,
  });
  const project = ((await projectsRes.json()) as { id: string; code: string }[])[0];
  expect(project, `租户 ${tenantCode} 里一个项目都没有`).toBeTruthy();

  const created = await page.request.post(`/api/tenants/${tenantId}/users`, {
    headers: tenantHeaders,
    data: { username, displayName: `验证成员-${stamp}`, password: '123456', tenantRole: 'member' },
  });
  expect(created.ok(), `建成员失败：${created.status()} ${await created.text()}`).toBeTruthy();
  const id = ((await created.json()) as { id: string }).id;

  // memberships 是**整体覆盖**语义：这里只派这一个项目的这一个产品
  const patched = await page.request.patch(`/api/tenants/${tenantId}/users/${id}`, {
    headers: tenantHeaders,
    data: { memberships: [{ projectId: project!.id, product, role }] },
  });
  expect(patched.ok(), `派角色失败：${patched.status()} ${await patched.text()}`).toBeTruthy();

  return { id, tenantId, projectCode: project!.code };
}

async function deleteTenantMember(page: Page, tenantCode: string, tenantId: string, userId: string) {
  const headers = { Authorization: `Bearer ${await tenantAdminToken(page)}`, 'X-Tenant-Code': tenantCode };
  await page.request.delete(`/api/tenants/${tenantId}/users/${encodeURIComponent(userId)}`, { headers });
}

/**
 * 前置条件：这个产品在「服务注册」里有页面地址。
 *
 * <p>产品页面的完整路由要靠它（`NavItemService.menuFor` 带给前端），没登记则菜单项
 * 只会渲染成「未配置前端地址」的置灰项。**明确断言**而不是替它猜一个默认端口 ——
 * 猜出来的地址会把「环境没配好」伪装成「功能坏了」。
 */
async function requireRegisteredFrontend(page: Page, product: string) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const res = await page.request.get('/api/v1/platform/services', { headers });
  const rows = (await res.json()) as { product: string; frontendUrl?: string }[];
  const hit = rows.find((r) => r.product === product);
  expect(
    hit?.frontendUrl,
    `前置条件不满足：产品 ${product} 还没在「服务注册」里填页面地址（见该环境的初始化脚本）`
  ).toBeTruthy();
}

/** 登记（或改）一个产品的页面地址。用完请调 {@link unregisterService} 还原。 */
async function registerService(page: Page, product: string, frontendUrl: string) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const res = await page.request.post('/api/v1/platform/services', {
    headers,
    data: { product, frontendUrl },
  });
  expect(res.ok(), `登记 ${product} 失败：${res.status()} ${await res.text()}`).toBeTruthy();
}

async function unregisterService(page: Page, product: string) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  await page.request.delete(`/api/v1/platform/services/${product}`, { headers });
}

/**
 * 临时给租户开一个模块许可，返回改动前的许可列表（供 {@link setModules} 还原）。
 *
 * <p>为什么需要：`nav_items` 的菜单在**服务端**按租户许可过滤（`NavItemService.menuFor`），
 * 许可里没有的产品，配了菜单也不会出现在侧栏。要验「没接子端的产品走外链」，就得先让
 * 那款产品对这个租户可见。
 */
async function grantModule(
  page: Page,
  tenantCode: string,
  module: string
): Promise<{ id: string; before: string[] }> {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const res = await page.request.get('/api/v1/platform/tenants', { headers });
  const rows = (await res.json()) as { id: string; code: string; modules?: string[] }[];
  const tenant = rows.find((r) => r.code === tenantCode);
  expect(tenant, `找不到租户 ${tenantCode}（这个环境的演示数据没灌？）`).toBeTruthy();
  const before = tenant!.modules ?? [];
  if (!before.includes(module)) {
    const patch = await page.request.patch(`/api/v1/platform/tenants/${tenant!.id}`, {
      headers,
      data: { modules: [...before, module] },
    });
    expect(patch.ok(), `给租户 ${tenantCode} 开 ${module} 许可失败：${patch.status()}`).toBeTruthy();
  }
  return { id: tenant!.id, before };
}

async function setModules(page: Page, tenantId: string, modules: string[]) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  await page.request.patch(`/api/v1/platform/tenants/${tenantId}`, { headers, data: { modules } });
}

/** 某个产品当前登记的页面地址（{@link registerService} 是覆盖语义，还原时要先记下来）。 */
async function frontendUrlOf(page: Page, product: string): Promise<string | undefined> {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const res = await page.request.get('/api/v1/platform/services', { headers });
  const rows = (await res.json()) as { product: string; frontendUrl?: string }[];
  return rows.find((r) => r.product === product)?.frontendUrl;
}

/** 候选清单里的一个节点（`/api/v1/platform/nav-candidates` 的 `menus`，是一棵树）。 */
type CandRow = { id: string; label: string; path: string; children?: CandRow[] };

function findCand(list: CandRow[], label: string): CandRow | undefined {
  for (const m of list) {
    if (m.label === label) return m;
    const hit = findCand(m.children ?? [], label);
    if (hit) return hit;
  }
  return undefined;
}

/**
 * 从**产品自报的清单**里按名字找一个节点的 id —— 挂载（`ref`）要引用的就是它。
 *
 * <p>为什么不把 id 拼死在用例里：那个 id 由产品侧生成（`gen-menu.mjs`），是挂载的
 * **引用键**。硬编码它会让「产品改了 id 生成规则」这件事变成一堆假红，而走一遍
 * `nav-candidates` 拿到的正是管理员在界面上选的那个 id，与真实用法一致。
 */
async function navCandRef(page: Page, product: string, label: string): Promise<string> {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const res = await page.request.get('/api/v1/platform/nav-candidates', { headers });
  expect(res.ok(), `读候选清单失败：${res.status()}`).toBeTruthy();
  const data = (await res.json()) as {
    products: { product: string; ok: boolean; error: string; menus: CandRow[] }[];
  };
  const found = data.products.find((p) => p.product === product);
  expect(found, `候选清单里没有产品 ${product}（「服务注册」里登记页面地址了吗？）`).toBeTruthy();
  expect(found!.ok, `${product} 的清单没拉到：${found!.error}`).toBeTruthy();
  const hit = findCand(found!.menus, label);
  expect(hit, `${product} 自报的清单里没有「${label}」`).toBeTruthy();
  return hit!.id;
}

/**
 * 一份最小可用的产品候选清单（`/menu.json` 的形状，见 `scripts/gen-menu.mjs`）。
 *
 * <p>信封里的 `product` 必须与登记的产品一致 —— org 会拿它核对「页面地址有没有填串」
 * （见 `MenuCandidateService.fetch`），对不上就整份拒掉，表现为「这个产品的清单拉不到」。
 */
/** 桩清单里的一项：`children` 非空就是一个目录节点（V23 起产品可以报任意层级）。 */
type StubMenu = {
  /** 不写就按路径拼一个。挂载引用的就是这个 id，所以目录节点必须显式给。 */
  id?: string;
  group?: string;
  path: string;
  label: string;
  sort: number;
  perm?: string;
  icon?: string;
  children?: StubMenu[];
};

function stubMenus(items: StubMenu[], product: string, prefix = ''): Record<string, unknown>[] {
  return items.map((m) => ({
    id: m.id ?? `${product}:project:${prefix}${m.path || m.label}`,
    scope: 'project',
    perm: '',
    icon: 'TableOutlined',
    ...m,
    ...(m.children ? { children: stubMenus(m.children, product, `${m.label}-`) } : {}),
  }));
}

function menusJson(items: StubMenu[], product = 'metadata') {
  return JSON.stringify({ product, version: '0.1.3', menus: stubMenus(items, product) });
}

/**
 * 一个只服务 `/menu.json` 的桩产品前端 —— 用来演「产品发版」。
 *
 * <p>为什么不改 `dw-lineage/ui/public/menu.json`：那是 gitignored 的**构建产物**，写坏了
 * 得靠重跑 `gen-menu.mjs` 还原，而漏还原会让后续用例看到一个被篡改的清单。桩在内存里
 * 改一行就够了，也不会留下垃圾。副作用只有一个：这条用例期间 metadata 登记的页面地址
 * 指向桩，所以必须在 `finally` 里还原（{@link frontendUrlOf} 先记下原值）。
 */
async function startMenuStub(initial: string): Promise<{ base: string; body: string; close: () => Promise<void> }> {
  const stub = { base: '', body: initial, close: async () => {} };
  const server = http.createServer((req, res) => {
    if (!req.url?.startsWith('/menu.json')) {
      res.writeHead(404);
      res.end();
      return;
    }
    const bytes = Buffer.from(stub.body, 'utf8');
    res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': bytes.length });
    res.end(bytes);
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  stub.base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  stub.close = () => new Promise<void>((resolve) => server.close(() => resolve()));
  return stub;
}

test.describe.serial('工作台前端流程', () => {
  /**
   * 把星河租户**两个壳**的菜单风格都钉成 `left`，让存量用例与风格解耦。
   *
   * <p>项目壳的默认风格是 `drawer`（`DEFAULT_PROJECT_APPEARANCE.menuPos`，PRD §4 写的默认值），
   * 而本文件里有一批用例是按「左侧栏」写的（`page.locator('aside nav')`，{@link openNav}
   * 的第一条回落也是它）。drawer 下压根没有 `aside` —— 不钉住的话，项目壳那几条会一起红，
   * 每条都要重新判断是「改动坏了」还是「风格变了」。
   *
   * <p>**两个壳都要钉**：外观按壳分开存之后，只钉工作台的话进项目壳的用例读到的仍是
   * 项目那套的默认值（drawer）。工作台那份其实不必钉（非项目壳的 drawer 会被折算成
   * left），但钉上才让「两个壳互不影响」那条用例有个确定的起点。
   *
   * <p>专门覆盖 drawer 的用例自己会先改过去、结束再改回来（见本文件末尾「收起」那条）。
   */
  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage();
    try {
      await setAppearance(page, 'workbench', { menuPos: 'left' });
      await setAppearance(page, 'project', { menuPos: 'left' });
    } finally {
      await page.close();
    }
  });

  test('登录张三进入项目管理', async ({ page }) => {
    await login(page, '张三', '123456');
    await expect(page).toHaveURL(/\/projects/);
    await expect(page.getByRole('heading', { name: '项目管理' })).toBeVisible();
    await expect(btn(page, '新增')).toBeVisible();
    await expect(page.getByText('块')).toBeVisible();
    await expect(page.getByText('行')).toBeVisible();
  });

  test('用户管理：行内仅编辑删除，抽屉含全部字段并可保存', async ({ page }) => {
    await login(page, '张三', '123456');
    await openNav(page, '用户管理');
    await expect(page.getByRole('heading', { name: '用户管理' })).toBeVisible();
    await expect(btn(page.locator('.page-header'), '新增')).toBeVisible();
    await expect(btn(page, '转让管理员')).toBeVisible();
    await expect(page.getByRole('heading', { name: '平台授权码' })).toBeVisible();

    const table = page.locator('.org-users-table');
    await expect(table.getByText('显示名')).toBeVisible();
    await expect(table.getByText('用户名')).toBeVisible();
    await expect(table.getByText('张三').first()).toBeVisible();

    const lisi = table.locator('tbody tr').filter({ hasText: '李四' }).first();
    await expect(btn(lisi, '编辑')).toBeVisible();
    await expect(btn(lisi, '删除')).toBeVisible();
    await expect(btn(lisi, '指定项目管理员')).toHaveCount(0);
    await expect(btn(lisi, '重置密码')).toHaveCount(0);
    await expect(btn(lisi, '停用')).toHaveCount(0);
    await expect(btn(lisi, '启用')).toHaveCount(0);

    await btn(lisi, '编辑').click();
    const drawer = page.locator('.user-edit-drawer.ant-drawer-right');
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText('编辑用户')).toBeVisible();
    await expect(field(drawer, '显示名')).toBeVisible();
    await expect(field(drawer, '用户名')).toBeVisible();
    await expect(drawer.getByText('角色', { exact: true })).toBeVisible();
    await expect(drawer.getByText('状态', { exact: true })).toBeVisible();
    await expect(drawer.getByRole('radio', { name: '启用' })).toBeVisible();
    await expect(drawer.getByRole('radio', { name: '停用' })).toBeVisible();
    await expect(drawer.getByRole('heading', { name: '项目' })).toBeVisible();
    await expect(drawer.getByRole('heading', { name: '指定管理员' })).toBeVisible();
    await expect(drawer.getByRole('heading', { name: '重置密码' })).toBeVisible();
    await expect(btn(drawer, '指定')).toBeVisible();
    await expect(btn(drawer, '重置')).toBeVisible();
    await expect(btn(drawer, '取消')).toBeVisible();
    await expect(btn(drawer, '保存')).toBeVisible();

    const nameInput = field(drawer, '显示名');
    const original = await nameInput.inputValue();
    const next = original.includes('（测）') ? original.replace('（测）', '') : `${original}（测）`;
    await nameInput.fill(next);
    await btn(drawer, '保存').click();
    await waitDrawerClosed(page, '.user-edit-drawer');
    await expect(table.getByText(next, { exact: true })).toBeVisible();

    await btn(lisi, '编辑').click();
    const again = page.locator('.user-edit-drawer.ant-drawer-open');
    await expect(again).toBeVisible();
    await field(again, '显示名').fill(original);
    await btn(again, '保存').click();
    await waitDrawerClosed(page, '.user-edit-drawer');
    // `.first()`：seed 里李四的显示名与用户名同值，表格两列都会命中，严格模式会判为歧义。
    // 这里要的只是「改回原名后还能看到它」，与本文件其它同名断言一致。
    await expect(table.getByText(original, { exact: true }).first()).toBeVisible();
  });

  test('用户管理：新增、抽屉改用户名、删除', async ({ page }) => {
    await login(page, '张三', '123456');
    await openNav(page, '用户管理');
    const username = `e2e_${stamp}`;
    const display = `E2E用户${stamp}`;

    await btn(page.locator('.page-header'), '新增').click();
    const modal = page.locator('.ant-modal').filter({ hasText: '新建 / 拉入用户' });
    await expect(modal).toBeVisible();
    await field(modal, '用户名').fill(username);
    await field(modal, '显示名').fill(display);
    await btn(modal, '加入').click();
    await expect(modal).toBeHidden({ timeout: 15_000 });

    const table = page.locator('.org-users-table');
    const row = table.locator('tbody tr').filter({ hasText: username });
    await expect(row).toBeVisible();
    await expect(btn(row, '编辑')).toBeVisible();
    await expect(btn(row, '删除')).toBeVisible();

    await btn(row, '编辑').click();
    const drawer = page.locator('.user-edit-drawer');
    await expect(drawer).toBeVisible();
    await field(drawer, '显示名').fill(`${display}改`);
    await field(drawer, '用户名').fill(`${username}_u`);
    await btn(drawer, '保存').click();
    await waitDrawerClosed(page, '.user-edit-drawer');
    await expect(table.getByText(`${display}改`)).toBeVisible();
    await expect(table.getByText(`${username}_u`)).toBeVisible();

    const edited = table.locator('tbody tr').filter({ hasText: `${username}_u` });
    await btn(edited, '删除').click();
    await btn(page, '确定').click();
    await expect(table.getByText(`${username}_u`)).toHaveCount(0);
  });

  test('项目管理：行内进入和编辑，删除在抽屉里', async ({ page }) => {
    await login(page, '张三', '123456');
    await openNav(page, '项目管理');
    await expect(page.getByRole('heading', { name: '项目管理' })).toBeVisible();

    const card = page.locator('.projects-grid .proj').first();
    await expect(card).toBeVisible();
    await expect(btn(card, '进入项目')).toBeVisible();
    await expect(btn(card, '编辑')).toBeVisible();
    await expect(btn(card, '删除')).toHaveCount(0);
    await expect(btn(card, '停用')).toHaveCount(0);
    await expect(btn(card, '启用')).toHaveCount(0);

    await btn(card, '编辑').click();
    const drawer = page.locator('.project-edit-drawer.ant-drawer-right');
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText('编辑项目')).toBeVisible();
    await expect(field(drawer, '项目名称')).toBeVisible();
    await expect(field(drawer, '项目编码')).toBeVisible();
    await expect(field(drawer, '说明')).toBeVisible();
    await expect(drawer.getByText('项目管理员')).toBeVisible();
    await expect(drawer.getByText('状态', { exact: true })).toBeVisible();
    await expect(drawer.getByText('创建信息')).toBeVisible();
    await expect(btn(drawer, '进入项目')).toBeVisible();
    await expect(btn(drawer, '删除项目')).toBeVisible();
    await expect(btn(drawer, '取消')).toBeVisible();
    await expect(btn(drawer, '保存')).toBeVisible();

    const desc = field(drawer, '说明');
    const original = await desc.inputValue();
    const next = original.includes('E2E') ? original.replace(/\s*E2E$/, '') : `${original} E2E`.trim();
    await desc.fill(next);
    await btn(drawer, '保存').click();
    await waitDrawerClosed(page, '.project-edit-drawer');
    await expect(page.locator('.projects-grid').getByText(next).first()).toBeVisible();

    await btn(page.locator('.projects-grid .proj').first(), '编辑').click();
    const again = page.locator('.project-edit-drawer.ant-drawer-open');
    await expect(again).toBeVisible();
    await field(again, '说明').fill(original);
    await btn(again, '保存').click();
    await waitDrawerClosed(page, '.project-edit-drawer');
  });

  test('项目管理：新增、抽屉改名、删除', async ({ page }) => {
    await login(page, '张三', '123456');
    await openNav(page, '项目管理');
    const code = `e2e_${stamp}`;
    const name = `E2E项目${stamp}`;

    await btn(page.locator('.page-header'), '新增').click();
    const modal = page.locator('.ant-modal').filter({ hasText: '新建项目' });
    await expect(modal).toBeVisible();
    await field(modal, '项目编码').fill(code);
    await field(modal, '项目名称').fill(name);
    await field(modal, '描述').fill('自动化创建，测完删除');
    await btn(modal, '创建').click();
    await expect(modal).toBeHidden({ timeout: 20_000 });

    const card = page.locator('.projects-grid .proj').filter({ hasText: name });
    await expect(card).toBeVisible();
    await expect(btn(card, '进入项目')).toBeVisible();
    await expect(btn(card, '编辑')).toBeVisible();
    await expect(btn(card, '删除')).toHaveCount(0);

    await btn(card, '编辑').click();
    const drawer = page.locator('.project-edit-drawer');
    await expect(drawer).toBeVisible();
    await field(drawer, '项目名称').fill(`${name}改`);
    await btn(drawer, '保存').click();
    await waitDrawerClosed(page, '.project-edit-drawer');
    const renamed = page.locator('.projects-grid .proj').filter({ hasText: `${name}改` });
    await expect(renamed).toBeVisible();

    await btn(renamed, '编辑').click();
    const again = page.locator('.project-edit-drawer.ant-drawer-open');
    await expect(again).toBeVisible();
    await btn(again, '删除项目').click();
    await btn(page, '确定').click();
    await expect(page.locator('.projects-grid').getByText(`${name}改`)).toHaveCount(0);
  });

  test('角色、设置、进项目、规范与建模冒烟', async ({ page }) => {
    await login(page, '张三', '123456');

    await openNav(page, '角色管理');
    await expect(page.getByRole('heading', { name: '角色管理' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '租户管理员' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '项目管理员' })).toBeVisible();

    await openNav(page, '设置', '/org/workbench/settings');
    await expect(page.getByRole('heading', { name: '设置' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '主题' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '菜单栏颜色' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '菜单风格' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '大模型' })).toBeVisible();

    // 「进入项目」落点 = **项目壳**（不是某个产品自己的站点，也不是工作台里的某一页）。
    // 见下面那条 case 的说明：项目壳里有什么，取决于管理员把哪些菜单挂到了项目壳上。
    await openNav(page, '项目管理');
    await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
    await expect(page).toHaveURL(/\/org\/project\//);

    // 这个环境没配项目菜单时，项目壳的首页会说明原因而不是弹回别处 ——
    // 两条路都是「进了项目壳」，所以断言到 URL 为止，具体内容交给下面那条 case。
    await expect(page.getByText('这个项目还没有配置页面')).toBeVisible();
  });

  /**
   * 项目壳：配到项目壳上的菜单真的出现，且按「能不能嵌」分流。
   *
   * <p>这是需求 ② 的核心链路 —— 管理员挂菜单 → 成员进项目 → 壳里出现各服务的入口。
   * 接了嵌入协议的（元数据 / 仓建设）走本站路由，链到壳内的 iframe 页；没接的
   * （数据质量没有独立前端）按「不可嵌产品一律整页外链」渲染成**新标签页**链接。
   */
  test('项目壳：挂上去的产品菜单按能不能嵌分流', async ({ page }) => {
    const metadataPath = '/lineage/tables';
    const warehousePath = '/model/members';
    const qualityPath = '/quality/rules';
    // 目录标题带 stamp：三个目录是同层的，同名会撞服务端的唯一约束（`uk_nav_node`）
    const gMeta = `数据地图-${stamp}`;
    const gWh = `仓建设-${stamp}`;
    const gQuality = `数据质量-${stamp}`;

    await requireRegisteredFrontend(page, 'metadata');
    await requireRegisteredFrontend(page, 'warehouse');
    // 数据质量没有子端接收端（见 config/products.ts 的 EMBEDDABLE），拿它当「外链」那一支的样本。
    // 地址是随便指的：这一支只断言 href 拼得对，不会真去打开那个站点。
    await registerService(page, 'quality', 'http://127.0.0.1:5181');
    const granted = await grantModule(page, 'xinghe', 'quality');
    // 三个目录的先后**显式登记**：下面那条断言依赖「数据地图这一支排在前面」。
    // 不登记的话三条的 `sort_order` 都是 0，服务端按标题排（仓建设 < 数据地图），
    // 落点会变成仓建设的「项目成员」—— 那是确定的规则（见上面「侧栏菜单树」那条），
    // 只是这条用例的落点断言不该靠它。
    const dirIds = await createNavDirs(page, [
      { scope: 'project', title: gMeta, sortOrder: 10 },
      { scope: 'project', title: gWh, sortOrder: 20 },
      { scope: 'project', title: gQuality, sortOrder: 30 },
    ]);

    try {

      await configureNavItems(page, [
        {
          product: 'metadata',
          scope: 'project',
          groupTitle: gMeta,
          label: '血缘分析',
          path: metadataPath,
          perm: '',
        },
        {
          product: 'warehouse',
          scope: 'project',
          groupTitle: gWh,
          label: '项目成员',
          path: warehousePath,
        },
        {
          product: 'quality',
          scope: 'project',
          groupTitle: gQuality,
          label: '质量规则',
          path: qualityPath,
        },
      ]);

      await login(page, '张三', '123456');
      await openNav(page, '项目管理');
      await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();

      // 第一条能嵌的项目菜单就是元数据的这条 —— 直接落进去，没有中转页
      await expect(page).toHaveURL(/\/org\/project\/[^/]+\/embed\/metadata\/lineage\/tables/);
      const nav = page.locator('aside nav');
      await expect(nav.getByText(gMeta, { exact: true })).toBeVisible();
      await expect(nav.getByText('血缘分析', { exact: true })).toBeVisible();

      // 嵌入的 iframe 必须**撑满内容区**。
      //
      // <p>这条不是凑数：`.main` 曾经是 `display: block`，页面里那三层 `flex: 1`
      // （`.embed-page` → `.embed` → `iframe.pane`）于是静默失效，iframe 退化成浏览器
      // 默认的 **150px** —— 子应用只剩顶上一条，其余全是空的。地址、菜单、外链那几条
      // 断言在当时**照样全绿**，只有量高度才看得见。
      //
      // <p>两个断言各管一头：`> 300` 排除「三层一起缩成默认高度」，与 `.main` 比排除
      // 「撑了但没撑满」。不写死视口高度，免得跟着 playwright 的 viewport 配置漂。
      const pane = page.locator('iframe.pane');
      await expect(pane).toBeVisible();
      const paneBox = await pane.boundingBox();
      const mainBox = await page.locator('.main').boundingBox();
      expect(paneBox!.height, 'iframe 不该是浏览器默认的 150px 高').toBeGreaterThan(300);
      expect(paneBox!.height, 'iframe 应撑满内容区').toBeGreaterThanOrEqual(mainBox!.height - 1);

      // 仓建设也接了子端，所以它同样是本站导航，不该新开标签页
      await expect(nav.getByText(gWh, { exact: true })).toBeVisible();
      await expect(nav.getByText('项目成员', { exact: true })).toBeVisible();
      await expect(nav.locator('a[target="_blank"]').filter({ hasText: '项目成员' })).toHaveCount(0);

      // 数据质量那个入口是「去别的站点」，不是本站的一次导航 —— 必须新标签页打开，
      // 且地址要带上是哪一页（只跳站点根会落到首页，用户点的是「质量规则」）
      const external = nav.locator('a[target="_blank"]').filter({ hasText: '质量规则' });
      await expect(external).toBeVisible();
      expect(await external.getAttribute('href')).toContain('/quality/rules');

      // 进了项目要能出去 —— 这个入口现在由左下角的用户面板提供：V24 把侧栏那条菜单删了
      // （同一个动作不该在屏幕上出现两次，用户 2026-09-27 报的重复）。所以改从面板里找，
      // 断言的意义不变；判据也一并从「租户管理员」放宽到「在项目里就有」，
      // 否则普通成员进得来、出不去（项目壳路由的 meta 是 member）。
      await page.locator('.me').click();
      await expect(page.locator('.sheet').getByText('返回工作台', { exact: true })).toBeVisible();
      await page.keyboard.press('Escape');
    } finally {
      await clearNavItems(page, [metadataPath, warehousePath, qualityPath]);
      await deleteNavNodes(page, dirIds);
      await unregisterService(page, 'quality');
      await setModules(page, granted.id, granted.before);
    }
  });

  /**
   * 被嵌的产品**不该再画自己那套导航栏**。
   *
   * <p>嵌进来的这层壳已经有完整导航（还带着「返回工作台」与项目切换器），子应用再画一份
   * 就是两条并排的侧栏、两个 logo、两个「收起」—— 同一套菜单被画两遍，看起来像重复入口。
   *
   * <p>**两个被嵌产品都要验**：这件事得在各个产品自己的布局里各实现一遍，而它们当时
   * 不一致 —— dw-lineage 的 `AppLayout` 一直有 `layout-embed`（只留内容区），dw-model 的
   * `ProjectLayout`/`SystemLayout` 一行都没判。只验一边就会漏掉另一边，而漏掉的那边正是
   * 用户看到的现象。
   *
   * <p>断言必须往 iframe **里面**看：宿主这层的侧栏一直都在，光看截图或数侧栏条数分不出
   * 哪条是谁画的。
   */
  test('项目壳：被嵌的产品不画自己那套导航', async ({ page }) => {
    const lineagePath = '/lineage/tables';
    const modelPath = '/model/members';

    await requireRegisteredFrontend(page, 'metadata');
    await requireRegisteredFrontend(page, 'warehouse');

    try {
      await configureNavItems(page, [
        { product: 'metadata', scope: 'project', label: '血缘分析', path: lineagePath, perm: '' },
        { product: 'warehouse', scope: 'project', label: '项目成员', path: modelPath, perm: '' },
      ]);

      await login(page, '张三', '123456');
      await openNav(page, '项目管理');
      await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
      await expect(page).toHaveURL(/\/org\/project\//);
      const projectCode = page.url().match(/\/org\/project\/([^/]+)/)![1];

      for (const [product, path] of [
        ['metadata', lineagePath],
        ['warehouse', modelPath],
      ] as const) {
        await page.goto(`/org/project/${projectCode}/embed/${product}${path}`);
        await expect(page.locator('iframe.pane')).toBeVisible();

        // 取 iframe 那一层；跨端口也算跨源，所以用 Playwright 的 frame 句柄而不是
        // `contentDocument`（后者会撞同源策略）。
        const child = () => page.frames().find((f) => f !== page.mainFrame());

        // **先等子应用真的挂载**，再判有没有侧栏。
        //
        // <p>少了这一步这条断言会假通过：iframe 刚建好、Vue 还没 mount 时 DOM 是空的，
        // `querySelector('aside')` 自然为 null —— 看着是「干净」，其实是「还没画」。
        // （实测：把 dw-model 的 embed 判断回退掉，这一步缺失时用例照样绿。）
        await expect
          .poll(
            async () => {
              const f = child();
              if (!f) return 0;
              try {
                // Vue 挂载完的标志：根容器里有元素了（挂载前是空壳）
                return await f.evaluate(() => {
                  const root = document.querySelector('#root, #app');
                  return root ? root.children.length : 0;
                });
              } catch {
                // 子应用还在导航，frame 句柄会短暂失效 —— 交给 poll 重试
                return 0;
              }
            },
            { message: `${product} 的子应用一直没挂载出来` }
          )
          .toBeGreaterThan(0);

        // `aside` 是被嵌产品侧栏的根标签（lineage 的 AppSidebar、model 的 AppNav 都是）。
        // 页面正文里不会再出现 aside，所以这一条不会误报。
        const hasSidebar = await child()!.evaluate(() => Boolean(document.querySelector('aside')));
        expect(hasSidebar, `${product} 被嵌进来后仍画着自己的侧栏`).toBe(false);
      }
    } finally {
      await clearNavItems(page, [lineagePath, modelPath]);
    }
  });

  /**
   * 侧栏菜单树：同一层里按登记的 `sortOrder` 排，空目录按 `emptyPolicy` 保留或隐藏，
   * 子菜单能一层层下钻。
   *
   * <p>顺序这条刻意构造反例：甲目录下的菜单项 `sortOrder` 更小（服务端因此先给出它，也就是
   * 「首次出现序」里甲在前），但登记时乙目录的排序更靠前。侧栏必须按**登记的顺序**排 ——
   * 不构造这个反例，两种规则下结果一样，断言会被「看起来对了」蒙混过去。
   *
   * <p>嵌套那一段是这条用例在 V23 之后新增的重点：以前侧栏只有两层（壳 → 项），
   * 现在渲染是递归的，某一层被写死成两层（或只渲染顶层）时，只有真正下钻过的断言才会红。
   *
   * <p>在工作台壳里验（登录后的落点就是 `/projects`，侧栏直接可见），不先进项目：
   * 少一段与菜单无关的流程，失败时更容易看出是哪一层坏了。
   */
  test('侧栏菜单树：按登记的顺序排，空目录按策略保留或隐藏，子菜单能下钻', async ({ page }) => {
    const a = `侧栏甲组-${stamp}`;
    const b = `侧栏乙组-${stamp}`;
    const kept = `空目录保留-${stamp}`;
    const hidden = `空目录隐藏-${stamp}`;
    const aPath = `/lineage/grp-a-${stamp}`;
    const bPath = `/lineage/grp-b-${stamp}`;
    const parent = `验证父-${stamp}`;
    const child = `验证子-${stamp}`;
    const grand = `验证孙-${stamp}`;

    const dirIds = await createNavDirs(page, [
      // 乙目录登记的排序更小 → 要排在甲目录前面
      { scope: 'workbench', title: b, sortOrder: 10 },
      { scope: 'workbench', title: a, sortOrder: 20 },
      // 一条菜单项都没有的两个目录：一个照常出现（里面放禁用说明），一个整支不出现
      { scope: 'workbench', title: kept, sortOrder: 30, emptyPolicy: 'always' },
      { scope: 'workbench', title: hidden, sortOrder: 40, emptyPolicy: 'hide' },
    ]);
    const nested: string[] = [];

    try {
      // 甲目录的项排序号更小：服务端按 sort_order 排，所以「首次出现序」里甲在前
      await configureNavItems(page, [
        { product: 'metadata', scope: 'workbench', groupTitle: a, label: `甲页-${stamp}`, path: aPath, perm: '', sortOrder: 0 },
        { product: 'metadata', scope: 'workbench', groupTitle: b, label: `乙页-${stamp}`, path: bPath, perm: '', sortOrder: 50 },
      ]);

      // 三级嵌套：父目录 › 子目录 › 叶子
      const p = await createNavNode(page, { scope: 'workbench', title: parent, path: '', sortOrder: 50 });
      const c = await createNavNode(page, { scope: 'workbench', parentId: p, title: child, path: '', sortOrder: 0 });
      const g = await createNavNode(page, {
        scope: 'workbench',
        parentId: c,
        title: grand,
        // 叶子得是个真能落的路由（org 自己的页面），否则渲染成「未配置」的置灰项
        path: '/org/workbench/knowledge',
        sortOrder: 0,
      });
      nested.push(g, c, p);

      await login(page, '张三', '123456');
      const nav = page.locator('aside nav');
      await expect(nav.getByText(`甲页-${stamp}`, { exact: true })).toBeVisible();
      await expect(nav.getByText(`乙页-${stamp}`, { exact: true })).toBeVisible();
      await expect(nav.getByText(kept, { exact: true })).toBeVisible();

      const titles = await nav.locator('.gtitle').allInnerTexts();
      const ia = titles.indexOf(a);
      const ib = titles.indexOf(b);
      expect(ia, `侧栏里没找到登记的「${a}」，实际顶层：${titles.join(' / ')}`).toBeGreaterThanOrEqual(0);
      expect(ib, `侧栏里没找到登记的「${b}」，实际顶层：${titles.join(' / ')}`).toBeGreaterThanOrEqual(0);
      expect(ib, `登记的排序没生效（首次出现序是「${a}」在前），实际：${titles.join(' / ')}`).toBeLessThan(ia);

      // 三级都在：少了孙这一层，说明渲染只往下走了一层
      await expect(nav.getByText(parent, { exact: true })).toBeVisible();
      await expect(nav.getByText(child, { exact: true })).toBeVisible();
      await expect(nav.getByText(grand, { exact: true })).toBeVisible();
      // 缩进线只在**深层**的子树上有（顶层目录是平铺标题）：父 › 子 › 孙 正好产生 1 条
      await expect(
        nav.locator('.kids.nested'),
        '深一层的子菜单应当有一条缩进线（顶层目录是平铺标题，不算）'
      ).toHaveCount(1);

      // 折叠：点「验证子」那一行的展开箭头，孙消失；再点回来。
      //
      // 按**行的文字**定位折叠头，不按 `.twist` 的第几个：只有深层目录才画折叠头
      // （顶层目录是平铺标题），所以「第几个」这个写法会在别的用例多建一层目录、
      // 或本用例换一处嵌套之后，悄悄指到另一行上去。
      const twist = nav.locator('.dir').filter({ hasText: child }).locator('.twist');
      await twist.click();
      // 收起走的是 `v-show`：元素还在 DOM 里，只是 `display: none`。断言「不可见」
      // 而不是 `toHaveCount(0)` —— 后者在这里永远不成立（会一直等到超时）。
      await expect(nav.getByText(grand, { exact: true })).toBeHidden();
      await twist.click();
      await expect(nav.getByText(grand, { exact: true })).toBeVisible();

      // always 空目录：整支在、入口置灰不可点、且**不是**链接
      const placeholder = nav.locator('.item.off').filter({ hasText: '暂无可用的入口' });
      await expect(placeholder).toHaveCount(1);
      await expect(placeholder.first()).toBeVisible();
      await expect(nav.locator('a').filter({ hasText: '暂无可用的入口' })).toHaveCount(0);

      // hide 空目录：整支不出现
      await expect(nav.getByText(hidden, { exact: true })).toHaveCount(0);
    } finally {
      await clearNavItems(page, [aPath, bPath]);
      await deleteNavItemIds(page, nested);
      await deleteNavNodes(page, dirIds);
    }
  });

  test('平台用户进 /admin', async ({ page }) => {
    await login(page, 'admin', '123456');
    await expect(page).toHaveURL(/\/org\/platform\//);
    await expect(page.getByRole('heading', { name: /租户/ })).toBeVisible();
    await openNav(page, '平台用户');
    await expect(page.getByRole('heading', { name: '平台用户' })).toBeVisible();
    await openNav(page, '设置');
    await expect(page.getByRole('heading', { name: '外观与布局' })).toBeVisible();
  });

  /**
   * 需求 ①：新增菜单 = 从产品**自报**的清单里挑一条 → 表单预填 → 可改 → 保存。
   *
   * <p>与 {@link configureNavItems} 那批用例刚好相反：这条用例的**被测对象就是菜单管理页的
   * 新增流程**，所以必须点界面（走接口配一遍等于把被测对象换成了接口）。
   *
   * <p>「预填的值来自产品的清单，不是前端写死的默认值」这条是整条需求的意义所在：少了它，
   * 用例在「表单里恰好有这几个值」时也会绿，而值从哪儿来就无从判断。所以这里刻意不手填
   * 任何一个字段，全靠「挑一条」那一步带出来。
   */
  test('菜单管理：新增菜单从产品候选起步，预填可改', async ({ page }) => {
    // 比默认的 60s 宽：这条要点两次弹窗、等 org 服务端去拉产品的 menu.json、再点一次保存，
    // 而链路上的每一次等待都可能是「产品前端冷启动」级别的慢
    test.setTimeout(120_000);
    // 前置：产品得在「服务注册」里有页面地址 —— 候选是 org 服务端去拉该产品的
    // `{frontendUrl}/menu.json`，没登记地址时候选列表是空的（这不是被测对象，缺了要明说）
    await requireRegisteredFrontend(page, 'metadata');

    const menuPath = '/lineage/search';
    const newLabel = `验证·全文检索-${stamp}`;
    // 执行前的 id 快照：清理时只删**这次多出来的**，环境的存量配置一条不碰
    const before = new Set((await navItemRows(page)).map((r) => r.id));

    try {
      await login(page, 'admin', '123456');
      await page.goto('/org/platform/nav-items');

      // 只能按 `.ant-btn-primary` 认工具栏上那一个：表格里两个壳根行（工作台壳 / 项目壳）
      // 的行内按钮文案也是「新增主菜单」（对壳根来说「新增子菜单」就是新增主菜单），
      // 单按名字会一次命中 3 个（strict mode 直接报出来，不会静默点错）
      await page.locator('button.ant-btn-primary').filter({ hasText: '新增主菜单' }).click();
      const form = page.locator('.ant-modal-content').filter({ hasText: '新增菜单' });
      await expect(form).toBeVisible({ timeout: 15_000 });

      // 来源切到「手工复制产品页面」后才会出现「挑一条」—— 这一步是整条用例的前提：
      // 预填值只能从产品清单来，不能是前端写死的默认值。
      await form.getByText('手工复制产品页面', { exact: true }).click();
      await btn(form, '挑一条').click();

      const pick = page.locator('.ant-modal-content').filter({ hasText: '从产品清单里挑一条' });
      await expect(pick).toBeVisible({ timeout: 15_000 });
      // 选择器必须**独占**：两个弹窗叠着时后开的那个会被前一个的遮罩盖住（管理页真机踩过 ——
      // 选择器整块被压在「新增菜单」下面，只看得见左半边）。只断言「可见」抓不到这种叠法，
      // 被盖住的元素照样算可见。也不能数 `.ant-modal-content` —— 关掉的弹窗 DOM 还在。
      await expect(
        page.locator('.ant-modal-wrap:visible'),
        '打开选择器时应当只剩它一个弹窗：叠着的话它会被表单那层的遮罩盖住'
      ).toHaveCount(1);
      await pick.locator('.ant-select').first().click();
      await page.locator(OPEN_OPTION).filter({ hasText: '元数据 / 血缘' }).first().click();

      // 树节点的标题是「菜单名（路径）」—— 按路径找，并确认权限词**原文**也在上面：
      // 管理员在这一步就得看出这条入口判的是什么词
      const candidate = pick.locator('.ant-tree-treenode').filter({ hasText: menuPath }).first();
      await expect(
        candidate,
        `产品「元数据 / 血缘」的清单里没有 ${menuPath} —— 这个环境的该产品前端没给出 menu.json？`
      ).toBeVisible({ timeout: 15_000 });
      await candidate.click();
      await expect(pick).toBeHidden({ timeout: 15_000 });

      // 预填：菜单名 / 路径 / 权限词 / 图标都取自候选
      await expect(field(form, '菜单名')).toHaveValue('全文检索');
      // 按字段标签的 `title` 精确定位，不用 `field()` 那套「整项文字」过滤：新表单里
      // 「从产品清单里取」那一项的帮助文字也含「路径」二字，按文字过滤会命中两项
      await expect(pathInputOf(form)).toHaveValue(menuPath);
      await expect(item(form, '权限词')).toContainText('查看目录');
      await expect(item(form, '图标')).toContainText('SearchOutlined');

      // 路径只读按**行为**验：属性名会骗人 —— `:read-only` 那个驼峰化 bug 渲染出的
      // `read-only="true"` 看着在锁、属性检查里也「有值」，实际能打字。所以真打一遍字。
      const pathInput = pathInputOf(form);
      await pathInput.click();
      await page.keyboard.type('XXX');
      await expect(
        pathInput,
        '路径应当只读：手改出来的路径点进去就是 404（页面的真实路由由产品自己定义）'
      ).toHaveValue(menuPath);

      // 需求 ① 的「默认取产品的，用户可以修改」：改名 + 改图标
      await field(form, '菜单名').fill(newLabel);
      const iconSel = item(form, '图标').locator('.ant-select').first();
      await iconSel.click();
      await page.locator(OPEN_OPTION).first().click();
      // 读**下拉框自己**显示的选中值，不是先读选项名再点：图标下拉是虚拟滚动的（几百项），
      // 读与点之间列表会滚，容易读到 A 点成 B。选中项的 `title` 比 innerText 稳 ——
      // 选中项里既有图标元素又有名字，innerText 会把名字念两遍。
      const chosenIcon = (
        (await iconSel.locator('.ant-select-selection-item').getAttribute('title')) ??
        (await iconSel.innerText())
      )
        .replace(/\s+/g, ' ')
        .trim()
        .split(' ')[0];
      expect(chosenIcon, '图标下拉没能选出一个图标').toBeTruthy();

      await btn(form, '保存').click();
      await expect(form).toBeHidden({ timeout: 15_000 });

      // 落库：路径 / 权限词 / 归属壳来自产品清单，菜单名与图标是管理员改过的
      const added = (await navItemRows(page)).filter((r) => !before.has(r.id) && r.path === menuPath);
      expect(added.length, `保存后没在 ${menuPath} 上找到新增的菜单项`).toBe(1);
      expect(added[0].label).toBe(newLabel);
      expect(added[0].icon).toBe(chosenIcon);
      expect(added[0].perm).toBe('catalog:read');
      expect(added[0].scope).toBe('project');
      // 手填进来的这一条是**顶层**的（没选父节点 = 主菜单）
      expect(added[0].parentId).toBe('');
      expect(added[0].mounted, '手工复制不是挂载：它是一条实实在在的副本').toBe(false);
    } finally {
      const after = await navItemRows(page);
      await deleteNavItemIds(
        page,
        after.filter((r) => !before.has(r.id)).map((r) => r.id)
      );
    }
  });

  /**
   * 项目壳：同一条菜单对**不同的人**结论不同 —— 判不动的入口不返回。
   *
   * <p>与上面「按能不能嵌分流」那条的分工：那条验的是「管理员配了什么就画什么」，
   * 这条验的是「按这个人在这个项目下的角色过滤」。两者都要有 —— 只有前者的话，
   * 过滤整个坏掉（谁都看得见判不动的入口，点进去必然 403）不会有任何用例红。
   */
  test('项目壳：侧栏按角色隐藏判不动的入口', async ({ page }) => {
    test.setTimeout(120_000);
    const readPath = `/lineage/nf-read-${stamp}`;
    const adminPath = `/lineage/nf-admin-${stamp}`;
    const readLabel = `验证·可读入口-${stamp}`;
    const adminLabel = `验证·管理入口-${stamp}`;
    const memberName = `nf_${stamp}`;

    await requireRegisteredFrontend(page, 'metadata');
    await configureNavItems(page, [
      {
        product: 'metadata',
        scope: 'project',
        groupTitle: `验证分组-${stamp}`,
        label: readLabel,
        path: readPath,
        perm: 'catalog:read',
      },
      {
        product: 'metadata',
        scope: 'project',
        groupTitle: `验证分组-${stamp}`,
        label: adminLabel,
        path: adminPath,
        perm: 'catalog:admin',
      },
    ]);
    // viewer：有 catalog:read，没有 catalog:admin —— 正好一条看得见、一条看不见
    const member = await createTenantMember(page, 'xinghe', memberName, 'metadata', 'viewer');

    try {
      await login(page, memberName, '123456');
      await page.goto('/org/workbench/projects');
      const enter = btn(page.locator('.projects-grid .proj').first(), '进入项目');
      await expect(enter, '成员进不了项目 —— 角色没派上？').toBeVisible({ timeout: 15_000 });
      await enter.click();
      await expect(page).toHaveURL(/\/org\/project\//, { timeout: 20_000 });

      const nav = page.locator('aside nav');
      await expect(nav.getByText(readLabel, { exact: true })).toBeVisible({ timeout: 15_000 });
      await expect(
        nav.getByText(adminLabel, { exact: true }),
        '判不动的入口不该出现在侧栏里：它的权限词是 catalog:admin，这个成员没有'
      ).toHaveCount(0);

      // 反面：租户管理员在本租户的产品里是**管理角色**，两条都该看得见 ——
      // 过滤不能是「谁都看不见」，否则上一条永远绿（用张三是因平台管理员进不了这个租户）
      const token = await tenantAdminToken(page);
      const res = await page.request.get('/api/v1/nav', {
        headers: {
          Authorization: `Bearer ${token}`,
          'X-Tenant-Code': 'xinghe',
          'X-Project-Code': member.projectCode,
        },
      });
      expect(res.ok(), `管理员读菜单失败：${res.status()} ${await res.text()}`).toBeTruthy();
      // `/api/v1/nav` 返回的是**树**（V23 起层级不限）—— 断言要递归展平，
      // 只看顶层会漏掉挂在目录下的那两条（它们现在正是目录的子节点）
      const labels = flattenLabels((await res.json()) as { label: string; children?: unknown[] }[]);
      expect(labels, `管理员的菜单应当两条都在，实际：${labels.join(' / ')}`).toContain(adminLabel);
      expect(labels).toContain(readLabel);
    } finally {
      await deleteTenantMember(page, 'xinghe', member.tenantId, member.id);
      await clearNavItems(page, [readPath, adminPath]);
    }
  });

  /**
   * 需求 ②：产品角色按**菜单**勾选来定义 —— 勾一个入口 = 给它挂的那个权限词。
   *
   * <p><b>勾选面来自产品自报的清单</b>（V23 起）：以前读的是管理员已配的门户菜单行，
   * 于是「有哪些权限可勾」取决于管理员往侧栏上摆了几条 —— 没摆上去的菜单、以及挂在
   * 挂载节点子树里的菜单都勾不到（挂载是「窗口」，管理面那行自己没有子节点）。
   * 权限面本来就该是产品说了算，所以改成拉 `nav-candidates`。
   *
   * <p>挑两条**权限词不同**的产品菜单（`全文检索` catalog:read / `数据目录` catalog:admin），
   * 只勾其中一条，断言落库的 `perms` **恰好**是那一条挂的词 —— 两条都勾就把错位掩盖了。
   */
  test('产品角色：按菜单勾选定义角色，落成菜单挂的权限词', async ({ page }) => {
    test.setTimeout(120_000);
    const readLabel = '全文检索';
    const adminLabel = '数据目录';
    const roleCode = `e2e_${stamp}`;
    const roleLabel = `验证·检索员-${stamp}`;

    // 前置：勾选面来自产品的清单，产品的页面地址得在「服务注册」里
    await requireRegisteredFrontend(page, 'metadata');

    try {
      await login(page, 'admin', '123456');
      await page.goto('/org/platform/product-roles');
      await page.locator('button.prod').filter({ hasText: '元数据 / 血缘' }).click();

      const table = page.locator('.role-col');
      // 内置三档随产品走，而且是**可管理的数据**（不是硬编码的只读矩阵）
      for (const label of ['目录管理员', '血缘分析', '只读']) {
        await expect(table.getByText(label, { exact: true }).first()).toBeVisible();
      }
      await expect(table.getByText('管理角色').first()).toBeVisible();

      await btn(page, '新增角色').click();
      const drawer = page.locator('.ant-drawer-content').filter({ hasText: '新增角色' });
      await expect(drawer).toBeVisible({ timeout: 15_000 });
      // 按 placeholder 定位，不按 label 文案过滤：这两项的帮助文字里也含「角色名」，
      // 按文案过滤会一次命中多个输入框（strict mode 会直接报出来，不会静默填错）
      await drawer.getByPlaceholder('血缘分析').fill(roleLabel);
      await drawer.getByPlaceholder('analyst').fill(roleCode);

      // 菜单树：每条叶子的名字带**产品自己的说法**（标签来自产品上报的词表）
      const readLeaf = drawer.locator('.ant-tree-treenode').filter({ hasText: readLabel });
      await expect(readLeaf).toHaveCount(1, { timeout: 15_000 });
      await expect(readLeaf).toContainText('查看目录');
      await expect(drawer.locator('.ant-tree-treenode').filter({ hasText: adminLabel })).toContainText(
        '管理目录'
      );

      await readLeaf.locator('.ant-tree-checkbox').click();
      await btn(drawer, '保存').click();
      await expect(page.locator('.ant-drawer-open')).toHaveCount(0, { timeout: 15_000 });

      const row = table.locator('tbody tr').filter({ hasText: roleLabel });
      await expect(row).toHaveCount(1, { timeout: 15_000 });
      await expect(row).toContainText('查看目录');

      // 落库的权限词必须**恰好**是勾中那条菜单挂的词（`catalog:admin` 那条没勾，不能在）
      const saved = (await productRoles(page, 'metadata')).find((r) => r.code === roleCode);
      expect(saved, `新建的角色 ${roleCode} 没落库`).toBeTruthy();
      expect(saved!.perms.map((p) => p.value)).toEqual(['catalog:read']);
    } finally {
      await deleteProductRoles(page, 'metadata', [roleCode]);
    }
  });

  /**
   * 项目壳的「成员管理」页 —— 壳**自己**的功能，不是任何服务报上来的页面。
   *
   * <p>钉三件事，各对应一种真实的坏法：
   *
   * <ol>
   *   <li>菜单项与页面都在。**标签必须是「成员管理」**：仓建设报上来的候选里已经有一条
   *       label 恰为「项目成员」的菜单（见上面「挂上去的产品菜单按能不能嵌分流」那条），
   *       固定项再叫「项目成员」就是侧栏里两个文本完全相同的项 —— 对用户是两个一样的入口，
   *       对 Playwright 的 strict mode 是直接报错。</li>
   *   <li><b>改完角色要真的落库</b>。页面不做乐观更新，所以这里改完再 `reload()` 一次：
   *       只断言下拉当场变成新值的话，「本地改了、远端没改」与「远端改了」看起来一模一样。</li>
   *   <li>「移出」清掉的是该人在本项目**所有产品**的行（不是某一个），断言整行消失。</li>
   * </ol>
   *
   * <p>全程用**自己造的临时成员**，不碰种子里的张三/李四：这条用例里有 PUT 与 DELETE，
   * 写坏种子会让后续任何手验都对不上账。
   */
  test('项目壳：成员管理页能改角色、能移出', async ({ page }) => {
    test.setTimeout(120_000);
    const memberName = `pm_${stamp}`;
    const display = `验证成员-${stamp}`;
    const member = await createTenantMember(page, 'xinghe', memberName, 'warehouse', 'viewer');

    try {
      // 张三（租户管理员，在本项目是 warehouse admin）—— 只有他有 iam:member
      await login(page, '张三', '123456');
      await page.goto(`/org/project/${encodeURIComponent(member.projectCode)}/members`);

      await expect(page.getByRole('heading', { name: '成员管理' })).toBeVisible({ timeout: 20_000 });
      const nav = page.locator('aside nav');
      await expect(nav.getByText('成员管理', { exact: true })).toBeVisible();
      // 「添加成员」的候选名单只对组织管理员开放，张三满足
      await expect(btn(page, '添加成员')).toBeVisible();

      const row = page.locator('tbody tr').filter({ hasText: display });
      await expect(row, '临时成员应当出现在成员表里').toHaveCount(1, { timeout: 15_000 });
      // 登录名列不能退化成内部主键（后端不带名字时正是那个症状）
      await expect(row).toContainText(memberName);

      // 改角色：只读访客 → 建模工程师。行内第一个下拉是仓建设那两个产品里靠前的那个。
      await row.locator('.ant-select').first().click();
      await page.locator(OPEN_OPTION).filter({ hasText: '建模工程师' }).first().click();
      await expect(row.locator('.ant-select').first()).toContainText('建模工程师', { timeout: 15_000 });

      // 重进这一页：值必须来自远端（页面不做乐观更新，本地不留脏数据）
      await page.reload();
      const reloaded = page.locator('tbody tr').filter({ hasText: display });
      await expect(reloaded.locator('.ant-select').first()).toContainText('建模工程师', {
        timeout: 15_000,
      });

      // 移出：整行消失。popconfirm 的确认按钮与行内触发按钮同名，按弹层定位。
      await reloaded.getByRole('button', { name: '移出' }).click();
      await page
        .locator('.ant-popover:visible')
        .getByRole('button', { name: buttonName('移出') })
        .click();
      await expect(page.locator('tbody tr').filter({ hasText: display })).toHaveCount(0, {
        timeout: 15_000,
      });
    } finally {
      await deleteTenantMember(page, 'xinghe', member.tenantId, member.id);
    }
  });

  /**
   * 没权限的人看到的是**置灰的**「成员管理」，不是消失的。
   *
   * <p>这是本仓既有的口径（V18 迁移顶部写过「一个空侧栏会让人以为服务坏了」）：
   * 普通成员看到置灰项，知道这里有个功能、只是自己动不了；整项不显示则让他以为这个项目
   * 压根没有成员管理。`disabledReason` 挂在 `title` 上，悬停能看到原因。
   *
   * <p>顺带钉住「置灰项不是链接」——`AppNav` 对 `disabled` 项渲染的是 `span.item.off`。
   * 哪天改成照旧渲染 `router-link`，用户点一下就进了 403 页面。
   */
  test('项目壳：无权限的人看到置灰的「成员管理」', async ({ page }) => {
    test.setTimeout(120_000);
    const memberName = `pmv_${stamp}`;
    // warehouse/viewer：进得了项目，但没有 iam:member
    const member = await createTenantMember(page, 'xinghe', memberName, 'warehouse', 'viewer');

    try {
      await login(page, memberName, '123456');
      await page.goto(`/org/project/${encodeURIComponent(member.projectCode)}`);

      const nav = page.locator('aside nav');
      await expect(nav.getByText('成员管理', { exact: true })).toBeVisible({ timeout: 20_000 });
      await expect(
        nav.locator('span.item.off').filter({ hasText: '成员管理' }),
        '没权限时应当是置灰的 span，而不是可点的链接'
      ).toHaveCount(1);
      await expect(nav.locator('a[href$="/members"]')).toHaveCount(0);
    } finally {
      await deleteTenantMember(page, 'xinghe', member.tenantId, member.id);
    }
  });

  /**
   * 挂载产品节点：挂的是产品清单里的**一个节点**（`ref`），它的内容由产品在渲染时提供，
   * 产品发版后 org 侧栏自动跟上。
   *
   * <p>这条用例的四个断言各自钉住一条规则：
   *
   * <ol>
   *   <li>展开出来的菜单项**一条都不落库**，侧栏里那一支照样出现（不是「一次性复制」那条老路）</li>
   *   <li>产品清单多一条菜单 → **不做任何管理动作**，刷新就出现（这是需求的核心）</li>
   *   <li>同路径的手工行被接管：不重复出现，也不再显示旧行</li>
   *   <li>删掉挂载行 → 展开项消失，手工行照常渲染（人工兜底路径不断）</li>
   * </ol>
   *
   * <p>这里刻意挂的是产品树里的一个**目录节点**而不是叶子：`ref` 指向中间层时，
   * 它下面的整棵子树都要跟着进来 —— 这是 V23 相对 V22「挂一个组名」的主要变化
   * （用户 2026-09-27：「产品可以报任意层级树」）。挂载行的 `path` 因此是空的，
   * 断言②能过就说明子树真的展开了。
   *
   * <p><b>环境前置</b>：第 2 条要求 org 后端带 `DWAI_NAV_CANDIDATE_TTL_SECONDS=0`。
   * 候选清单默认缓存 300 秒（挡的是侧栏每次渲染都去打产品），增删改一个节点会主动失效缓存，
   * 但「产品自己改了清单」不会 —— 默认配置下这条最多要等 5 分钟。断言消息里写明了这一点。
   */
  test('挂载产品节点：产品清单变了，不做任何管理动作侧栏就跟上', async ({ page }) => {
    test.setTimeout(150_000);
    const dir = `挂载目录-${stamp}`;
    /** 产品清单里那个目录节点的 id —— 挂载行引用的就是它。 */
    const dirId = `stub-dir-${stamp}`;
    const pathA = `/mount-a-${stamp}`;
    const pathB = `/mount-b-${stamp}`;
    const labelA = `挂载项甲-${stamp}`;
    const labelB = `挂载项乙-${stamp}`;
    const stale = `接管旧行-${stamp}`;

    const stub = await startMenuStub(
      menusJson([
        { id: dirId, path: '', label: dir, sort: 10, children: [{ path: pathA, label: labelA, sort: 10 }] },
      ])
    );
    const original = await frontendUrlOf(page, 'metadata');
    await registerService(page, 'metadata', stub.base);
    const mountedId = await createNavNode(page, {
      scope: 'project',
      title: dir,
      path: '',
      icon: 'FolderOutlined',
      product: 'metadata',
      ref: dirId,
      mounted: true,
      emptyPolicy: 'hide',
    });

    try {
      // ① 挂上即出现，且展开出来的菜单项一条都没落库
      await login(page, '张三', '123456');
      await openNav(page, '项目管理');
      await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
      await expect(page).toHaveURL(/\/org\/project\//);
      const nav = page.locator('aside nav');
      await expect(nav.getByText(labelA, { exact: true })).toBeVisible({ timeout: 20_000 });
      expect(
        (await navItemRows(page)).some((r) => r.path === pathA),
        '展开出来的菜单项不该落成 nav_nodes 行 —— 落成了说明走的还是「一次性复制」那条老路'
      ).toBe(false);

      // ② 产品发版：只改桩返回的清单，org 这边一个管理动作都不做
      stub.body = menusJson([
        {
          id: dirId,
          path: '',
          label: dir,
          sort: 10,
          children: [
            { path: pathA, label: labelA, sort: 10 },
            { path: pathB, label: labelB, sort: 5 },
          ],
        },
      ]);
      await page.reload();
      await expect(
        nav.getByText(labelB, { exact: true }),
        '产品清单加了子菜单但侧栏没跟上。若只在某台机器上红，先确认那台的 org 后端带了 ' +
          'DWAI_NAV_CANDIDATE_TTL_SECONDS=0（默认 300 秒缓存，管理动作之外不会主动失效）'
      ).toBeVisible({ timeout: 20_000 });

      // ③ 同路径的手工行被接管：展开项出现一次、旧行不再出现
      await configureNavItems(page, [{ product: 'metadata', scope: 'project', label: stale, path: pathA }]);
      await page.reload();
      await expect(nav.getByText(labelA, { exact: true })).toHaveCount(1, { timeout: 20_000 });
      await expect(nav.getByText(stale, { exact: true })).toHaveCount(0);

      // ④ 删掉挂载行：展开项消失，那条手工行回到台前
      await deleteNavNodes(page, [mountedId]);
      await page.reload();
      await expect(nav.getByText(labelA, { exact: true })).toHaveCount(0);
      await expect(nav.getByText(stale, { exact: true })).toBeVisible({ timeout: 20_000 });
    } finally {
      await clearNavItems(page, [pathA, pathB]);
      await deleteNavNodes(page, [mountedId]);
      if (original) await registerService(page, 'metadata', original);
      await stub.close();
    }
  });

  /**
   * 挂载产品节点：**一个目录里混装两个产品的菜单**。
   *
   * <p>这是用户拍板要的语义（2026-09-27）：「分组是 org 的壳的分组，不应该和产品强绑定，
   * 可能一个分组中既有 model 的菜单也有 lineage 的菜单。」V23 之后这件事变成了最朴素的
   * 一层父子关系：org 建**一个目录节点**，下面挂两条 `mounted` 行，各引用不同产品清单里的
   * 节点。「分组身份 = 壳 + 组名」那套约束随之消失 —— 目录就是个普通节点，谁都能挂到它下面。
   *
   * <p>钉住三条：
   *
   * <ol>
   *   <li>侧栏里该目录标题**只出现一次** —— 两条挂载行是同层的兄弟，各自展开自己那一支，
   *       不需要任何「同名合并」逻辑（同名合并是旧模型里按 `groupTitle` 分桶才有的东西）</li>
   *   <li>两个产品的菜单项都在这个目录里（各自的产品不同，所以两边的清单都要真拉到）</li>
   *   <li>两个叶子都**没有**被复制成 `nav_nodes` 行 —— 混装靠的是展开，不是复制</li>
   * </ol>
   *
   * <p>两个桩各自自报正确的 `product`：org 会拿清单信封里的 `product` 核对
   * 「页面地址有没有填串」（见 {@link menusJson}），报错了整份拒掉，症状会变成
   * 「其中一个产品的项没出现」，与目录语义无关。
   */
  test('挂载产品节点：一个目录里混装两个产品的菜单', async ({ page }) => {
    test.setTimeout(120_000);
    const dir = `混装目录-${stamp}`;
    const mdPath = `/mix-a-${stamp}`;
    const whPath = `/mix-b-${stamp}`;
    const mdLabel = `混装血缘-${stamp}`;
    const whLabel = `混装模型-${stamp}`;

    const mdStub = await startMenuStub(menusJson([{ path: mdPath, label: mdLabel, sort: 10 }]));
    const whStub = await startMenuStub(
      menusJson([{ path: whPath, label: whLabel, sort: 10 }], 'warehouse')
    );
    const originalMd = await frontendUrlOf(page, 'metadata');
    const originalWh = await frontendUrlOf(page, 'warehouse');
    await registerService(page, 'metadata', mdStub.base);
    await registerService(page, 'warehouse', whStub.base);

    // ref 从产品自报的清单里取（管理员在界面上也是这么选的），不硬编码 id 生成规则
    const mdRef = await navCandRef(page, 'metadata', mdLabel);
    const whRef = await navCandRef(page, 'warehouse', whLabel);
    const dirIds = await createNavDirs(page, [{ scope: 'project', title: dir, sortOrder: 5 }]);
    const mountedIds = [
      await createNavNode(page, {
        scope: 'project',
        parentId: dirIds[0],
        title: mdLabel,
        path: '',
        icon: 'FolderOutlined',
        product: 'metadata',
        ref: mdRef,
        mounted: true,
        emptyPolicy: 'hide',
      }),
      await createNavNode(page, {
        scope: 'project',
        parentId: dirIds[0],
        title: whLabel,
        path: '',
        icon: 'FolderOutlined',
        product: 'warehouse',
        ref: whRef,
        mounted: true,
        emptyPolicy: 'hide',
      }),
    ];

    try {
      await login(page, '张三', '123456');
      await openNav(page, '项目管理');
      await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
      await expect(page).toHaveURL(/\/org\/project\//);

      const nav = page.locator('aside nav');
      await expect(nav.getByText(mdLabel, { exact: true }), 'metadata 挂的项没出现在项目壳里').toBeVisible({
        timeout: 20_000,
      });
      await expect(nav.getByText(whLabel, { exact: true }), 'warehouse 挂的项没出现在项目壳里').toBeVisible({
        timeout: 20_000,
      });

      await expect(
        nav.locator('.gtitle').filter({ hasText: dir }),
        '两条挂载行挂的是同一个目录，侧栏里就该是**一个**目录、标题只出现一次'
      ).toHaveCount(1);

      const rows = await navItemRows(page);
      for (const p of [mdPath, whPath]) {
        expect(
          rows.some((r) => r.path === p),
          `混装靠的是展开，不该往 nav_nodes 里复制 ${p}`
        ).toBe(false);
      }
    } finally {
      await clearNavItems(page, [mdPath, whPath]);
      await deleteNavItemIds(page, mountedIds);
      await deleteNavNodes(page, dirIds);
      if (originalMd) await registerService(page, 'metadata', originalMd);
      if (originalWh) await registerService(page, 'warehouse', originalWh);
      await mdStub.close();
      await whStub.close();
    }
  });

  /**
   * 项目壳的「收起」（drawer）风格：顶栏 + 抽屉 + 快捷栏 —— 0.2.0 原型的三件。
   *
   * <p>量一下抽屉的位置是为了守住一个**定位**前提：`.drawer` 是 `absolute` + `top: 100%`，
   * 而本仓库此前没有任何 `position: relative` 的祖先（含 html/body/#app），包含块会一直
   * 退到视口 —— 抽屉整个落到屏幕外，表现是「点了汉堡什么都没发生」。所以要求它贴在顶栏下沿。
   */
  test('项目壳：收起风格下的顶栏、抽屉与快捷栏', async ({ page }) => {
    await setAppearance(page, 'project', { menuPos: 'drawer' });
    try {
      await login(page, '张三', '123456');
      await openNav(page, '项目管理');
      await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
      await expect(page).toHaveURL(/\/org\/project\//);

      // 顶栏在、常驻侧栏不在 —— drawer 的全部意义就在这里
      const bar = page.locator('.bar');
      await expect(bar).toBeVisible();
      // 租户名（`canBackHome` 为真时是可点的 `<a class="home">`，否则只是一行字 ——
      // 两种都算数，这里断言的是「名字在顶栏里」）。
      await expect(bar.locator('.home, .tenant-name').first()).toContainText('星河');
      await expect(page.locator('aside.sidebar')).toHaveCount(0);
      // 项目首页不在任何主菜单里，所以快捷栏不出现（快捷栏是「当前主菜单的快捷项」）
      await expect(page.locator('.shortcut')).toHaveCount(0);

      // 汉堡在顶栏里；点开抽屉，抽屉挂在顶栏正下方
      await bar.locator('.burger').click();
      const drawer = bar.locator('.drawer');
      await expect(drawer).toBeVisible();
      await expect(drawer.locator('.cats')).toBeVisible();
      const barBox = (await bar.boundingBox())!;
      const drawerBox = (await drawer.boundingBox())!;
      expect(drawerBox.y, '抽屉要挂在顶栏正下方，而不是屏幕外').toBeGreaterThanOrEqual(
        barBox.y + barBox.height - 1
      );

      // 抽屉里点「项目」这个有子项的分类 → 右侧出面板 → 点「成员管理」
      await drawer.locator('.cat').filter({ hasText: '项目' }).first().hover();
      const panel = drawer.locator('.panel');
      await expect(panel.getByText('成员管理', { exact: true })).toBeVisible();
      await panel.getByText('成员管理', { exact: true }).click();
      await expect(page).toHaveURL(/\/members$/);

      // 进到主菜单里的页面 → 左侧出现快捷栏，列的就是这一支下面的项
      const shortcut = page.locator('.shortcut');
      await expect(shortcut).toBeVisible();
      await expect(shortcut.getByText('外观', { exact: true })).toBeVisible();

      // 收起：`<` 按钮把它折起来，并把状态记进 localStorage
      await shortcut.locator('.fold').click();
      await expect(page.locator('.shortcut')).toHaveClass(/collapsed/);
      expect(await page.evaluate(() => localStorage.getItem('dw-ai.shortcutCollapsed'))).toBe('1');
    } finally {
      await setAppearance(page, 'project', { menuPos: 'left' });
    }
  });

  /**
   * 项目壳的「设置 → 外观」：PRD §4 第 7 条。
   *
   * <p>作用域是 `'project'` —— 与工作台「设置」那套（`'workbench'`）**各存各的**，
   * 改哪边都不影响另一边（见下面「外观按壳隔离」那条）。所以这里断言的是
   * 「这个入口进得去、表单在」，而不是它自己另存了一份。
   */
  test('项目壳：设置 → 外观', async ({ page }) => {
    await login(page, '张三', '123456');
    await openNav(page, '项目管理');
    await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
    await expect(page).toHaveURL(/\/org\/project\//);

    await openNav(page, '外观');
    await expect(page).toHaveURL(/\/org\/project\/[^/]+\/settings\/nav$/);
    await expect(page.getByRole('heading', { name: '外观' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '主题' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '菜单栏颜色' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '菜单风格' })).toBeVisible();
  });

  /**
   * 工作台壳与项目壳的外观**各管各的** —— 专为这次拆分写的一条。
   *
   * <p>此前两者共用一行（`scope='tenant'`）：在项目「设置 → 外观」里改菜单风格或主题，
   * 工作台跟着变。这里把两套**同时**设成互不相同的值，再分别进两个壳看：菜单风格看
   * `aside` / `.bar` 谁在，主题看 `documentElement.dataset.theme`。
   *
   * <p>两个壳设的都是与 `beforeAll` 不同的值（left→drawer、cyan→dark），
   * 否则「读到的其实是 beforeAll 钉的那份」会让断言假绿。
   */
  test('外观按壳隔离：改项目不影响工作台', async ({ page }) => {
    await setAppearance(page, 'workbench', { theme: 'green', menuPos: 'left', menuColor: 'ink' });
    await setAppearance(page, 'project', { theme: 'dark', menuPos: 'drawer', menuColor: 'ink' });
    try {
      await login(page, '张三', '123456');

      // 工作台壳：左侧栏在、项目壳那条顶栏不在；主题是工作台那份
      await expect(page).toHaveURL(/\/projects/);
      await expect(page.locator('aside.sidebar')).toBeVisible();
      await expect(page.locator('.bar')).toHaveCount(0);
      expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe('green');

      // 进项目壳（SPA 内导航，不重新加载）：反过来 —— 顶栏在、常驻侧栏不在，
      // 主题换成项目那份。这一步是「同一次会话里换壳就换外观」的判据。
      await openNav(page, '项目管理');
      await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
      await expect(page).toHaveURL(/\/org\/project\//);
      await expect(page.locator('.bar')).toBeVisible();
      await expect(page.locator('aside.sidebar')).toHaveCount(0);
      expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe('dark');

      // 回工作台：又变回绿色。走一次真实加载（而不是后退），顺带证明工作台那份
      // 没有被「最后一次用过的值」覆盖掉。
      await page.goto('/org/workbench/projects');
      await expect(page.locator('aside.sidebar')).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe('green');
    } finally {
      await setAppearance(page, 'workbench', { theme: 'cyan', menuPos: 'left', menuColor: 'ink' });
      await setAppearance(page, 'project', { theme: 'cyan', menuPos: 'left', menuColor: 'ink' });
    }
  });

  /**
   * 项目切换器：守在顶栏**右侧**，而且换项目**不换页面**。
   *
   * <p>两条都是用户报的。第二条是真缺陷：`onPick` 里无条件
   * `router.push('/org/project/{新码}')` —— 切完项目就被丢到项目首页（菜单里第一条
   * 能嵌的产品页面），正在看的那一页没了。
   *
   * <p>为什么拿「成员管理」当那一页：它是项目壳**自己**的页面，不依赖产品菜单与服务
   * 注册，两个项目下都存在 —— 能干净地验「子路径原样保留」，不会把「菜单没配」和
   * 「路径丢了」混在一起。
   *
   * <p>临时项目自己造、自己删：`ProjectSwitcher` 在只有一个项目时**不渲染**
   * （`options.length > 1`），借库里已有的项目则会把用例绑死在某个特定库上。
   */
  test('项目切换：换项目不换页面，切换器在顶栏右侧', async ({ page }) => {
    test.setTimeout(120_000);
    const tid = await xingheTenantId(page);
    const headers = {
      Authorization: `Bearer ${await tenantAdminToken(page)}`,
      'X-Tenant-Code': 'xinghe',
    };
    const altCode = `e2e_alt_${stamp}`;

    const created = await page.request.post(`/api/v1/tenants/${tid}/projects`, {
      headers,
      data: { code: altCode, name: `E2E临时项目${stamp}` },
    });
    expect(created.ok(), `建临时项目失败：${created.status()} ${await created.text()}`).toBeTruthy();
    const altId = (await created.json()).id as string;

    try {
      await login(page, '张三', '123456');
      await openNav(page, '项目管理');
      // 进**另一个**项目：若进的就是待会儿要切过去的那个，`onPick` 会因为
      // 「选中的就是当前项目」直接返回，用例会变成什么都没验。
      await btn(
        page.locator('.projects-grid .proj').filter({ hasNotText: altCode }).first(),
        '进入项目'
      ).click();
      await expect(page).toHaveURL(/\/org\/project\//);

      // 走到项目壳里的一页（不是首页）
      await openNav(page, '成员管理');
      await expect(page).toHaveURL(/\/members$/);

      // 切换器在顶栏的**右半边** —— 它原先挤在左上角租户名旁边，看着像「租户」的一部分
      const bar = page.locator('.bar');
      const switcher = bar.locator('.ant-select:visible').first();
      await expect(switcher).toBeVisible();
      const barBox = (await bar.boundingBox())!;
      const swBox = (await switcher.boundingBox())!;
      expect(swBox.x, '项目切换器应当靠顶栏右侧').toBeGreaterThan(barBox.x + barBox.width / 2);

      // 换项目：只有项目码变，子路径（/members）不动
      await switcher.click();
      await page.locator(OPEN_OPTION).filter({ hasText: altCode }).first().click();
      await expect(page).toHaveURL(new RegExp(`/org/project/${altCode}/members$`), {
        timeout: 15_000,
      });
      // 停在原来那一页，而不是被弹回项目首页
      await expect(page.getByRole('heading', { name: /成员管理/ })).toBeVisible();
    } finally {
      await page.request.delete(`/api/v1/tenants/${tid}/projects/${altId}`, { headers });
    }
  });
});
