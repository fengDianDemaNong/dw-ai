export type RunMode = 'standalone' | 'standard' | 'multi';

const STORAGE_KEY = 'sql-tools.runMode';

function parseMode(v: string | null | undefined): RunMode | null {
  if (v === 'standalone' || v === 'standard' || v === 'multi') return v;
  return null;
}

export function getRunMode(): RunMode {
  if (typeof window !== 'undefined') {
    const q = new URLSearchParams(window.location.search).get('mode');
    const fromQuery = parseMode(q);
    if (fromQuery) {
      setRunMode(fromQuery);
      return fromQuery;
    }
  }
  const env = parseMode(import.meta.env.VITE_RUN_MODE);
  if (env) return env;
  if (typeof window !== 'undefined') {
    const fromApi = parseMode(window.sessionStorage.getItem(STORAGE_KEY));
    if (fromApi) return fromApi;
  }
  return 'standalone';
}

export function setRunMode(mode: RunMode) {
  window.sessionStorage.setItem(STORAGE_KEY, mode);
}

export function isStandalone(): boolean {
  return getRunMode() === 'standalone';
}

export function isStandardMode(): boolean {
  return getRunMode() === 'standard';
}

export function isMulti(): boolean {
  return getRunMode() === 'multi';
}

export function orgOrigin(): string {
  return (import.meta.env.VITE_ORG_ORIGIN as string | undefined)?.replace(/\/$/, '')
    || 'http://127.0.0.1:5171';
}

export function warehouseOrigin(): string {
  return (import.meta.env.VITE_WAREHOUSE_ORIGIN as string | undefined)?.replace(/\/$/, '')
    || 'http://127.0.0.1:5172';
}

export async function loadRuntime(): Promise<RunMode> {
  try {
    const res = await fetch('/api/runtime');
    if (res.ok) {
      const body = (await res.json()) as { runMode?: string };
      const mode = parseMode(body.runMode);
      if (mode) {
        setRunMode(mode);
        return mode;
      }
    }
  } catch {
    /* 后端未起来时用构建时模式 */
  }
  return getRunMode();
}

const EMBED_KEY = 'sql-tools.embed';

export function isEmbed(): boolean {
  if (typeof window === 'undefined') return false;
  return (
    sessionStorage.getItem(EMBED_KEY) === '1' ||
    new URLSearchParams(window.location.search).get('embed') === '1'
  );
}

export function consumeBootHash(): void {
  const params = new URLSearchParams(window.location.search);
  if (params.get('embed') === '1') sessionStorage.setItem(EMBED_KEY, '1');
  const raw = window.location.hash.startsWith('#boot=') ? window.location.hash.slice(6) : '';
  if (!raw) return;
  try {
    const boot = JSON.parse(decodeURIComponent(raw)) as {
      token?: string;
      refreshToken?: string;
      tokenExp?: string;
      userId?: string;
      tenantCode?: string;
      projectCode?: string;
      tenant?: string;
      project?: string;
      tenantName?: string;
      projectName?: string;
      embed?: boolean;
    };
    const tenant = boot.tenantCode || boot.tenant;
    const project = boot.projectCode || boot.project;
    if (tenant) sessionStorage.setItem('sql-tools.bootTenant', tenant);
    if (project) sessionStorage.setItem('sql-tools.bootProject', project);
    if (boot.userId) sessionStorage.setItem('sql-tools.bootUser', boot.userId);
    if (boot.tenantName) sessionStorage.setItem('sql-tools.bootTenantName', boot.tenantName);
    if (boot.projectName) sessionStorage.setItem('sql-tools.bootProjectName', boot.projectName);
    if (boot.token) sessionStorage.setItem('sql-tools.bootToken', boot.token);
    if (boot.refreshToken) sessionStorage.setItem('sql-tools.bootRefresh', boot.refreshToken);
    if (boot.tokenExp) sessionStorage.setItem('sql-tools.bootTokenExp', boot.tokenExp);
    if (boot.embed) sessionStorage.setItem(EMBED_KEY, '1');
  } catch {
    /* 忽略损坏的启动参数 */
  }
  history.replaceState(null, '', window.location.pathname + window.location.search);
}
