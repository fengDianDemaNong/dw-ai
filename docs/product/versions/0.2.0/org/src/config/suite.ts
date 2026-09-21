import { app } from '../stores/app';

/** 组织平台与仓建设是两个进程。实现时各是独立安装包，这里用本机端口演示。 */
export const ORG_ORIGIN = 'http://127.0.0.1:4234';
export const WAREHOUSE_ORIGIN = 'http://127.0.0.1:4233';
export const LINEAGE_ORIGIN = 'http://127.0.0.1:5175';

export type BootSession = {
  userId?: string | null;
  tenantId?: string | null;
  projectId?: string | null;
};

export function consumeBootHash(): BootSession | null {
  const raw = window.location.hash.startsWith('#boot=') ? window.location.hash.slice(6) : '';
  if (raw) {
    history.replaceState(null, '', window.location.pathname + window.location.search);
  }
  if (!raw) return null;
  try {
    return JSON.parse(decodeURIComponent(raw)) as BootSession;
  } catch {
    return null;
  }
}

export function openOrigin(origin: string, path = '/', boot?: BootSession) {
  const payload = boot ?? {
    userId: app.currentUserId,
    tenantId: app.currentTenantId,
    projectId: app.currentProjectId,
  };
  const hash = payload.userId ? `#boot=${encodeURIComponent(JSON.stringify(payload))}` : '';
  window.location.href = `${origin}${path}${hash}`;
}
