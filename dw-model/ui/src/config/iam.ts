import type { Perm, Product, ProjectMember, ProjectRole, TenantLicense } from '@dw-ai/engine';

export type { Perm, Product, ProjectMember, ProjectRole, TenantLicense };

/**
 * 权限矩阵（`ROLE_PERMS`）与判权函数（`roleHas`）在共享包里 —— 工作台、仓建设、
 * 数据地图三个前端都要用它画菜单，各抄一份的话加一个产品得改三处，而漏掉的那一处
 * 不会编译报错，只在运行时判否。这里只做转发，让原 import 路径继续可用。
 */
export { ROLE_PERMS, roleHas } from '@dw-ai/engine';

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
  { username: 'admin', password: '123456', note: '平台用户' },
  { username: '张三', password: '123456', note: '仅星河 · 管理员' },
  { username: '李四', password: '123456', note: '星河建模 / 启航管理员' },
] as const;

/**
 * standard（普通模式）能进的账号 —— 只有 {@code admin} 一个。
 *
 * <p>空库启动时由 {@code BootstrapAdminRunner} 建，密码取
 * {@code dwai.bootstrap.admin-password}（默认 {@code 123456}）。
 *
 * <p>早前这里列的是张三/李四/王五，出处是 {@code WarehouseLocalSeedRunner} 里
 * 一段想给 standard 灌本地账号、却被上面的 standalone 判断挡成死代码的写法 ——
 * 那三个账号在空库里从来不存在，照着这张表试只会白撞。它们与星河租户那一套
 * 是共享的演示身份层，执行 {@code bin/seed-demo.sh} 之后才有（standalone 与
 * standard 都会灌；只有第二个租户「启航科技」是 multi 独有的那部分）。
 */
export const DEMO_ACCOUNTS_STANDARD = [
  { username: 'admin', password: '123456', note: '平台管理员 · 可建项目和用户' },
] as const;

export const ROLE_LABEL: Record<ProjectRole, string> = {
  admin: '项目管理员',
  modeler: '建模工程师',
  viewer: '只读访客',
};

