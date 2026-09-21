import type { AiCap, ProductModule, ProjectRole, TenantOrgRole } from '../types';
import { PRODUCT_MANIFESTS } from './products';

export const MODULE_OPTIONS: { value: ProductModule; label: string; shipped?: boolean }[] =
  PRODUCT_MANIFESTS.map((p) => ({
    value: p.code,
    label: p.kind === 'builtin' ? `${p.label}（内置）` : p.label,
    shipped: p.shipped,
  }));

export const ALL_MODULES: ProductModule[] = MODULE_OPTIONS.map((m) => m.value);

export const AI_CAP_OPTIONS: { value: AiCap; label: string; hint: string }[] = [
  { value: 'spec_design', label: '规范设计', hint: '对话 + 勾选同步' },
  { value: 'spec_ask', label: '规范问答', hint: '只读，不写库' },
  { value: 'model_design', label: '建模 AI', hint: '分层对话 + 写入 / 记版本' },
];

export const ALL_AI_CAPS: AiCap[] = AI_CAP_OPTIONS.map((c) => c.value);

export function aiCapLabel(cap: AiCap) {
  return AI_CAP_OPTIONS.find((x) => x.value === cap)?.label ?? cap;
}

export const TENANT_ROLE_LABEL: Record<TenantOrgRole, string> = {
  admin: '租户管理员',
  member: '普通成员',
};

/** 组织层项目角色。工种角色在各产品 manifest 里。 */
export const PROJECT_ROLE_LABEL: Record<ProjectRole, string> = {
  admin: '项目管理员',
  modeler: '成员（仓建设·建模工程师）',
  viewer: '成员（仓建设·只读）',
};

export const ORG_PROJECT_ROLE_OPTS = [
  { value: 'admin' as const, label: '项目管理员' },
  { value: 'member' as const, label: '项目成员' },
];

export const DEMO_ACCOUNTS_MULTI = [
  { username: 'admin', password: 'admin123', note: '平台用户' },
  { username: '张三', password: '123456', note: '仅星河 · 租户管理员' },
  { username: '李四', password: '123456', note: '星河：建模工程师 + 血缘分析' },
  { username: '王五', password: '123456', note: '星河：仓建设只读；看不见元数据' },
] as const;

export const DEMO_ACCOUNTS_STANDARD = [
  { username: '张三', password: '123456', note: '管理员 · 可建项目和用户' },
  { username: '李四', password: '123456', note: '建模工程师 + 血缘分析' },
  { username: '王五', password: '123456', note: '尚未加入项目' },
] as const;
