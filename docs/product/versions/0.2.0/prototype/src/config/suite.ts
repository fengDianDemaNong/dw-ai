import { app, currentProject, currentTenant } from '../stores/app';

/** 组织平台与仓建设是两个进程。实现时各是独立安装包，这里用本机端口演示。 */
export const ORG_ORIGIN = 'http://127.0.0.1:4234';
export const WAREHOUSE_ORIGIN = 'http://127.0.0.1:4233';
/** 数据地图：本机 sql-lineage 前端，仓建设侧栏 iframe 嵌入。 */
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

/** sql-lineage 认 tenant/project 为组织侧 code，embed=1 时对方去掉自己的菜单壳。 */
export function lineageEmbedUrl(path: string, extra?: Record<string, string>): string {
  const p = path.startsWith('/') ? path : `/${path}`;
  const q = new URLSearchParams({ embed: '1', ...extra });
  const boot = {
    userId: app.currentUserId,
    tenantCode: currentTenant.value?.code ?? '',
    projectCode: currentProject.value?.code ?? '',
    tenantName: currentTenant.value?.name ?? '',
    projectName: currentProject.value?.name ?? '',
    embed: true,
  };
  return `${LINEAGE_ORIGIN}${p}?${q}#boot=${encodeURIComponent(JSON.stringify(boot))}`;
}
