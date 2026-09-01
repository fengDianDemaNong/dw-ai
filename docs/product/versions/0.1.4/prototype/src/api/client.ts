/** 生产控制台只调 API。未配置 VITE_API_BASE 时走本地原型。 */
export const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined)?.replace(/\/$/, '') ?? '';
export const RULES_BASE = (import.meta.env.VITE_RULES_BASE as string | undefined)?.replace(/\/$/, '') ?? '';

export function useRemoteApi() {
  return Boolean(API_BASE);
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const token = sessionStorage.getItem('dw-ai.token');
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `${res.status} ${path}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const api = {
  login: (username: string, password: string) =>
    req<{ token: string; displayName: string; tenantId: string }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  tenants: () => req<unknown[]>('/api/tenants'),
  projects: () => req<unknown[]>('/api/projects'),
  spec: {
    domains: (projectId: string) => req<unknown[]>(`/api/projects/${projectId}/domains`),
    layers: (projectId: string) => req<unknown[]>(`/api/projects/${projectId}/layers`),
    grades: (projectId: string) => req<unknown[]>(`/api/projects/${projectId}/grades`),
    roots: (projectId: string) => req<unknown[]>(`/api/projects/${projectId}/roots`),
  },
  previewQuery: (body: unknown) =>
    req<{ sql: string; decision: string; rows: Record<string, unknown>[] }>('/api/query/preview', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  publishJob: (body: unknown) =>
    req<{ processCode?: string; skipped?: boolean }>('/api/jobs/publish', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
};

export async function rulesReq<T>(path: string, body: unknown): Promise<T> {
  const base = RULES_BASE || API_BASE;
  const res = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json() as Promise<T>;
}
