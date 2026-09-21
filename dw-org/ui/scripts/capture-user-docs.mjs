/**
 * Capture live console screenshots into docs/user/images/.
 * Requires UI on 5173 and API on 8080. Run from ui/: node scripts/capture-user-docs.mjs
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '../..');
const outDir = join(root, 'docs/user/images');
const baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:5173';

function buttonName(label) {
  return new RegExp(`^${label.split('').join('\\s*')}$`);
}

async function shot(page, name, opts = {}) {
  const path = join(outDir, `${name}.png`);
  await page.waitForTimeout(280);
  await page.screenshot({ path, type: 'png', animations: 'disabled', ...opts });
  console.log('wrote', path);
}

async function login(page, username, password) {
  await page.goto('/org/login');
  await page.locator('.ant-form-item').filter({ hasText: '用户名' }).locator('input').fill(username);
  await page.locator('.ant-form-item').filter({ hasText: '密码' }).locator('input').fill(password);
  await page.getByRole('button', { name: buttonName('登录') }).click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });
}

async function pickXinghe(page) {
  if (!page.url().includes('/select-tenant')) return;
  const xinghe = page.locator('.tenant').filter({ hasText: '星河' }).first();
  if (await xinghe.count()) {
    await xinghe.click();
    await page.getByRole('button', { name: buttonName('进入租户') }).click();
    await page.waitForURL((url) => !url.pathname.includes('/select-tenant'), { timeout: 20_000 });
  }
}

async function openNav(page, label) {
  const side = page.locator('aside nav').getByText(label, { exact: true }).first();
  if (await side.isVisible().catch(() => false)) {
    await side.click();
    return;
  }
  await page.getByText(label, { exact: true }).first().click();
}

async function main() {
  await mkdir(outDir, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext({
    baseURL,
    locale: 'zh-CN',
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();

  // 1 login
  await page.goto('/org/login');
  await page.getByRole('heading', { name: /登录/ }).waitFor();
  await shot(page, 'login');

  // 2 李四 → 选租户
  await login(page, '李四', '123456');
  if (page.url().includes('/select-tenant')) {
    await page.getByRole('heading', { name: /选择租户/ }).waitFor();
    await shot(page, 'select-tenant');
    await pickXinghe(page);
  }

  // 3 张三 工作台
  await context.clearCookies();
  await page.evaluate(() => {
    sessionStorage.clear();
    localStorage.clear();
  });
  await login(page, '张三', '123456');
  await pickXinghe(page);
  await page.waitForURL(/\/projects/, { timeout: 20_000 });
  await page.getByRole('heading', { name: '项目管理' }).waitFor();
  await shot(page, 'workbench-projects');

  const firstEdit = page.locator('.projects-grid').getByRole('button', { name: buttonName('编辑') }).first();
  if (await firstEdit.count()) {
    await firstEdit.click();
    await page.locator('.project-edit-drawer.ant-drawer-open').waitFor();
    await page.waitForTimeout(250);
    await shot(page, 'project-edit-drawer');
    await page.locator('.project-edit-drawer').getByRole('button', { name: buttonName('取消') }).click();
    await page.waitForTimeout(200);
  }

  await openNav(page, '用户管理');
  await page.getByRole('heading', { name: '用户管理' }).waitFor();
  await shot(page, 'users');

  const lisi = page.locator('.org-users-table tbody tr').filter({ hasText: '李四' }).first();
  await lisi.getByRole('button', { name: buttonName('编辑') }).click();
  await page.locator('.user-edit-drawer.ant-drawer-open').waitFor();
  await page.waitForTimeout(250);
  await shot(page, 'user-edit-drawer');
  await page.locator('.user-edit-drawer').getByRole('button', { name: buttonName('取消') }).click();
  await page.waitForTimeout(200);

  await page.getByRole('heading', { name: '平台授权码' }).scrollIntoViewIfNeeded();
  await shot(page, 'grants');

  await page.keyboard.press('Escape').catch(() => {});
  await page.locator('.ant-modal-wrap').waitFor({ state: 'hidden', timeout: 3_000 }).catch(() => {});
  const grantAdd = page.locator('.block .row-head').getByRole('button', { name: buttonName('新增') });
  if (await grantAdd.count()) {
    await grantAdd.click({ force: true });
    await page.getByText('生成平台授权码').waitFor({ timeout: 8_000 });
    await shot(page, 'grant-create');
    await page.locator('.ant-modal').getByRole('button', { name: buttonName('取消') }).click();
    await page.waitForTimeout(200);
  }

  await openNav(page, '角色管理');
  await page.getByRole('heading', { name: '角色管理' }).waitFor();
  await shot(page, 'roles');

  await openNav(page, '设置');
  await page.getByRole('heading', { name: '设置' }).waitFor();
  await shot(page, 'settings');

  await openNav(page, '项目管理');
  await page.getByRole('heading', { name: '项目管理' }).waitFor();
  const firstEdit2 = page.locator('.projects-grid').getByRole('button', { name: buttonName('编辑') }).first();
  if (await firstEdit2.count()) {
    await firstEdit2.click();
    await page.locator('.project-edit-drawer.ant-drawer-open').waitFor();
    const enter = page.locator('.project-edit-drawer').getByRole('button', { name: buttonName('进入项目') });
    if (await enter.isVisible().catch(() => false)) {
      await enter.click();
    }
  }
  await page.waitForURL(/\/w/, { timeout: 20_000 }).catch(() => {});
  await page.getByRole('heading', { name: '概况' }).waitFor({ timeout: 15_000 });
  await shot(page, 'project-home');

  await openNav(page, '主题域');
  await page.getByRole('heading', { name: '主题域' }).waitFor();
  await shot(page, 'spec-domains');

  await openNav(page, '分层规范');
  await page.getByRole('heading', { name: '分层规范' }).waitFor();
  await shot(page, 'spec-layers');

  const copilotNav = page.locator('aside nav').getByText('AI 设计规范', { exact: true }).first();
  if (await copilotNav.isVisible().catch(() => false)) {
    await copilotNav.click();
  } else {
    await page.goto('/w/spec/copilot');
  }
  await page.getByRole('heading', { name: 'AI 设计规范' }).waitFor();
  await shot(page, 'spec-copilot');

  const validateNav = page.locator('aside nav').getByText('规范校验', { exact: true }).first();
  if (await validateNav.isVisible().catch(() => false)) {
    await validateNav.click();
  } else {
    await page.goto('/w/model/validate');
  }
  await page.getByRole('heading', { name: '规范校验' }).waitFor();
  await shot(page, 'model-validate');

  const ods = page.locator('aside nav').getByText(/^ODS$/, { exact: true }).first();
  if (await ods.isVisible().catch(() => false)) {
    await ods.click();
    await page.getByRole('heading', { name: /ODS/ }).waitFor({ timeout: 10_000 }).catch(() => {});
    await shot(page, 'model-ods');
  } else {
    await page.goto('/w/model/ODS');
    await page.waitForTimeout(600);
    await shot(page, 'model-ods');
  }

  await openNav(page, '项目成员');
  await page.getByRole('heading', { name: '项目成员' }).waitFor();
  await shot(page, 'members');

  // 4 平台
  await context.clearCookies();
  await page.evaluate(() => {
    sessionStorage.clear();
    localStorage.clear();
  });
  await login(page, 'admin', 'admin123');
  await page.waitForURL(/\/admin/, { timeout: 20_000 });
  await page.getByRole('heading', { name: /租户/ }).waitFor();
  await shot(page, 'admin-tenants');

  await page.goto('/admin/users');
  await page.getByRole('heading', { name: '平台用户' }).waitFor();
  await shot(page, 'admin-users');

  await browser.close();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
