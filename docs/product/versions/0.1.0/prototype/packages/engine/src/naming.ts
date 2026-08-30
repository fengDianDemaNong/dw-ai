import type { Layer, SpecIssue } from './types';

const LAYER_PATTERNS: Record<Layer, RegExp> = {
  ODS: /^ods_[a-z][a-z0-9]+_[a-z][a-z0-9_]+_(di|df|hi|hf)$/,
  DWD: /^dwd_[a-z]{2,6}_[a-z][a-z0-9_]+_(item|detail|info|fact|snap)_(di|df|hi|hf)$/,
  DWS: /^dws_[a-z]{2,6}_[a-z][a-z0-9_]+_(di|wi|mi|df)$/,
  ADS: /^ads_[a-z][a-z0-9]+_[a-z][a-z0-9_]+$/,
};

export const LAYER_TEMPLATES: Record<Layer, string> = {
  ODS: 'ods_[源系统]_[表名]_[增量标记]',
  DWD: 'dwd_[主题域]_[业务过程]_[粒度]_[周期]',
  DWS: 'dws_[主题域]_[统计周期]_[业务过程]',
  ADS: 'ads_[应用]_[业务场景]',
};

const FIELD_PATTERN = /^[a-z][a-z0-9]*(_[a-z0-9]+)+$/;

export function detectLayer(tableName: string): string | null {
  const prefix = tableName.split('_')[0]?.toUpperCase();
  return prefix || null;
}

export function validateTableName(tableName: string, layer?: string): SpecIssue[] {
  const issues: SpecIssue[] = [];
  if (!tableName) {
    issues.push({ level: 'error', rule: 'empty', message: '表名不能为空' });
    return issues;
  }
  if (tableName !== tableName.toLowerCase()) {
    issues.push({ level: 'error', rule: 'case', message: '表名必须全小写' });
  }
  const inferred = detectLayer(tableName);
  if (!inferred && !layer) {
    issues.push({
      level: 'error',
      rule: 'layer',
      message: '表名必须以分层编码开头，例如 dwd_ / dim_',
    });
    return issues;
  }
  if (layer && inferred && inferred !== layer) {
    issues.push({
      level: 'error',
      rule: 'layer-mismatch',
      message: `声明分层为 ${layer}，但表名指向 ${inferred}`,
    });
  }
  const checkLayer = layer || inferred || '';
  const pattern = LAYER_PATTERNS[checkLayer as Layer];
  const template = LAYER_TEMPLATES[checkLayer as Layer];
  if (pattern && template) {
    if (!pattern.test(tableName)) {
      issues.push({
        level: 'error',
        rule: 'naming',
        message: `${checkLayer} 命名不符合 ${template}`,
      });
    } else {
      issues.push({
        level: 'info',
        rule: 'naming',
        message: `符合 ${checkLayer} 分层规范`,
      });
    }
  } else if (!tableName.startsWith(`${checkLayer.toLowerCase()}_`)) {
    issues.push({
      level: 'error',
      rule: 'naming',
      message: `${checkLayer} 表名应以 ${checkLayer.toLowerCase()}_ 开头`,
    });
  } else {
    issues.push({
      level: 'info',
      rule: 'naming',
      message: `符合 ${checkLayer} 分层前缀`,
    });
  }
  return issues;
}

export function validateFieldName(field: string, knownRoots: string[]): SpecIssue[] {
  const issues: SpecIssue[] = [];
  if (field === 'dt' || field === 'ts') return issues;
  if (!FIELD_PATTERN.test(field) && field !== 'id') {
    issues.push({
      level: 'warn',
      rule: 'field-naming',
      message: `${field} 建议采用 [业务过程]_[度量/维度]_[词根]`,
    });
  }
  const tokens = field.split('_');
  const last = tokens[tokens.length - 1];
  if (last && knownRoots.length && !knownRoots.includes(last) && field !== 'id') {
    issues.push({
      level: 'warn',
      rule: 'root',
      message: `${field} 末段「${last}」未在词根库中登记`,
    });
  }
  return issues;
}

export function suggestDwdName(domain: string, process: string, grain: string, period = 'di'): string {
  const g = grain.includes('项') || grain.includes('item') ? 'item' : grain.includes('快照') ? 'snap' : 'detail';
  return `dwd_${domain.toLowerCase()}_${process}_${g}_${period}`;
}

export function suggestDwsName(domain: string, process: string, period = 'di'): string {
  return `dws_${domain.toLowerCase()}_${process}_${period}`;
}

export function periodFromGrain(timeGrain: 'day' | 'week' | 'month'): string {
  return timeGrain === 'week' ? 'wi' : timeGrain === 'month' ? 'mi' : 'di';
}
