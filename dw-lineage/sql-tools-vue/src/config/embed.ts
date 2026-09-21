import type { Router } from 'vue-router';
import { isEmbed, orgOrigin, warehouseOrigin } from './runtime';

export const EMBED_NAV = 'dw-embed-navigate';
export const EMBED_READY = 'dw-embed-ready';

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

export function listenEmbedHost(router: Router): void {
  window.addEventListener('message', (e: MessageEvent) => {
    if (!allowed(e.origin)) return;
    if (e.data?.type !== EMBED_NAV) return;
    const path = typeof e.data.path === 'string' ? e.data.path : '';
    if (!path.startsWith('/')) return;
    const query = e.data.query && typeof e.data.query === 'object' ? e.data.query : undefined;
    void router.push({ path, query });
  });

  if (!isEmbed() || window.parent === window) return;
  const target = parentOrigin() ?? '*';
  window.parent.postMessage({ type: EMBED_READY }, target);
}
