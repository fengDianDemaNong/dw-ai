export type DeployMode = 'multi' | 'standard';

const STORAGE_KEY = 'dw-ai.deploy-mode';
export const DEFAULT_TENANT_ID = 't-xinghe';

function readEnv(): DeployMode | null {
  const env = import.meta.env.VITE_DEPLOY_MODE;
  if (env === 'multi' || env === 'standard') return env;
  return null;
}

export function getDeployMode(): DeployMode {
  if (typeof window !== 'undefined') {
    const q = new URLSearchParams(window.location.search).get('mode');
    if (q === 'multi' || q === 'standard') {
      window.sessionStorage.setItem(STORAGE_KEY, q);
      return q;
    }
    const fromApi = window.sessionStorage.getItem('dw-ai.deployMode');
    if (fromApi === 'multi' || fromApi === 'standard') return fromApi;
    const stored = window.sessionStorage.getItem(STORAGE_KEY);
    if (stored === 'multi' || stored === 'standard') return stored;
  }
  return readEnv() ?? 'multi';
}

export function setDeployModeHint(mode: DeployMode) {
  window.sessionStorage.setItem('dw-ai.deployMode', mode);
}

export function setDeployMode(mode: DeployMode) {
  window.sessionStorage.setItem(STORAGE_KEY, mode);
  window.sessionStorage.setItem('dw-ai.deployMode', mode);
  window.location.reload();
}

export function isMultiTenant(): boolean {
  return getDeployMode() === 'multi';
}
