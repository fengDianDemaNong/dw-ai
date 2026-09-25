import type { Router } from 'vue-router';
import { isEmbed, orgOrigin, storedHostOrigin } from './runtime';
import { applyAccessToken } from '../stores/tenant';

export const EMBED_NAV = 'dw-embed-navigate';
export const EMBED_READY = 'dw-embed-ready';
/** 嵌壳推送新的 access token（组织续期后）。 */
export const EMBED_TOKEN = 'dw-embed-token';
/** 向嵌壳求新 token：401 兜底（宿主可能错过续期推送，或本页加载晚于最近一次续期）。 */
export const EMBED_TOKEN_REQUEST = 'dw-embed-token-request';

function parentOrigin(): string | undefined {
  try {
    if (document.referrer) return new URL(document.referrer).origin;
  } catch {
    /* ignore */
  }
  return undefined;
}

/**
 * 往哪个 origin 发消息（{@link EMBED_READY} 与 {@link EMBED_TOKEN_REQUEST} 的目标）。
 *
 * <p>先 `document.referrer`：它描述的是<b>当前这一次加载</b>的父页面，最不容易过期。
 * 被跨站策略剥掉时退到壳在 `#boot=` 里自报的 origin（{@link storedHostOrigin}）——
 * 那个值是壳亲口说的，比猜可靠。两者都没有就返回 `undefined`，调用方据此**不发**
 * （这条握手带着令牌语义，见 {@link requestEmbedToken}）。
 *
 * <p>顺序不能反。壳自报的 origin 存在 sessionStorage 里，会跨页面加载存活
 * （`#boot=` 本身被 `consumeBootHash` 清掉了，sessionStorage 里的还在），
 * 于是存在「上一次加载的壳」这种陈旧值；referrer 描述的是这一次。referrer 优先，
 * 陈旧值就只在 referrer 也拿不到时才有机会被用上 —— 那种情况本来就没得发，
 * 所以这个顺序只会**多**救回一些场景，不会把原本发得对的发错。
 */
function hostTarget(): string | undefined {
  return parentOrigin() ?? storedHostOrigin();
}

/**
 * 谁的消息才收。
 *
 * <p>三道：<b>壳自报的 origin</b>（`#boot=` 里带过来的 `hostOrigin`，
 * 见 {@link storedHostOrigin}）、**运行期推导出的组织平台地址**（{@link orgOrigin}，
 * 它自己会依次问 boot、后端、内置默认），以及 `document.referrer` 兜底。
 *
 * <p>主通道是第一条。门户可能是组织平台，也可能是仓建设 —— 靠环境变量一对一配对
 * 既配不全、漏了又不报错，表现为续期 token 被静默丢掉（页面照常渲染，
 * 要等令牌过期才以「整片 401」暴露）。由壳自报就不必让被嵌方去猜。
 *
 * <p>`document.referrer` 只作兜底：它可能被跨站策略剥掉，也可能在 iframe 内的
 * 二次跳转后变化，不能当主通道。
 */
function allowed(origin: string): boolean {
  if (origin === storedHostOrigin() || origin === orgOrigin()) return true;
  const parent = parentOrigin();
  return Boolean(parent && parent === origin);
}

/**
 * 请宿主把当前 access token 推过来。
 *
 * <p>目标 origin 见 {@link hostTarget}（referrer 优先，`#boot=` 自报的兜底）。
 * 两个都拿不到就不发：这条握手带着令牌语义，不广播。
 * 宿主侧的 {@code ProductEmbed} 收到后回 {@code EMBED_TOKEN}。
 */
export function requestEmbedToken(): void {
  if (typeof window === 'undefined' || window.parent === window) return;
  const target = hostTarget();
  if (!target) return;
  window.parent.postMessage({ type: EMBED_TOKEN_REQUEST }, target);
}

export function listenEmbedHost(router: Router): void {
  window.addEventListener('message', (e: MessageEvent) => {
    if (!allowed(e.origin)) return;

    if (e.data?.type === EMBED_TOKEN) {
      const token = typeof e.data.token === 'string' ? e.data.token : '';
      const tokenExp = typeof e.data.tokenExp === 'string' ? e.data.tokenExp : '';
      applyAccessToken(token, tokenExp);
      return;
    }

    if (e.data?.type !== EMBED_NAV) return;
    const path = typeof e.data.path === 'string' ? e.data.path : '';
    if (!path.startsWith('/')) return;
    const query = e.data.query && typeof e.data.query === 'object' ? e.data.query : undefined;
    void router.push({ path, query });
  });

  if (!isEmbed() || window.parent === window) return;
  // 不广播：EMBED_READY 本身不带敏感数据，但定向发送是纪律 ——
  // 宿主拿不到 ready 时有自己的 onLoad 兜底，功能不依赖这条广播。
  // 目标 origin 见 hostTarget（referrer 优先，`#boot=` 自报的兜底）。
  const target = hostTarget();
  if (!target) return;
  window.parent.postMessage({ type: EMBED_READY }, target);
  // ready 之后立刻要一次 token：#boot 里的可能是 iframe 创建之后宿主又续过的旧值
  requestEmbedToken();
}
