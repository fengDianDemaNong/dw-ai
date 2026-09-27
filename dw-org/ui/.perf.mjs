// 量「点开某个挂载页」的耗时：渲染时刻 + 该产品 origin 下每个资源的请求耗时。
// 用法：node .perf.mjs '<org路径>|<产品origin片段>|<标题>' '<org路径>|<产品origin片段>|<标题>' ...
import { chromium } from 'playwright';

const BASE = 'http://127.0.0.1:5180';
const targets = process.argv.slice(2).map((s) => {
  const [t, o, title] = s.split('|');
  return { t, o, title: title ?? t };
});

const field = (r, l) => r.locator('.ant-form-item').filter({ hasText: l }).locator('input, textarea').first();
const btn = (r, n) => r.getByRole('button', { name: new RegExp(`^${n.split('').join('\\s*')}$`) });

const browser = await chromium.launch({ channel: 'chrome' });
const ctx = await browser.newContext();
const page = await ctx.newPage();

let t0 = 0;
const started = new Map();
const done = [];
const onReq = (r) => started.set(r, Date.now() - t0);
const onEnd = (r, ok) => {
  const s = started.get(r);
  if (s === undefined) return;
  started.delete(r);
  done.push({ url: r.url(), type: r.resourceType(), ms: Date.now() - t0 - s, ok, at: s });
};
page.on('request', onReq);
page.on('requestfinished', (r) => onEnd(r, true));
page.on('requestfailed', (r) => onEnd(r, false));

await page.goto(`${BASE}/org/login`);
await field(page, '用户名').fill('张三');
await field(page, '密码').fill('123456');
await btn(page, '登录').click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20_000 });
await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
await page.waitForURL(/\/org\/project\//, { timeout: 20_000 });
await page.waitForTimeout(3000);

for (const { t, o, title } of targets) {
  done.length = 0;
  started.clear();
  t0 = Date.now();
  await page.goto(`${BASE}${t}`, { waitUntil: 'commit' });

  const deadline = Date.now() + 45_000;
  let rendered = -1;
  while (Date.now() < deadline) {
    const f = page.frames().find((x) => x.url().includes(o.replace(/^\d+$/, '')) || x.url().includes(`:${o}/`));
    if (f) {
      try {
        if (await f.evaluate(() => Boolean(document.querySelector('#root, #app')?.children.length))) {
          rendered = Date.now() - t0;
          break;
        }
      } catch { /* 导航中 */ }
    }
    await page.waitForTimeout(50);
  }
  await page.waitForTimeout(8000); // 让数据请求都发完

  console.log(`\n=== ${title} ===`);
  console.log(`  导航 → 骨架渲染：${rendered < 0 ? '45s 未渲染' : rendered + 'ms'}`);

  const mine = done.filter((d) => d.url.includes(`:${o}/`) || d.url.includes(`127.0.0.1:${o}`));
  const bad = mine.filter((d) => !d.ok);
  const slow = [...mine].sort((a, b) => b.ms - a.ms).slice(0, 12);
  const sum = mine.filter((x) => x.ok).reduce((a, x) => a + x.ms, 0);
  console.log(`  产品 origin:${o} 的请求 ${mine.length} 个（失败 ${bad.length}），串行耗时合计 ${sum}ms`);
  for (const s of slow) {
    console.log(`    ${String(s.ms).padStart(6)}ms  t+${String(s.at).padStart(5)}  ${(s.ok ? '' : 'FAIL ').padEnd(5)}${s.type.padEnd(9)} ${s.url.replace(/^https?:\/\/127\.0\.0\.1:\d+/, '').slice(0, 110)}`);
  }
  for (const s of bad) {
    console.log(`    FAIL ${s.type} ${s.url.replace(/^https?:\/\/127\.0\.0\.1:\d+/, '').slice(0, 110)}`);
  }
}

await browser.close();
