import * as XLSX from 'xlsx';
import type { ExportPolicy, GradeDraft, LayerRule, QueryPolicy, SpecDomainDraft, SpecRootDraft, RootKind } from './types';
import { hydrateLayerRule } from './config/layerPolicies';
import { EXPORT_OPTIONS, QUERY_OPTIONS } from './config/grades';

export const SPEC_PACK_FORMAT = 'dw-ai.spec.v1';

export interface SpecPackSource {
  tenantName: string;
  tenantCode: string;
  projectName: string;
  projectCode: string;
}

export interface SpecPack {
  format: string;
  exportedAt: string;
  source: SpecPackSource;
  domains: SpecDomainDraft[];
  layers: Omit<LayerRule, 'projectId'>[];
  roots: SpecRootDraft[];
  grades: GradeDraft[];
}

export interface SpecPackParseResult {
  pack: SpecPack;
  warnings: string[];
}

const SERVE_OUT: Record<string, string> = {
  forbid: '禁止',
  approval: '审批后',
  allow: '允许',
};
const SERVE_IN: Record<string, LayerRule['serve']> = {
  禁止: 'forbid',
  审批后: 'approval',
  允许: 'allow',
  forbid: 'forbid',
  approval: 'approval',
  allow: 'allow',
};
const MASK_OUT: Record<string, string> = {
  keep: '保持原文',
  mask: '掩码脱敏',
  hash: '哈希',
  encrypt: '加密',
  drop: '禁止下沉',
};
const MASK_IN: Record<string, LayerRule['masking']> = {
  保持原文: 'keep',
  掩码脱敏: 'mask',
  哈希: 'hash',
  加密: 'encrypt',
  禁止下沉: 'drop',
  keep: 'keep',
  mask: 'mask',
  hash: 'hash',
  encrypt: 'encrypt',
  drop: 'drop',
};
const NULL_OUT: Record<string, string> = { keep: '保留空值', fill: '默认填充', reject: '空值打回' };
const NULL_IN: Record<string, LayerRule['nullHandling']> = {
  保留空值: 'keep',
  默认填充: 'fill',
  空值打回: 'reject',
  keep: 'keep',
  fill: 'fill',
  reject: 'reject',
};

const QUERY_IN: Record<string, QueryPolicy> = Object.fromEntries([
  ...QUERY_OPTIONS.map((o) => [o.label, o.value] as const),
  ...QUERY_OPTIONS.map((o) => [o.value, o.value] as const),
]);
const EXPORT_IN: Record<string, ExportPolicy> = Object.fromEntries([
  ...EXPORT_OPTIONS.map((o) => [o.label, o.value] as const),
  ...EXPORT_OPTIONS.map((o) => [o.value, o.value] as const),
]);
const QUERY_OUT: Record<string, string> = Object.fromEntries(QUERY_OPTIONS.map((o) => [o.value, o.label]));
const EXPORT_OUT: Record<string, string> = Object.fromEntries(EXPORT_OPTIONS.map((o) => [o.value, o.label]));
const KIND_OUT: Record<string, string> = { biz: '业务', tech: '技术', time: '时间' };
const KIND_IN: Record<string, RootKind> = {
  业务: 'biz',
  技术: 'tech',
  时间: 'time',
  biz: 'biz',
  tech: 'tech',
  time: 'time',
};

function splitList(v: unknown): string[] {
  if (Array.isArray(v)) return v.map(String).map((s) => s.trim()).filter(Boolean);
  if (v == null || v === '') return [];
  return String(v)
    .split(/[,，、;；|]/)
    .map((s) => s.trim())
    .filter(Boolean);
}

function cell(row: Record<string, unknown>, ...keys: string[]): string {
  for (const k of keys) {
    const hit = Object.keys(row).find((x) => x.trim() === k || x.replace(/\s/g, '') === k);
    if (hit != null && row[hit] != null && String(row[hit]).trim() !== '') {
      return String(row[hit]).trim();
    }
  }
  return '';
}

export function buildSpecPack(
  source: SpecPackSource,
  data: {
    domains: SpecDomainDraft[];
    layers: Omit<LayerRule, 'projectId'>[];
    roots: SpecRootDraft[];
    grades?: GradeDraft[];
  }
): SpecPack {
  return {
    format: SPEC_PACK_FORMAT,
    exportedAt: new Date().toISOString(),
    source,
    domains: data.domains.map((d) => ({
      ...d,
      code: d.code.toUpperCase(),
      related: [...d.related],
      coreEntities: [...d.coreEntities],
    })),
    layers: data.layers.map((l) => {
      const h = hydrateLayerRule({ ...l, layer: String(l.layer).toUpperCase() });
      const { projectId: _pid, ...rest } = h;
      return rest;
    }),
    roots: data.roots.map((r) => ({ ...r, code: r.code.trim() })),
    grades: (data.grades ?? []).map((g) => ({ ...g, code: g.code.toUpperCase() })),
  };
}

export function parseSpecJson(text: string): SpecPackParseResult {
  const warnings: string[] = [];
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    throw new Error('JSON 无法解析，请确认是智仓规范迁移包');
  }
  const obj = raw as Partial<SpecPack> & { themeDomains?: SpecDomainDraft[] };
  if (!Array.isArray(obj.domains) && !Array.isArray(obj.themeDomains)) {
    throw new Error('不是有效的规范包：缺少 domains');
  }
  if (obj.format && obj.format !== SPEC_PACK_FORMAT) {
    warnings.push(`格式为 ${obj.format}，将按 ${SPEC_PACK_FORMAT} 兼容读取`);
  }
  const pack = buildSpecPack(
    obj.source ?? { tenantName: '', tenantCode: '', projectName: '', projectCode: '' },
    {
      domains: (obj.domains ?? obj.themeDomains ?? []).filter((d) => d?.code && d?.name),
      layers: (obj.layers ?? []).filter((l) => l?.layer),
      roots: (obj.roots ?? []).filter((r) => r?.code),
      grades: (obj.grades ?? []).filter((g) => g?.code && g?.name),
    }
  );
  pack.exportedAt = obj.exportedAt ?? pack.exportedAt;
  pack.format = obj.format ?? SPEC_PACK_FORMAT;
  return { pack, warnings };
}

function sheetToRows(wb: XLSX.WorkBook, names: string[]): Record<string, unknown>[] {
  const name = names.find((n) => wb.SheetNames.includes(n)) ??
    wb.SheetNames.find((s) => names.some((n) => s.includes(n)));
  if (!name) return [];
  return XLSX.utils.sheet_to_json<Record<string, unknown>>(wb.Sheets[name], { defval: '' });
}

export function parseSpecExcel(buf: ArrayBuffer): SpecPackParseResult {
  const wb = XLSX.read(buf, { type: 'array' });
  const warnings: string[] = [];
  const domainRows = sheetToRows(wb, ['主题域', 'domains', 'Domains']);
  const layerRows = sheetToRows(wb, ['分层', '分层规范', 'layers', 'Layers']);
  const rootRows = sheetToRows(wb, ['词根', '词根库', 'roots', 'Roots']);
  const gradeRows = sheetToRows(wb, ['数据等级', '等级', 'grades', 'Grades']);
  if (!domainRows.length && !layerRows.length && !rootRows.length && !gradeRows.length) {
    throw new Error('Excel 中未找到「主题域 / 分层 / 词根 / 数据等级」工作表');
  }

  const domains: SpecDomainDraft[] = [];
  domainRows.forEach((row, i) => {
    const code = cell(row, '编码', 'code');
    const name = cell(row, '名称', 'name');
    if (!code && !name) return;
    if (!code || !name) {
      warnings.push(`主题域第 ${i + 2} 行缺少编码或名称，已跳过`);
      return;
    }
    domains.push({
      code: code.toUpperCase(),
      name,
      definition: cell(row, '业务定义', '定义', 'definition'),
      bizOwner: cell(row, '业务Owner', '业务 Owner', 'bizOwner') || '待指定',
      techOwner: cell(row, '技术Owner', '技术 Owner', 'techOwner') || '待指定',
      dataOwner: cell(row, '数据Owner', '数据 Owner', 'dataOwner') || '待指定',
      related: splitList(cell(row, '关联域', 'related')),
      coreEntities: splitList(cell(row, '核心实体', 'coreEntities')),
    });
  });

  const layers: Omit<LayerRule, 'projectId'>[] = [];
  layerRows.forEach((row) => {
    const layer = cell(row, '层级', 'layer').toUpperCase();
    if (!layer) return;
    const serveRaw = cell(row, '对外服务', 'serve');
    const serve = SERVE_IN[serveRaw] ?? (['forbid', 'approval', 'allow'].includes(serveRaw) ? (serveRaw as LayerRule['serve']) : 'forbid');
    if (serveRaw && !SERVE_IN[serveRaw]) warnings.push(`分层 ${layer} 的对外服务「${serveRaw}」无法识别，已按禁止处理`);
    const maskingRaw = cell(row, '脱敏', '脱敏策略', 'masking');
    const nullRaw = cell(row, '空值处理', '空值策略', 'nullHandling');
    layers.push({
      layer,
      naming: cell(row, '命名规则', '表命名', 'naming'),
      retention: cell(row, '数据保留', 'retention'),
      serve,
      note: cell(row, '说明', 'note'),
      fieldFormat: cell(row, '字段格式', 'fieldFormat') || undefined,
      timeFormat: cell(row, '时间格式', 'timeFormat') || undefined,
      masking: MASK_IN[maskingRaw],
      maskingNote: cell(row, '脱敏说明', 'maskingNote') || undefined,
      nullHandling: NULL_IN[nullRaw],
      nullFill: cell(row, '空值填充', 'nullFill') || undefined,
    });
  });

  const roots: SpecRootDraft[] = [];
  rootRows.forEach((row, i) => {
    const code = cell(row, '编码', 'code');
    if (!code) return;
    const kindRaw = cell(row, '类型', 'kind') || '业务';
    const kind = KIND_IN[kindRaw];
    if (!kind) {
      warnings.push(`词根 ${code} 类型「${kindRaw}」无法识别，已跳过`);
      return;
    }
    roots.push({
      kind,
      code,
      zh: cell(row, '中文', 'zh') || code,
      en: cell(row, '英文', 'en') || code,
      domain: cell(row, '所属域', 'domain') || undefined,
      formula: cell(row, '计算公式', '公式', 'formula') || undefined,
      dataType: cell(row, '数据类型', 'dataType') || undefined,
      format: cell(row, '格式', 'format') || undefined,
    });
  });

  const grades: GradeDraft[] = [];
  gradeRows.forEach((row, i) => {
    const code = cell(row, '编码', 'code');
    const name = cell(row, '名称', 'name');
    if (!code && !name) return;
    if (!code || !name) {
      warnings.push(`数据等级第 ${i + 2} 行缺少编码或名称，已跳过`);
      return;
    }
    const queryRaw = cell(row, '查询', 'query');
    const exportRaw = cell(row, '导出', 'export');
    grades.push({
      code: code.toUpperCase(),
      name,
      level: Number(cell(row, '排序', 'level')) || i + 1,
      color: cell(row, '颜色', 'color') || 'blue',
      query: QUERY_IN[queryRaw] ?? 'login',
      export: EXPORT_IN[exportRaw] ?? 'approval',
      note: cell(row, '要求说明', '说明', 'note'),
      examples: cell(row, '适用示例', 'examples'),
    });
  });

  const pack = buildSpecPack(
    { tenantName: '', tenantCode: '', projectName: 'Excel 导入', projectCode: '' },
    { domains, layers, roots, grades }
  );
  return { pack, warnings };
}

export function specPackToExcelBuffer(pack: SpecPack, template = false): Uint8Array {
  const wb = XLSX.utils.book_new();
  const guide = [
    ['智仓 DW-AI 规范 Excel'],
    ['用途', template ? '空模板，按列填写后导入' : '从项目导出，可改完再导入到其他项目'],
    ['系统迁移', '跨环境请优先用 JSON 迁移包，字段更完整、顺序稳定'],
    ['对外服务', '禁止 / 审批后 / 允许'],
    ['脱敏', '保持原文 / 掩码脱敏 / 哈希 / 加密 / 禁止下沉'],
    ['空值处理', '保留空值 / 默认填充 / 空值打回'],
    ['查询', '可直接查询 / 登录后可查 / 审批后可查 / 禁止查询'],
    ['导出', '可导出 / 审批后导出 / 禁止导出'],
    ['分隔符', '关联域、核心实体可用逗号、顿号或分号'],
    ['来源项目', `${pack.source.tenantName} / ${pack.source.projectName}`],
    ['导出时间', pack.exportedAt],
  ];
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(guide), '说明');

  const domainHeader = ['编码', '名称', '业务定义', '业务Owner', '技术Owner', '数据Owner', '关联域', '核心实体'];
  const domainRows = (template ? [] : pack.domains).map((d) => [
    d.code,
    d.name,
    d.definition,
    d.bizOwner,
    d.techOwner,
    d.dataOwner,
    d.related.join('、'),
    d.coreEntities.join('、'),
  ]);
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([domainHeader, ...domainRows]), '主题域');

  const layerHeader = ['层级', '命名规则', '数据保留', '对外服务', '字段格式', '时间格式', '脱敏', '脱敏说明', '空值处理', '空值填充', '说明'];
  const layerRows = (template ? [] : pack.layers).map((l) => {
    const h = hydrateLayerRule(l);
    return [
      h.layer,
      h.naming,
      h.retention,
      SERVE_OUT[h.serve] ?? h.serve,
      h.fieldFormat ?? '',
      h.timeFormat ?? '',
      MASK_OUT[h.masking ?? ''] ?? h.masking ?? '',
      h.maskingNote ?? '',
      NULL_OUT[h.nullHandling ?? ''] ?? h.nullHandling ?? '',
      h.nullFill ?? '',
      h.note,
    ];
  });
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([layerHeader, ...layerRows]), '分层');

  const rootHeader = ['类型', '编码', '中文', '英文', '所属域', '计算公式', '数据类型', '格式'];
  const rootRows = (template ? [] : pack.roots).map((r) => [
    KIND_OUT[r.kind] ?? r.kind,
    r.code,
    r.zh,
    r.en,
    r.domain ?? '',
    r.formula ?? '',
    r.dataType ?? '',
    r.format ?? '',
  ]);
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([rootHeader, ...rootRows]), '词根');

  const gradeHeader = ['编码', '名称', '排序', '颜色', '查询', '导出', '要求说明', '适用示例'];
  const gradeRowsX = (template ? [] : pack.grades).map((g) => [
    g.code,
    g.name,
    g.level,
    g.color,
    QUERY_OUT[g.query] ?? g.query,
    EXPORT_OUT[g.export] ?? g.export,
    g.note,
    g.examples,
  ]);
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([gradeHeader, ...gradeRowsX]), '数据等级');

  const out = XLSX.write(wb, { bookType: 'xlsx', type: 'array' }) as Uint8Array | number[];
  return out instanceof Uint8Array ? out : new Uint8Array(out);
}

export function downloadBlob(filename: string, data: string | Uint8Array, mime: string) {
  const blob = new Blob([data as BlobPart], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export function fileStamp(code: string) {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${code || 'project'}-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`;
}
