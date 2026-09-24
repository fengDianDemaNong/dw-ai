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
    || 'http://127.0.0.1:5173';
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
  /** 「我在本项目各产品下的角色」，形状是 `{ warehouse: 'admin', metadata: 'viewer' }`。 */
  roles?: unknown;
};

/**
 * 读「我在这项目下各产品的角色」。由组织工作台在 `#boot=` 里带进来后存下
 * （见 {@link consumeBootHash}），再原样转发给下游。
 *
 * <p>下游只拿它把菜单画对 —— 真正的门禁在各服务后端，它们各自调组织平台的
 * `authz/check` 兜底。所以这一份即便被改，后果也只是看到一个点不进去的入口。
 */
function bootRoles(): unknown {
  try {
    return JSON.parse(sessionStorage.getItem('dw-ai.roles') ?? '{}');
  } catch {
    return {};
  }
}

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
    roles: bootRoles(),
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

/**
 * 回组织平台的工作台。
 *
 * <p>multi 下本进程没有工作台（见 `config/pages.ts` 的 `hasWorkbench`）——项目在组织那边建、
 * 进项目也在组织那边完成，所以「返回工作台」对 multi 是一次<b>跨进程跳转</b>，不能写成
 * `router.push(SYS_HOME)`：`SYS_HOME` 是仓建设自己的工作台路径，multi 下会被路由守卫
 * 直接弹回去，点了等于没点（这正是它此前在 multi 下不可见的另一半原因）。
 *
 * <p>落点选 `workbench/projects` 而不是 `/org`：组织平台对普通成员只放行工作台的
 * 「项目管理」页，其余页会把他弹回上一级，指到那里会白跳一次。
 */
export function openOrgWorkbench(): void {
  window.location.href = `${orgOrigin()}/org/workbench/projects`;
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
      roles?: unknown;
    };
    // 壳给的「我在本项目各产品的角色」。存下来供侧栏按角色收口，并在跳去数据地图时
    // 原样转发（见 sessionBoot）。
    if (boot.roles) sessionStorage.setItem('dw-ai.roles', JSON.stringify(boot.roles));
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
