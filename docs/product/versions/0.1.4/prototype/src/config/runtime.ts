export type DeployMode = 'multi' | 'standard';

const STORAGE_KEY = 'dw-ai.proto.0.1.4.mode';
export const DEFAULT_TENANT_ID = 't-xinghe';

function readEnv(): DeployMode | null {
  const env = import.meta.env.VITE_DEPLOY_MODE;
  if (env === 'multi' || env === 'standard') return env;
  return null;
}

/** 启动模式。服务启动时由配置决定；原型可用 ?mode= 或本机存储模拟重启。 */
export function getDeployMode(): DeployMode {
  if (typeof window !== 'undefined') {
    const q = new URLSearchParams(window.location.search).get('mode');
    if (q === 'multi' || q === 'standard') {
      window.localStorage.setItem(STORAGE_KEY, q);
      return q;
    }
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (stored === 'multi' || stored === 'standard') return stored;
  }
  return readEnv() ?? 'multi';
}

export function setDeployMode(mode: DeployMode) {
  window.localStorage.setItem(STORAGE_KEY, mode);
  const url = new URL(window.location.href);
  url.searchParams.set('mode', mode);
  window.location.href = url.toString();
}

export function isMultiTenant(): boolean {
  return getDeployMode() === 'multi';
}
