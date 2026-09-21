import type { ProjectMember, TenantLicense } from '../types';

export type { ProjectMember, TenantLicense };

export type Perm =
  | 'spec:read'
  | 'spec:write'
  | 'model:read'
  | 'model:write'
  | 'model:publish'
  | 'iam:member';

export type ProjectRole = ProjectMember['role'];

/** 与产品 SKU 对应。运行时只开放仓建设（规范中心 + 建模中心）。 */
export type ProductModule = TenantLicense['modules'][number];

export const DEMO_USERS = ['张三', '李四'] as const;

export const MODULE_OPTIONS: { value: ProductModule; label: string; shipped?: boolean }[] = [
  { value: 'warehouse', label: '仓建设（规范中心、建模中心）', shipped: true },
  { value: 'metadata', label: '数据地图', shipped: true },
  { value: 'serve', label: '数据服务', shipped: false },
  { value: 'quality', label: '数据质量', shipped: false },
  { value: 'materialize', label: '沉淀优化', shipped: false },
  { value: 'dev', label: '开发中心', shipped: false },
];

export const ALL_MODULES: ProductModule[] = MODULE_OPTIONS.map((m) => m.value);

export type AiCap = 'spec_design' | 'spec_ask' | 'model_design';

export const AI_CAP_OPTIONS: { value: AiCap; label: string; hint: string }[] = [
  { value: 'spec_design', label: '规范设计', hint: '对话 + 勾选同步' },
  { value: 'spec_ask', label: '规范问答', hint: '只读，不写库' },
  { value: 'model_design', label: '建模 AI', hint: '分层对话 + 写入 / 记版本' },
];

export const ALL_AI_CAPS: AiCap[] = AI_CAP_OPTIONS.map((c) => c.value);

export function aiCapLabel(cap: AiCap) {
  return AI_CAP_OPTIONS.find((x) => x.value === cap)?.label ?? cap;
}

export const TENANT_ROLE_LABEL: Record<'admin' | 'member', string> = {
  admin: '管理员',
  member: '普通成员',
};

export const PROJECT_ROLE_LABEL: Record<ProjectRole, string> = {
  admin: '项目管理员',
  modeler: '建模工程师',
  viewer: '只读访客',
};

export const DEMO_ACCOUNTS_MULTI = [
  { username: 'admin', password: 'admin123', note: '平台用户' },
  { username: '张三', password: '123456', note: '仅星河 · 管理员' },
  { username: '李四', password: '123456', note: '星河建模 / 启航管理员' },
] as const;

export const DEMO_ACCOUNTS_STANDARD = [
  { username: '张三', password: '123456', note: '管理员 · 可建项目和用户' },
  { username: '李四', password: '123456', note: '建模工程师（规范只读）' },
  { username: '王五', password: '123456', note: '尚未加入项目' },
] as const;

export const ROLE_LABEL: Record<ProjectRole, string> = {
  admin: '项目管理员',
  modeler: '建模工程师',
  viewer: '只读访客',
};

export const ROLE_PERMS: Record<ProjectRole, Perm[]> = {
  admin: ['spec:read', 'spec:write', 'model:read', 'model:write', 'model:publish', 'iam:member'],
  modeler: ['spec:read', 'model:read', 'model:write'],
  viewer: ['spec:read', 'model:read'],
};

export function roleHas(role: ProjectRole | undefined, perm: Perm): boolean {
  if (!role) return false;
  return ROLE_PERMS[role].includes(perm);
}
