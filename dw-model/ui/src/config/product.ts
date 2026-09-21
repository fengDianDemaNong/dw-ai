export type UiProduct = 'org' | 'warehouse' | 'suite';

export function getUiProduct(): UiProduct {
  return 'warehouse';
}

export function isOrgUi(): boolean {
  return getUiProduct() === 'org';
}

export function isWarehouseUi(): boolean {
  return getUiProduct() === 'warehouse';
}

export function orgOrigin(): string {
  return (import.meta.env.VITE_ORG_ORIGIN as string | undefined)?.replace(/\/$/, '') || 'http://127.0.0.1:5171';
}

export function warehouseOrigin(): string {
  return (import.meta.env.VITE_WAREHOUSE_ORIGIN as string | undefined)?.replace(/\/$/, '')
    || 'http://127.0.0.1:5172';
}

export function lineageOrigin(): string {
  return (import.meta.env.VITE_LINEAGE_ORIGIN as string | undefined)?.replace(/\/$/, '')
    || 'http://127.0.0.1:5175';
}

export type EmbedBoot = {
  token?: string;
  refreshToken?: string;
  tokenExp?: string;
  userId?: string;
  tenant?: string;
  project?: string;
  tenantCode?: string;
  projectCode?: string;
  tenantName?: string;
  projectName?: string;
};

function sessionBoot(): EmbedBoot {
  return {
    token: sessionStorage.getItem('dw-ai.token') ?? '',
    refreshToken: sessionStorage.getItem('dw-ai.refreshToken') ?? '',
    tokenExp: sessionStorage.getItem('dw-ai.tokenExp') ?? '',
    userId: sessionStorage.getItem('dw-ai.userId') ?? '',
    tenant: sessionStorage.getItem('dw-ai.tenantId') ?? '',
    project: sessionStorage.getItem('dw-ai.projectId') ?? '',
    tenantCode: sessionStorage.getItem('dw-ai.tenantCode') ?? '',
    projectCode: sessionStorage.getItem('dw-ai.projectCode') ?? '',
  };
}

function bootPayload(): string {
  return encodeURIComponent(JSON.stringify(sessionBoot()));
}

export function lineageEmbedUrl(path: string, extra?: Record<string, string>, boot?: EmbedBoot): string {
  const p = path.startsWith('/') ? path : `/${path}`;
  const q = new URLSearchParams({ embed: '1', ...extra });
  const session = { ...sessionBoot(), ...boot, embed: true };
  return `${lineageOrigin()}${p}?${q}#boot=${encodeURIComponent(JSON.stringify(session))}`;
}

export function openWarehouseApp(path = '/model'): void {
  const suffix = path.startsWith('/') ? path : `/${path}`;
  window.location.href = `${warehouseOrigin()}${suffix}#boot=${bootPayload()}`;
}

export function openOrgLogin(): void {
  window.location.href = `${orgOrigin()}/org/login`;
}

export function openLineageApp(path = '/lineage'): void {
  const suffix = path.startsWith('/') ? path : `/${path}`;
  window.location.href = `${lineageOrigin()}${suffix}#boot=${bootPayload()}`;
}

export function consumeBootHash(): void {
  const raw = window.location.hash.startsWith('#boot=') ? window.location.hash.slice(6) : '';
  if (!raw) return;
  try {
    const boot = JSON.parse(decodeURIComponent(raw)) as {
      token?: string;
      refreshToken?: string;
      tokenExp?: string;
      tenant?: string;
      project?: string;
      tenantCode?: string;
      projectCode?: string;
      userId?: string;
    };
    if (boot.token) sessionStorage.setItem('dw-ai.token', boot.token);
    if (boot.refreshToken) sessionStorage.setItem('dw-ai.refreshToken', boot.refreshToken);
    if (boot.tokenExp) sessionStorage.setItem('dw-ai.tokenExp', boot.tokenExp);
    if (boot.tenant) sessionStorage.setItem('dw-ai.tenantId', boot.tenant);
    if (boot.project) sessionStorage.setItem('dw-ai.projectId', boot.project);
    if (boot.tenantCode) sessionStorage.setItem('dw-ai.tenantCode', boot.tenantCode);
    if (boot.projectCode) sessionStorage.setItem('dw-ai.projectCode', boot.projectCode);
    if (boot.userId) sessionStorage.setItem('dw-ai.userId', boot.userId);
  } catch {
    /* 忽略损坏的启动参数 */
  }
  history.replaceState(null, '', window.location.pathname + window.location.search);
}
