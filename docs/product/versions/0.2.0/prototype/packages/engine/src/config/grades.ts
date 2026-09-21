import type { ExportPolicy, GradeDraft, QueryPolicy } from '../types';

export const QUERY_OPTIONS: { value: QueryPolicy; label: string }[] = [
  { value: 'allow', label: '可直接查询' },
  { value: 'login', label: '登录后可查' },
  { value: 'approval', label: '审批后可查' },
  { value: 'forbid', label: '禁止查询' },
];

export const EXPORT_OPTIONS: { value: ExportPolicy; label: string }[] = [
  { value: 'allow', label: '可导出' },
  { value: 'approval', label: '审批后导出' },
  { value: 'forbid', label: '禁止导出' },
];

export function queryLabel(v?: QueryPolicy) {
  return QUERY_OPTIONS.find((x) => x.value === v)?.label ?? '未设';
}

export function exportLabel(v?: ExportPolicy) {
  return EXPORT_OPTIONS.find((x) => x.value === v)?.label ?? '未设';
}

export function gradesForIndustry(industry: string): GradeDraft[] {
  const id = industry === 'ads' ? 'ads-4' : 'std-4';
  const tpl = GRADE_TEMPLATES.find((t) => t.id === id) ?? GRADE_TEMPLATES[0];
  return tpl.grades.map((g) => ({ ...g, examples: g.examples }));
}

export interface GradeTemplate {
  id: string;
  name: string;
  summary: string;
  grades: GradeDraft[];
}

export const GRADE_TEMPLATES: GradeTemplate[] = [
  {
    id: 'std-4',
    name: '通用四级（推荐）',
    summary: '公开 / 内部 / 敏感 / 机密。适合大多数数仓，不确定时先用这一套。',
    grades: [
      {
        code: 'L1',
        name: '公开',
        level: 1,
        color: 'green',
        query: 'allow',
        export: 'allow',
        note: '已对外或可公开的统计口径，不含个人与资金明细。',
        examples: '日活、曝光量、类目 GMV 汇总',
      },
      {
        code: 'L2',
        name: '内部',
        level: 2,
        color: 'blue',
        query: 'login',
        export: 'approval',
        note: '企业内部运营数据，登录即可查，导出需审批。',
        examples: '订单明细、投放计划、代码位报表',
      },
      {
        code: 'L3',
        name: '敏感',
        level: 3,
        color: 'orange',
        query: 'approval',
        export: 'forbid',
        note: '含个人标识或设备标识，查询需审批，禁止明文导出。',
        examples: '手机号、设备号、用户 ID 映射',
      },
      {
        code: 'L4',
        name: '机密',
        level: 4,
        color: 'red',
        query: 'forbid',
        export: 'forbid',
        note: '资金账户、密钥、未发布策略。禁止直接查询与导出。',
        examples: '结算账户、DSP 合同单价、密钥',
      },
    ],
  },
  {
    id: 'std-3',
    name: '精简三级',
    summary: '公开 / 内部 / 受限。团队较小、不想维护四级时用。',
    grades: [
      {
        code: 'L1',
        name: '公开',
        level: 1,
        color: 'green',
        query: 'allow',
        export: 'allow',
        note: '可直接查询、可导出的汇总与指标。',
        examples: '经营看板指标',
      },
      {
        code: 'L2',
        name: '内部',
        level: 2,
        color: 'blue',
        query: 'login',
        export: 'approval',
        note: '内部明细，登录可查，导出审批。',
        examples: '订单、流量明细',
      },
      {
        code: 'L3',
        name: '受限',
        level: 3,
        color: 'red',
        query: 'approval',
        export: 'forbid',
        note: '隐私与机密合并为一档，审批后可查，禁止导出。',
        examples: 'PII、支付账户',
      },
    ],
  },
  {
    id: 'ads-4',
    name: '广告投放四级',
    summary: '面向 ADX / DSP / SSP：效果可公开，设备与结算更严。',
    grades: [
      {
        code: 'L1',
        name: '效果公开',
        level: 1,
        color: 'green',
        query: 'allow',
        export: 'allow',
        note: '已脱敏的投放效果汇总，可直接查、可导出。',
        examples: '曝光、点击、填充率日汇总',
      },
      {
        code: 'L2',
        name: '运营内部',
        level: 2,
        color: 'blue',
        query: 'login',
        export: 'approval',
        note: '计划、代码位、媒体维度明细，登录可查。',
        examples: '计划报表、代码位质量',
      },
      {
        code: 'L3',
        name: '标识敏感',
        level: 3,
        color: 'orange',
        query: 'approval',
        export: 'forbid',
        note: '设备号、IDFA、IP、用户标识，查询审批，禁止导出。',
        examples: 'device_id、idfa、oaid',
      },
      {
        code: 'L4',
        name: '结算机密',
        level: 4,
        color: 'red',
        query: 'forbid',
        export: 'forbid',
        note: 'DSP 底价、媒体分成、合同与账户，禁止直查。',
        examples: '底价、分成比例、结算账号',
      },
    ],
  },
];
