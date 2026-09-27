import { chromium } from 'playwright';
const BASE = 'http://127.0.0.1:5180';
const field = (r, l) => r.locator('.ant-form-item').filter({ hasText: l }).locator('input, textarea').first();
const btn = (r, n) => r.getByRole('button', { name: new RegExp(`^${n.split('').join('\\s*')}$`) });
const browser = await chromium.launch({ channel: 'chrome' });
const page = await (await browser.newContext()).newPage();
await page.goto(`${BASE}/org/login`);
await field(page, '用户名').fill('张三');
await field(page, '密码').fill('123456');
await btn(page, '登录').click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20_000 });
await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
await page.waitForURL(/\/org\/project\//, { timeout: 20_000 });
await page.waitForTimeout(3500);
console.log('当前 URL:', page.url());
const items = await page.locator('aside nav a').evaluateAll((els) =>
  els.map((e) => ({ text: e.innerText.replace(/\s+/g, ' ').trim(), href: e.getAttribute('href') }))
);
for (const i of items) console.log(`  ${JSON.stringify(i.text)} -> ${i.href}`);
const dirs = await page.locator('aside nav .dir, aside nav .gtitle').evaluateAll((els) => els.map((e) => e.innerText.replace(/\s+/g, ' ').trim()));
console.log('目录/标题：', JSON.stringify(dirs));
await browser.close();
