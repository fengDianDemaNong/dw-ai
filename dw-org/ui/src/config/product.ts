import type { Router } from 'vue-router';
import { APP_HOME } from './paths';

export type UiProduct = 'org' | 'warehouse' | 'suite';

export function getUiProduct(): UiProduct {
  return 'org';
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

function bootPayload(): string {
  const token = sessionStorage.getItem('dw-ai.token') ?? '';
  const refreshToken = sessionStorage.getItem('dw-ai.refreshToken') ?? '';
  const tokenExp = sessionStorage.getItem('dw-ai.tokenExp') ?? '';
  const tenant = sessionStorage.getItem('dw-ai.tenantId') ?? '';
  const project = sessionStorage.getItem('dw-ai.projectId') ?? '';
  const tenantCode = sessionStorage.getItem('dw-ai.tenantCode') ?? '';
  const projectCode = sessionStorage.getItem('dw-ai.projectCode') ?? '';
  const userId = sessionStorage.getItem('dw-ai.userId') ?? '';
  return encodeURIComponent(JSON.stringify({ token, refreshToken, tokenExp, tenant, project, tenantCode, projectCode, userId }));
}

export function lineageEmbedUrl(path: string, extra?: Record<string, string>): string {
  const p = path.startsWith('/') ? path : `/${path}`;
  const q = new URLSearchParams({ embed: '1', ...extra });
  const boot = {
    token: sessionStorage.getItem('dw-ai.token') ?? '',
    refreshToken: sessionStorage.getItem('dw-ai.refreshToken') ?? '',
    tokenExp: sessionStorage.getItem('dw-ai.tokenExp') ?? '',
    userId: sessionStorage.getItem('dw-ai.userId') ?? '',
    tenant: sessionStorage.getItem('dw-ai.tenantId') ?? '',
    project: sessionStorage.getItem('dw-ai.projectId') ?? '',
    tenantCode: sessionStorage.getItem('dw-ai.tenantCode') ?? '',
    projectCode: sessionStorage.getItem('dw-ai.projectCode') ?? '',
    embed: true,
  };
  return `${lineageOrigin()}${p}?${q}#boot=${encodeURIComponent(JSON.stringify(boot))}`;
}

export function openWarehouseApp(path = '/model'): void {
  const suffix = path.startsWith('/') ? path : `/${path}`;
  window.location.href = `${warehouseOrigin()}${suffix}#boot=${bootPayload()}`;
}

export function openLineageApp(path = '/lineage'): void {
  const suffix = path.startsWith('/') ? path : `/${path}`;
  window.location.href = `${lineageOrigin()}${suffix}#boot=${bootPayload()}`;
}

/** 组织进程里的仓建设 / 数据地图首页走外链，不在本 UI 渲染。 */
export function followHome(path: string, router: Router): void {
  if (path === APP_HOME || path.startsWith('/model')) {
    openWarehouseApp(path.startsWith('/model') ? path : '/model');
    return;
  }
  if (path.startsWith('/lineage')) {
    openLineageApp(path);
    return;
  }
  void router.push(path);
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
