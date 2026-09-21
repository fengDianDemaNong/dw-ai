import { expect, type Locator, type Page, test } from '@playwright/test';

const stamp = Date.now().toString(36);

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

async function openNav(page: Page, label: string) {
  const side = page.locator('aside nav').getByText(label, { exact: true }).first();
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
    await expect(table.getByText(original, { exact: true })).toBeVisible();
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

    await openNav(page, '设置');
    await expect(page.getByRole('heading', { name: '设置' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '主题' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '菜单栏位置' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '大模型' })).toBeVisible();

    await openNav(page, '项目管理');
    await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
    await expect(page).toHaveURL(/\/w/);
    await expect(page.getByRole('heading', { name: '概况' })).toBeVisible();

    await openNav(page, '项目成员');
    await expect(page.getByRole('heading', { name: '项目成员' })).toBeVisible();

    await openNav(page, '主题域');
    await expect(page.getByRole('heading', { name: '主题域' })).toBeVisible();

    await openNav(page, '分层规范');
    await expect(page.getByRole('heading', { name: '分层规范' })).toBeVisible();

    await openNav(page, '数据等级');
    await expect(page.getByRole('heading', { name: '数据等级' })).toBeVisible();

    await openNav(page, '词根库');
    await expect(page.getByRole('heading', { name: '词根库' })).toBeVisible();

    await openNav(page, '规范校验');
    await expect(page.getByRole('heading', { name: '规范校验' })).toBeVisible();
  });

  test('平台用户进 /admin', async ({ page }) => {
    await login(page, 'admin', 'admin123');
    await expect(page).toHaveURL(/\/admin/);
    await expect(page.getByRole('heading', { name: /租户/ })).toBeVisible();
    await openNav(page, '平台用户');
    await expect(page.getByRole('heading', { name: '平台用户' })).toBeVisible();
    await openNav(page, '设置');
    await expect(page.getByRole('heading', { name: '外观与布局' })).toBeVisible();
  });
});
