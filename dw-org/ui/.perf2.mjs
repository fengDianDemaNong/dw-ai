// 对照：同一个产品页面，走 org 嵌入 vs 顶层直接打开，各要多少请求 / 多久。
// 用法：node .perf2.mjs '<org embed 路径>' <产品端口> '<标题>'
import { chromium } from 'playwright';

const ORG = 'http://127.0.0.1:5180';
const [, , target, port, title = target] = process.argv;

const field = (r, l) => r.locator('.ant-form-item').filter({ hasText: l }).locator('input, textarea').first();
const btn = (r, n) => r.getByRole('button', { name: new RegExp(`^${n.split('').join('\\s*')}$`) });

function recorder(page) {
  const started = new Map();
  const log = [];
  return {
    log,
    attach() {
      page.on('request', (r) => started.set(r, { at: Date.now() }));
      const end = (r, ok) => {
        const s = started.get(r);
        if (!s) return;
        started.delete(r);
        log.push({ url: r.url(), type: r.resourceType(), ms: Date.now() - s.at, at: s.at, ok });
      };
      page.on('requestfinished', (r) => end(r, true));
      page.on('requestfailed', (r) => end(r, false));
    },
  };
}

/** 等 #root 有骨架、再等到有真实文字且不转圈。返回两段耗时。 */
async function waitPaint(frame, t0, budget = 45_000) {
  const deadline = Date.now() + budget;
  let skel = -1;
  let content = -1;
  while (Date.now() < deadline) {
    try {
      const st = await frame.evaluate(() => {
        const root = document.querySelector('#root, #app');
        const text = (root?.innerText ?? '').trim();
        const spinning = Boolean(document.querySelector('.ant-spin-spinning'));
        return { kids: root?.children.length ?? 0, len: text.length, spinning };
      });
      if (skel < 0 && st.kids > 0) skel = Date.now() - t0;
      if (skel > 0 && content < 0 && st.len > 20 && !st.spinning) content = Date.now() - t0;
      if (content > 0) break;
    } catch { /* 导航中 */ }
    await page.waitForTimeout(50);
  }
  return { skel, content };
}

const browser = await chromium.launch({ channel: 'chrome' });
const ctx = await browser.newContext();
const page = await ctx.newPage();
const rec = recorder(page);
rec.attach();

await page.goto(`${ORG}/org/login`);
await field(page, '用户名').fill('张三');
await field(page, '密码').fill('123456');
await btn(page, '登录').click();
await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20_000 });
await btn(page.locator('.projects-grid .proj').first(), '进入项目').click();
await page.waitForURL(/\/org\/project\//, { timeout: 20_000 });
await page.waitForTimeout(2500);

// ---------- A：走 org 嵌入 ----------
rec.log.length = 0;
const tA = Date.now();
await page.goto(`${ORG}${target}`, { waitUntil: 'commit' });
let src = '';
const deadlineA = Date.now() + 30_000;
while (Date.now() < deadlineA) {
  const f = page.frames().find((x) => x.url().includes(`:${port}/`));
  if (f?.url().startsWith('http')) { src = f.url(); break; }
  await page.waitForTimeout(50);
}
const frameA = page.frames().find((x) => x.url().includes(`:${port}/`));
const paintA = frameA ? await waitPaint(frameA, tA) : { skel: -1, content: -1 };
await page.waitForTimeout(6000);
const mineA = rec.log.filter((d) => d.url.includes(`:${port}/`));

// ---------- B：顶层直接打开同一个 src（同 context，身份与缓存一致） ----------
const pageB = await ctx.newPage();
const recB = recorder(pageB);
recB.attach();
let paintB = { skel: -1, content: -1 };
if (src) {
  const tB = Date.now();
  await pageB.goto(src, { waitUntil: 'commit' });
  paintB = await waitPaint(pageB, tB);
  await pageB.waitForTimeout(6000);
}
const mineB = recB.log.filter((d) => d.url.includes(`:${port}/`) || d.url.includes(`127.0.0.1:${port}`));

const show = (mine) => {
  const bad = mine.filter((d) => !d.ok).length;
  const sum = mine.filter((d) => d.ok).reduce((a, x) => a + x.ms, 0);
  const byType = new Map();
  for (const d of mine) byType.set(d.type, (byType.get(d.type) ?? 0) + 1);
  return `${mine.length} 个（失败 ${bad}，串行合计 ${sum}ms；${[...byType].map(([k, v]) => `${k}=${v}`).join(' ')}）`;
};

console.log(`\n=== ${title} ===`);
console.log(`  A 嵌入   : 骨架 ${paintA.skel}ms，内容 ${paintA.content < 0 ? '未出现' : paintA.content + 'ms'}，产品侧请求 ${show(mineA)}`);
console.log(`  B 顶层   : 骨架 ${paintB.skel}ms，内容 ${paintB.content < 0 ? '未出现' : paintB.content + 'ms'}，产品侧请求 ${show(mineB)}`);
console.log(`  iframe src: ${src.slice(0, 150)}`);

await browser.close();
