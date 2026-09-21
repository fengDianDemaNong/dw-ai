export type RunMode = 'standalone' | 'standard' | 'multi';
/** @deprecated 用 RunMode */
export type DeployMode = RunMode;

const STORAGE_KEY = 'dw-ai.deploy-mode';
export const DEFAULT_TENANT_ID = 't-xinghe';

function parseMode(v: string | null | undefined): RunMode | null {
  if (v === 'standalone' || v === 'standard' || v === 'multi') return v;
  return null;
}

function readEnv(): RunMode | null {
  return parseMode(import.meta.env.VITE_RUN_MODE) ?? parseMode(import.meta.env.VITE_DEPLOY_MODE);
}

export function getRunMode(): RunMode {
  return 'multi';
}

export function getDeployMode(): RunMode {
  return getRunMode();
}

export function setDeployModeHint(mode: RunMode) {
  window.sessionStorage.setItem('dw-ai.deployMode', mode);
}

export function setDeployMode(mode: RunMode) {
  window.sessionStorage.setItem(STORAGE_KEY, mode);
  window.sessionStorage.setItem('dw-ai.deployMode', mode);
  window.location.reload();
}

export function isStandalone(): boolean {
  return getRunMode() === 'standalone';
}

export function isMultiTenant(): boolean {
  return getRunMode() === 'multi';
}

export function isStandardMode(): boolean {
  return getRunMode() === 'standard';
}
