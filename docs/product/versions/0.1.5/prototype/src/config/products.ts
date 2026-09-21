import type { ProductModule } from '../types';

export type ProductKind = 'builtin' | 'external';

export interface ProductRoleDef {
  code: string;
  label: string;
  hint: string;
  admin?: boolean;
}

export interface ProductManifest {
  code: ProductModule;
  label: string;
  kind: ProductKind;
  shipped: boolean;
  desc: string;
  adminRole: string;
  roles: ProductRoleDef[];
}

export const PRODUCT_MANIFESTS: ProductManifest[] = [
  {
    code: 'warehouse',
    label: '仓建设',
    kind: 'builtin',
    shipped: true,
    desc: '规范中心 + 建模中心。独立进程，启动后向平台注册。',
    adminRole: 'spec_admin',
    roles: [
      { code: 'spec_admin', label: '规范管理员', hint: '改规范、建模与发布', admin: true },
      { code: 'modeler', label: '建模工程师', hint: '规范只读，建模可写；发布看项目是否授予' },
      { code: 'viewer', label: '只读', hint: '规范与模型只读' },
    ],
  },
  {
    code: 'metadata',
    label: '元数据',
    kind: 'external',
    shipped: true,
    desc: '物理目录、SQL 解析、作业血缘。独立进程，启动后向平台注册，不必与建模同机。',
    adminRole: 'catalog_admin',
    roles: [
      { code: 'catalog_admin', label: '目录管理员', hint: '目录、数据目录、临时表规则', admin: true },
      { code: 'analyst', label: '血缘分析', hint: '解析 SQL、看图；不能改目录规则' },
      { code: 'viewer', label: '只读', hint: '只看目录与血缘图' },
    ],
  },
  {
    code: 'quality',
    label: '数据质量',
    kind: 'external',
    shipped: false,
    desc: '规则执行与报告。须先在平台注册服务，再给租户开通。',
    adminRole: 'rule_admin',
    roles: [
      { code: 'rule_admin', label: '规则管理员', hint: '发布规则', admin: true },
      { code: 'inspector', label: '巡检员', hint: '跑检、看报告' },
      { code: 'viewer', label: '只读', hint: '只看报告' },
    ],
  },
  {
    code: 'serve',
    label: '数据服务',
    kind: 'external',
    shipped: false,
    desc: '指标工厂与数据市场。须先在平台注册服务。',
    adminRole: 'metric_admin',
    roles: [
      { code: 'metric_admin', label: '指标管理员', hint: '发布指标与服务', admin: true },
      { code: 'developer', label: '服务开发', hint: '注册服务' },
      { code: 'viewer', label: '只读', hint: '只看市场' },
    ],
  },
  {
    code: 'materialize',
    label: '沉淀优化',
    kind: 'external',
    shipped: false,
    desc: '查询反向建模。默认关闭的独立产品。',
    adminRole: 'admin',
    roles: [
      { code: 'admin', label: '管理员', hint: '审核推荐', admin: true },
      { code: 'viewer', label: '只读', hint: '只看推荐' },
    ],
  },
  {
    code: 'dev',
    label: '开发中心',
    kind: 'external',
    shipped: false,
    desc: '开发中心。独立进程，启动后向平台注册。',
    adminRole: 'admin',
    roles: [
      { code: 'admin', label: '管理员', hint: '管作业', admin: true },
      { code: 'viewer', label: '只读', hint: '只看作业' },
    ],
  },
];

export function productOf(code: ProductModule): ProductManifest {
  return PRODUCT_MANIFESTS.find((p) => p.code === code) ?? PRODUCT_MANIFESTS[0];
}

export function productRoleLabel(code: ProductModule, role: string | undefined): string {
  if (!role) return '未派';
  return productOf(code).roles.find((r) => r.code === role)?.label ?? role;
}

export function productRoleOptions(code: ProductModule) {
  return productOf(code).roles.map((r) => ({ value: r.code, label: r.label }));
}

export const VISIBLE_TO_OPTS = [
  { value: 'tenant_admin', label: '仅租户管理员' },
  { value: 'project_admin', label: '租户管理员 + 项目管理员' },
  { value: 'role_holders', label: '管理员 + 持有该产品角色的人' },
  { value: 'all_members', label: '全部项目成员' },
] as const;

export function visibleToLabel(v: string | undefined) {
  return VISIBLE_TO_OPTS.find((x) => x.value === v)?.label ?? '管理员 + 持有该产品角色的人';
}

export const SYNC_TARGET_LABEL: Record<string, string> = {
  warehouse: '仓建设',
  metadata: '元数据',
  quality: '数据质量',
  serve: '数据服务',
  scheduler: '调度（DS）',
  dev: '开发中心',
  materialize: '沉淀优化',
};

export function defaultModulePolicy(code: ProductModule) {
  return {
    product: code,
    enabled: true,
    visibleTo: (code === 'warehouse' ? 'all_members' : 'role_holders') as const,
  };
}
