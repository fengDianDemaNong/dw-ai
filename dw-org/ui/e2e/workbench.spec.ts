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

/**
 * 以平台管理员身份配一批菜单项（走真实的批量接口，与「菜单管理」页同一个）。
 *
 * <p>用接口而不是点界面：菜单是这些用例的**前置条件**，不是被测对象 ——
 * 用界面配一遍会把「菜单管理页能不能用」的失败混进「项目壳对不对」的失败里。
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
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const batch = await page.request.post('/api/v1/platform/nav-items/batch', { headers, data: { items } });
  expect(batch.ok(), `配菜单失败：${batch.status()} ${await batch.text()}`).toBeTruthy();
}

/**
 * 登记一批侧栏分组（走真实的 `nav_groups` 接口），返回 id 供 {@link deleteNavGroups} 清理。
 *
 * <p>尽量在本用例里建**自己标题**的分组（标题带 {@link stamp}）并只清自己建的：
 * 一次性删光所有分组会把别人（或上一次跑残留）的配置一起带走。
 */
async function createNavGroups(
  page: Page,
  groups: { scope: string; product: string; title: string; sortOrder?: number; emptyPolicy?: string }[]
): Promise<string[]> {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const ids: string[] = [];
  for (const g of groups) {
    const res = await page.request.post('/api/v1/platform/nav-groups', { headers, data: g });
    expect(res.ok(), `登记分组「${g.title}」失败：${res.status()} ${await res.text()}`).toBeTruthy();
    ids.push((await res.json()).id as string);
  }
  return ids;
}

async function deleteNavGroups(page: Page, ids: string[]) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  for (const id of ids) {
    await page.request.delete(`/api/v1/platform/nav-groups/${encodeURIComponent(id)}`, { headers });
  }
}

/** 删掉这些 path 上的菜单项（同一条路径可能挂在两个壳上，两个都清）。 */
async function clearNavItems(page: Page, paths: string[]) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const list = await page.request.get('/api/v1/platform/nav-items', { headers });
  for (const row of (await list.json()) as { id: string; path: string }[]) {
    if (paths.includes(row.path)) {
      await page.request.delete(`/api/v1/platform/nav-items/${encodeURIComponent(row.id)}`, { headers });
    }
  }
}

/** 菜单项的完整行（断言预填/保存结果用）。 */
type NavRow = {
  id: string;
  product: string;
  scope: string;
  label: string;
  path: string;
  icon: string;
  perm: string;
  groupTitle: string;
};

async function navItemRows(page: Page): Promise<NavRow[]> {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  const res = await page.request.get('/api/v1/platform/nav-items', { headers });
  expect(res.ok(), `读菜单列表失败：${res.status()}`).toBeTruthy();
  return (await res.json()) as NavRow[];
}

async function deleteNavItemIds(page: Page, ids: string[]) {
  const headers = { Authorization: `Bearer ${await adminToken(page)}` };
  for (const id of ids) {
    await page.request.delete(`/api/v1/platform/nav-items/${encodeURIComponent(id)}`, { headers });
  }
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

test.describe.serial('工作台前端流程', () => {
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

    await requireRegisteredFrontend(page, 'metadata');
    await requireRegisteredFrontend(page, 'warehouse');
    // 数据质量没有子端接收端（见 config/products.ts 的 EMBEDDABLE），拿它当「外链」那一支的样本。
    // 地址是随便指的：这一支只断言 href 拼得对，不会真去打开那个站点。
    await registerService(page, 'quality', 'http://127.0.0.1:5181');
    const granted = await grantModule(page, 'xinghe', 'quality');

    try {

      await configureNavItems(page, [
        {
          product: 'metadata',
          scope: 'project',
          groupTitle: '数据地图',
          label: '血缘分析',
          path: metadataPath,
          perm: '',
        },
        {
          product: 'warehouse',
          scope: 'project',
          groupTitle: '仓建设',
          label: '项目成员',
          path: warehousePath,
        },
        {
          product: 'quality',
          scope: 'project',
          groupTitle: '数据质量',
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
      await expect(nav.getByText('数据地图', { exact: true })).toBeVisible();
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
      await expect(nav.getByText('仓建设', { exact: true })).toBeVisible();
      await expect(nav.getByText('项目成员', { exact: true })).toBeVisible();
      await expect(nav.locator('a[target="_blank"]').filter({ hasText: '项目成员' })).toHaveCount(0);

      // 数据质量那个入口是「去别的站点」，不是本站的一次导航 —— 必须新标签页打开，
      // 且地址要带上是哪一页（只跳站点根会落到首页，用户点的是「质量规则」）
      const external = nav.locator('a[target="_blank"]').filter({ hasText: '质量规则' });
      await expect(external).toBeVisible();
      expect(await external.getAttribute('href')).toContain('/quality/rules');

      // 同一个壳里「返回工作台」始终在，否则进了项目出不去
      await expect(nav.getByText('返回工作台', { exact: true })).toBeVisible();
    } finally {
      await clearNavItems(page, [metadataPath, warehousePath, qualityPath]);
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
   * 侧栏分组：组间顺序按**登记**的 `sortOrder`，空组按 `empty_policy` 保留或隐藏。
   *
   * <p>顺序这条刻意构造反例：甲组的菜单项 `sortOrder` 更小（服务端因此先给出它，也就是
   * 「首次出现序」里甲在前），但登记时乙组的组间顺序更靠前。侧栏必须按**登记的顺序**排 ——
   * 不构造这个反例，两种规则下结果一样，断言会被「看起来对了」蒙混过去。
   *
   * <p>在工作台壳里验（登录后的落点就是 `/projects`，侧栏直接可见），不先进项目：
   * 少一段与分组无关的流程，失败时更容易看出是哪一层坏了。
   */
  test('侧栏分组：按登记的顺序排，空组按策略保留或隐藏', async ({ page }) => {
    const a = `侧栏甲组-${stamp}`;
    const b = `侧栏乙组-${stamp}`;
    const kept = `空组保留-${stamp}`;
    const hidden = `空组隐藏-${stamp}`;
    const aPath = `/lineage/grp-a-${stamp}`;
    const bPath = `/lineage/grp-b-${stamp}`;

    const groupIds = await createNavGroups(page, [
      // 乙组登记的组间顺序更小 → 要排在甲组前面
      { scope: 'workbench', product: 'metadata', title: b, sortOrder: 10, emptyPolicy: 'hide' },
      { scope: 'workbench', product: 'metadata', title: a, sortOrder: 20, emptyPolicy: 'hide' },
      // 一条菜单项都没有的两个组：一个照常出现（里面放禁用说明），一个整组不出现
      { scope: 'workbench', product: 'metadata', title: kept, sortOrder: 30, emptyPolicy: 'always' },
      { scope: 'workbench', product: 'metadata', title: hidden, sortOrder: 40, emptyPolicy: 'hide' },
    ]);

    try {
      // 甲组的项排序号更小：服务端按 sort_order 排，所以「首次出现序」里甲组在前
      await configureNavItems(page, [
        { product: 'metadata', scope: 'workbench', groupTitle: a, label: `甲页-${stamp}`, path: aPath, perm: '', sortOrder: 0 },
        { product: 'metadata', scope: 'workbench', groupTitle: b, label: `乙页-${stamp}`, path: bPath, perm: '', sortOrder: 50 },
      ]);

      await login(page, '张三', '123456');
      const nav = page.locator('aside nav');
      await expect(nav.getByText(`甲页-${stamp}`, { exact: true })).toBeVisible();
      await expect(nav.getByText(`乙页-${stamp}`, { exact: true })).toBeVisible();
      await expect(nav.getByText(kept, { exact: true })).toBeVisible();

      const titles = await nav.locator('.gtitle').allInnerTexts();
      const ia = titles.indexOf(a);
      const ib = titles.indexOf(b);
      expect(ia, `侧栏里没找到登记的「${a}」，实际分组：${titles.join(' / ')}`).toBeGreaterThanOrEqual(0);
      expect(ib, `侧栏里没找到登记的「${b}」，实际分组：${titles.join(' / ')}`).toBeGreaterThanOrEqual(0);
      expect(ib, `登记的组间顺序没生效（首次出现序是「${a}」在前），实际：${titles.join(' / ')}`).toBeLessThan(ia);

      // always 空组：整组在、入口置灰不可点、且**不是**链接
      const placeholder = nav.locator('.item.off').filter({ hasText: '暂无可用的入口' });
      await expect(placeholder).toHaveCount(1);
      await expect(placeholder.first()).toBeVisible();
      await expect(nav.locator('a').filter({ hasText: '暂无可用的入口' })).toHaveCount(0);

      // hide 空组：整组不出现
      await expect(nav.getByText(hidden, { exact: true })).toHaveCount(0);
    } finally {
      await clearNavItems(page, [aPath, bPath]);
      await deleteNavGroups(page, groupIds);
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
   * 需求 ①：新增菜单 = 先选产品 → 从产品**自报**的候选里挑一条 → 表单预填 → 可改 → 保存。
   *
   * <p>与 {@link configureNavItems} 那批用例刚好相反：这条用例的**被测对象就是菜单管理页的
   * 新增流程**，所以必须点界面（走接口配一遍等于把被测对象换成了接口）。
   *
   * <p>「第一步弹出来的是产品选择，不是一张空表单」这条断言是整条需求的意义所在：少了它，
   * 用例在「表单里恰好有这几个值」时也会绿，而预填值从哪儿来（产品自报的候选 vs 前端写死的
   * 默认值）就无从判断。
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

      await btn(page, '新增菜单').click();
      const pick = page.locator('.ant-modal-content').filter({ hasText: '新增菜单 · 从产品取' });
      await expect(pick).toBeVisible({ timeout: 15_000 });

      await pick.locator('.ant-select').first().click();
      await page.locator(OPEN_OPTION).filter({ hasText: '元数据 / 血缘' }).first().click();

      const candidate = pick.locator('tbody tr').filter({ hasText: menuPath }).first();
      await expect(
        candidate,
        `产品「元数据 / 血缘」的候选里没有 ${menuPath} —— 这个环境的该产品前端没给出 menu.json？`
      ).toBeVisible({ timeout: 15_000 });
      // 候选行要把权限词**原文**带出来：管理员在这一步就得看出这条入口判的是什么词
      await expect(candidate).toContainText('catalog:read');
      await candidate.getByRole('button', { name: '选它' }).click();

      const form = page.locator('.ant-modal-content').filter({ hasText: '新增菜单 · 来自候选' });
      await expect(form).toBeVisible({ timeout: 15_000 });

      // 预填：菜单名 / 路径 / 权限词 / 图标都取自候选
      await expect(field(form, '菜单名')).toHaveValue('全文检索');
      await expect(field(form, '子应用路径')).toHaveValue(menuPath);
      await expect(item(form, '权限词')).toContainText('查看目录');
      await expect(item(form, '图标')).toContainText('SearchOutlined');

      // 路径只读按**行为**验：属性名会骗人 —— `:read-only` 那个驼峰化 bug 渲染出的
      // `read-only="true"` 看着在锁、属性检查里也「有值」，实际能打字。所以真打一遍字。
      const pathInput = field(form, '子应用路径');
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

      // 落库：路径 / 权限词 / 归属壳 / 分组来自候选，菜单名与图标是管理员改过的
      const added = (await navItemRows(page)).filter((r) => !before.has(r.id) && r.path === menuPath);
      expect(added.length, `保存后没在 ${menuPath} 上找到新增的菜单项`).toBe(1);
      expect(added[0].label).toBe(newLabel);
      expect(added[0].icon).toBe(chosenIcon);
      expect(added[0].perm).toBe('catalog:read');
      expect(added[0].scope).toBe('project');
      expect(added[0].groupTitle).toBe('数据地图');
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
      const labels = ((await res.json()) as { label: string }[]).map((r) => r.label);
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
   * <p>菜单在这条用例里是**前置条件**（用接口配，见 {@link configureNavItems}），被测对象是
   * 角色页「菜单 → 权限词」这段映射，菜单从哪儿来与它无关。所以配两条**权限词不同**的菜单，
   * 只勾其中一条，断言落库的 `perms` **恰好**是那一条挂的词 —— 两条都勾就把错位掩盖了。
   */
  test('产品角色：按菜单勾选定义角色，落成菜单挂的权限词', async ({ page }) => {
    test.setTimeout(120_000);
    const readPath = `/lineage/e2e-read-${stamp}`;
    const adminPath = `/lineage/e2e-admin-${stamp}`;
    const readLabel = `验证·全文检索-${stamp}`;
    const adminLabel = `验证·数据目录-${stamp}`;
    const roleCode = `e2e_${stamp}`;
    const roleLabel = `验证·检索员-${stamp}`;

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
      await clearNavItems(page, [readPath, adminPath]);
    }
  });
});
