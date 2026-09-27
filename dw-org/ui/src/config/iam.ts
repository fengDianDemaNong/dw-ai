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

/**
 * 模块词表 —— 与后端 `ProductCodes.LICENSE_MODULES` 逐字对应（守卫测试比对）。
 *
 * <p>`desc` 是给工作台「模块管理」页的「说明」列用的（截图里的第二列）。文案取自原型，
 * 但**产品名一律用这里的 `label`**：原型把我们叫「仓建设」的产品写在描述里，那是另一套
 * 词汇，直接抄进来会让同一页出现两个名字。
 */
export const MODULE_OPTIONS: { value: ProductModule; label: string; desc: string; shipped?: boolean }[] = [
  {
    value: 'warehouse',
    label: '数仓建模（规范中心、建模中心）',
    desc: '规范中心 + 建模中心。独立进程，启动后向平台注册。',
    shipped: true,
  },
  {
    value: 'metadata',
    label: '数据地图',
    desc: '全文检索、血缘、数据目录、SQL 解析。独立进程，以 iframe 嵌进项目侧栏。',
    shipped: true,
  },
  {
    value: 'serve',
    label: '数据服务',
    desc: '指标工厂与数据市场。须先在平台注册服务。',
    shipped: false,
  },
  {
    value: 'quality',
    label: '数据质量',
    desc: '规则执行与报告。须先在平台注册服务，再给租户开通。',
    shipped: false,
  },
  {
    value: 'materialize',
    label: '沉淀优化',
    desc: '查询反向建模。默认关闭的独立产品。',
    shipped: false,
  },
  {
    value: 'dev',
    label: '开发中心',
    desc: '开发中心。独立进程，启动后向平台注册。',
    shipped: false,
  },
];

/**
 * 模块「谁能看见」的四档 —— 与后端 `NavNodeService.VISIBLE_TO` 一一对应。
 *
 * <p>判据在服务端（`Render.visible`），这里只提供选项与文案：前端不重复判一遍，
 * 否则迟早漂成「下拉里选得上、侧栏里不生效」。
 */
export const VISIBLE_TO_OPTS = [
  { value: 'tenant_admin', label: '仅租户管理员' },
  { value: 'project_admin', label: '租户管理员 + 项目管理员' },
  { value: 'role_holders', label: '管理员 + 持有该产品角色的人' },
  { value: 'all_members', label: '全部项目成员' },
] as const;

export type VisibleTo = (typeof VISIBLE_TO_OPTS)[number]['value'];

/** 四档的默认档 —— 与后端 `DEFAULT_VISIBLE_TO` 一致（warehouse 例外，见后端注释）。 */
export const DEFAULT_VISIBLE_TO: VisibleTo = 'role_holders';

export function visibleToLabel(v: string | undefined | null) {
  return VISIBLE_TO_OPTS.find((x) => x.value === v)?.label ?? '管理员 + 持有该产品角色的人';
}

export function moduleLabel(product: string) {
  return MODULE_OPTIONS.find((m) => m.value === product)?.label ?? product;
}

export function moduleDesc(product: string) {
  return MODULE_OPTIONS.find((m) => m.value === product)?.desc ?? '';
}

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

