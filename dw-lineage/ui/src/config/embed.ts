import type { Router } from 'vue-router';
import { isEmbed, orgOrigin, warehouseOrigin } from './runtime';
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

function allowed(origin: string): boolean {
  if (origin === orgOrigin() || origin === warehouseOrigin()) return true;
  const parent = parentOrigin();
  return Boolean(parent && parent === origin);
}

/**
 * 请宿主把当前 access token 推过来。
 *
 * <p>拿不到宿主 origin（referrer 被策略剥掉）就不发：这条握手带着令牌语义，
 * 不广播到 `'*'`。宿主侧的 {@code ProductEmbed} 收到后回 {@code EMBED_TOKEN}。
 */
export function requestEmbedToken(): void {
  if (typeof window === 'undefined' || window.parent === window) return;
  const target = parentOrigin();
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
  // 不广播到 '*'：EMBED_READY 本身不带敏感数据，但定向发送是纪律 ——
  // 宿主拿不到 ready 时有自己的 onLoad 兜底，功能不依赖这条广播
  const target = parentOrigin();
  if (!target) return;
  window.parent.postMessage({ type: EMBED_READY }, target);
  // ready 之后立刻要一次 token：#boot 里的可能是 iframe 创建之后宿主又续过的旧值
  requestEmbedToken();
}
