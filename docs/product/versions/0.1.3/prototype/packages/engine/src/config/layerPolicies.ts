import type { LayerRule, MaskingPolicy, NullPolicy } from '../types';

export interface LayerPolicy {
  fieldFormat: string;
  timeFormat: string;
  masking: MaskingPolicy;
  maskingNote: string;
  nullHandling: NullPolicy;
  nullFill: string;
}

export const MASKING_OPTIONS: { value: MaskingPolicy; label: string }[] = [
  { value: 'keep', label: '保持原文' },
  { value: 'mask', label: '掩码脱敏' },
  { value: 'hash', label: '哈希' },
  { value: 'encrypt', label: '加密' },
  { value: 'drop', label: '禁止下沉' },
];

export const NULL_OPTIONS: { value: NullPolicy; label: string }[] = [
  { value: 'keep', label: '保留空值' },
  { value: 'fill', label: '默认填充' },
  { value: 'reject', label: '空值打回' },
];

export function maskingLabel(v?: MaskingPolicy) {
  return MASKING_OPTIONS.find((x) => x.value === v)?.label ?? '未设';
}

export function nullLabel(v?: NullPolicy) {
  return NULL_OPTIONS.find((x) => x.value === v)?.label ?? '未设';
}

const GENERIC: LayerPolicy = {
  fieldFormat: '[业务过程]_[度量/维度]_[词根]，全小写蛇形',
  timeFormat: '日期 yyyy-MM-dd；时间戳 yyyy-MM-dd HH:mm:ss',
  masking: 'mask',
  maskingNote: '手机号、身份证、设备号等敏感字段按本层策略处理',
  nullHandling: 'fill',
  nullFill: '度量 0，维度「未知」',
};

const BY_LAYER: Record<string, LayerPolicy> = {
  ODS: {
    fieldFormat: '尽量保持源系统字段名，仅做合法化（小写、去特殊字符）',
    timeFormat: '保持源系统格式，入库补 dt 分区 yyyy-MM-dd',
    masking: 'keep',
    maskingNote: '原始层可明文落地；高敏感字段建议入库哈希，本层不做业务清洗',
    nullHandling: 'keep',
    nullFill: '',
  },
  DWD: {
    fieldFormat: '[业务过程]_[度量/维度]_[词根]，全小写蛇形，如 order_pay_amt',
    timeFormat: '日期 yyyy-MM-dd；时间戳 yyyy-MM-dd HH:mm:ss',
    masking: 'mask',
    maskingNote: '手机号、身份证、设备号必须脱敏后入明细层，禁止明文下沉',
    nullHandling: 'fill',
    nullFill: '度量填 0，维度填「未知」或空串',
  },
  DWS: {
    fieldFormat: '与 DWD 字段对齐，汇总列用 _cnt / _amt / _rate 词根',
    timeFormat: '统计周期字段 dt / wi / mi，格式 yyyy-MM-dd',
    masking: 'drop',
    maskingNote: '汇总层不得保留 PII；只能带已脱敏或已剔除的统计维度',
    nullHandling: 'fill',
    nullFill: '指标空值填 0',
  },
  ADS: {
    fieldFormat: '面向应用：可读英文或与指标工厂字段一致',
    timeFormat: '按消费端要求，默认 yyyy-MM-dd',
    masking: 'mask',
    maskingNote: '对外服务前按应用要求二次脱敏或脱敏展示',
    nullHandling: 'fill',
    nullFill: '展示层空值按产品约定填充',
  },
  DIM: {
    fieldFormat: 'dim_[主题域]_[维度名]，主键 *_id',
    timeFormat: '缓慢变化维用 start_dt / end_dt，yyyy-MM-dd',
    masking: 'mask',
    maskingNote: '维表中的个人信息按 DWD 同级脱敏',
    nullHandling: 'reject',
    nullFill: '',
  },
};

export function defaultLayerPolicy(layer: string): LayerPolicy {
  return { ...(BY_LAYER[layer.toUpperCase()] ?? GENERIC) };
}

export function hydrateLayerRule(r: Pick<LayerRule, 'layer'> & Partial<LayerRule>): LayerRule {
  const d = defaultLayerPolicy(r.layer);
  return {
    layer: r.layer,
    naming: r.naming ?? '',
    retention: r.retention ?? '',
    serve: r.serve ?? 'forbid',
    note: r.note ?? '',
    projectId: r.projectId,
    fieldFormat: r.fieldFormat || d.fieldFormat,
    timeFormat: r.timeFormat || d.timeFormat,
    masking: r.masking || d.masking,
    maskingNote: r.maskingNote || d.maskingNote,
    nullHandling: r.nullHandling || d.nullHandling,
    nullFill: r.nullFill ?? d.nullFill,
  };
}

export function specLayerHref(layer: string) {
  return `/w/spec/layers?layer=${encodeURIComponent(layer.toUpperCase())}`;
}
