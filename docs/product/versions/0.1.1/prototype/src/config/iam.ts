import type { ProductModule, ProjectRole, TenantOrgRole } from '../types';

export const MODULE_OPTIONS: { value: ProductModule; label: string }[] = [
  { value: 'warehouse', label: '仓建设' },
  { value: 'serve', label: '数据服务' },
  { value: 'quality', label: '数据质量' },
  { value: 'materialize', label: '沉淀优化' },
  { value: 'dev', label: '开发中心' },
];

export const ALL_MODULES: ProductModule[] = MODULE_OPTIONS.map((m) => m.value);

export const TENANT_ROLE_LABEL: Record<TenantOrgRole, string> = {
  admin: '租户管理员',
  member: '普通成员',
};

export const PROJECT_ROLE_LABEL: Record<ProjectRole, string> = {
  admin: '项目管理员',
  modeler: '建模工程师',
  viewer: '只读访客',
};

export const DEMO_ACCOUNTS_MULTI = [
  { username: 'admin', password: 'admin123', note: '平台用户' },
  { username: '张三', password: '123456', note: '仅星河 · 租户管理员' },
  { username: '李四', password: '123456', note: '星河建模 / 启航项目管理员' },
] as const;

export const DEMO_ACCOUNTS_STANDARD = [
  { username: '张三', password: '123456', note: '管理员 · 可建项目和用户' },
  { username: '李四', password: '123456', note: '建模工程师（规范只读）' },
  { username: '王五', password: '123456', note: '尚未加入项目' },
] as const;
