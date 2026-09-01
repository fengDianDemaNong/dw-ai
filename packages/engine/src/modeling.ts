import { columnsFromDraft, renderCreateTable } from './ddl';
import { renderEtlSql } from './fieldLogic';
import { hydrateLayerRule, maskingLabel, nullLabel } from './config/layerPolicies';
import type {
  Column,
  DataGrade,
  Domain,
  FieldLogic,
  FieldTag,
  LayerRule,
  MaskingPolicy,
  ModelingDraft,
  NullPolicy,
  QualityRule,
  SpecIssue,
  TableJoin,
  TableSourceRef,
  WarehouseTable,
  WordRoot,
} from './types';
import { suggestDwdName, suggestDwsName, validateFieldName, validateTableName } from './naming';

function tokenize(name: string): string[] {
  return name.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
}

function matchRoots(name: string, roots: WordRoot[]): WordRoot[] {
  const tokens = tokenize(name);
  const hit: WordRoot[] = [];
  for (const root of roots) {
    if (tokens.includes(root.code) || tokens.includes(root.en) || name.toLowerCase().includes(root.code)) {
      hit.push(root);
    }
  }
  return hit;
}

function isMeasureType(type: string): boolean {
  return /int|decimal|double|float|number|bigint/i.test(type);
}

function pickTechRoot(col: Column, roots: WordRoot[]): WordRoot | undefined {
  const t = col.type.toLowerCase();
  const n = col.name.toLowerCase();
  if (n.endsWith('_id') || n === 'id') return roots.find((r) => r.code === 'id');
  if (n.includes('amt') || n.includes('amount') || n.includes('gmv') || /decimal/.test(t)) {
    return roots.find((r) => r.code === 'amt' || r.code === 'gmv');
  }
  if (n.includes('cnt') || n.includes('count') || n.includes('qty') || n.includes('num')) {
    return roots.find((r) => r.code === 'cnt');
  }
  if (n.includes('time') || n.includes('_at') || n.includes('ts') || /datetime|timestamp/.test(t)) {
    return roots.find((r) => r.code === 'ts');
  }
  if (n === 'dt' || n.includes('date')) return roots.find((r) => r.code === 'dt');
  return undefined;
}

function suggestFieldName(col: Column, process: string, roots: WordRoot[]): string {
  const n = col.name.toLowerCase();
  if (n === 'dt' || n === 'id') return n;
  const tech = pickTechRoot(col, roots);
  const biz = matchRoots(n, roots.filter((r) => r.kind === 'biz'));
  const bizCode = biz[0]?.code;
  if (n.endsWith('_id')) {
    const entity = n.replace(/_id$/, '');
    return `${entity}_id`;
  }
  if (tech?.code === 'ts') {
    const stem = bizCode || n.replace(/(_time|_at|time)$/g, '') || process;
    return `${stem}_ts`;
  }
  if (tech?.code === 'amt' || tech?.code === 'gmv') {
    return `${process}_${bizCode || 'pay'}_${tech.code === 'gmv' ? 'gmv' : 'amt'}`;
  }
  if (tech?.code === 'cnt') {
    return `${process}_${bizCode || 'item'}_cnt`;
  }
  if (bizCode && !n.includes(bizCode)) return `${process}_${bizCode}`;
  if (n.includes(process)) return n;
  return n.startsWith(process) ? n : `${process}_${n}`.replace(/__/g, '_');
}

export function recognizeFields(columns: Column[], roots: WordRoot[], process: string): FieldTag[] {
  return columns.map((col) => {
    const hits = matchRoots(col.name + ' ' + col.comment, roots);
    const suggested = suggestFieldName(col, process, roots);
    let confidence = 0.55;
    if (hits.length) confidence += 0.12 * Math.min(hits.length, 3);
    if (col.comment) confidence += 0.08;
    if (suggested !== col.name) confidence += 0.05;
    confidence = Math.min(0.98, confidence);
    const meaning =
      hits.map((h) => h.zh).join(' / ') || col.comment || (pickTechRoot(col, roots)?.zh ?? '未识别');
    return {
      field: col.name,
      type: col.type,
      comment: col.comment,
      suggestedName: suggested,
      roots: hits.map((h) => h.code),
      meaning,
      confidence: Number(confidence.toFixed(2)),
      sensitive: Boolean(col.sensitive) || /phone|mobile|idcard|email|name/i.test(col.name),
    };
  });
}

export function inferProcess(table: WarehouseTable): string {
  const n = table.name.toLowerCase();
  if (n.includes('order')) return 'order';
  if (n.includes('pay')) return 'pay';
  if (n.includes('refund')) return 'refund';
  if (n.includes('user') || n.includes('usr')) return 'user';
  if (n.includes('item') || n.includes('sku') || n.includes('goods')) return 'item';
  const parts = n.split('_').filter((p) => !['ods', 'dwd', 'dws', 'ads', 'mysql', 'di', 'df'].includes(p));
  return parts[0] || 'biz';
}

export function assignDomain(
  table: WarehouseTable,
  tags: FieldTag[],
  domains: Domain[]
): { code: string; confidence: number } {
  const blob = `${table.name} ${table.comment} ${tags.map((t) => t.meaning + t.field).join(' ')}`.toLowerCase();
  let best = { code: domains[0]?.code ?? 'TRD', confidence: 0.4 };
  for (const d of domains) {
    let score = 0;
    if (blob.includes(d.code.toLowerCase()) || blob.includes(d.name.replace(/域$/, ''))) score += 0.45;
    for (const e of d.coreEntities) {
      if (blob.includes(e.toLowerCase())) score += 0.18;
    }
    if (tags.some((t) => t.roots.length && d.coreEntities.some((e) => t.meaning.includes(e)))) score += 0.15;
    if (score > best.confidence) best = { code: d.code, confidence: Math.min(0.97, score) };
  }
  return { code: best.code, confidence: Number(best.confidence.toFixed(2)) };
}

function inferGrain(table: WarehouseTable, tags: FieldTag[]): string {
  const names = tags.map((t) => t.field);
  if (names.some((n) => n.includes('item') || n.includes('sku')) && names.some((n) => n.includes('order'))) {
    return '订单项';
  }
  if (names.some((n) => n === 'order_id' || n.includes('order'))) return '订单';
  if (names.some((n) => n === 'user_id') && table.name.includes('user')) return '用户';
  return '明细';
}

function inferPrimaryKeys(tags: FieldTag[]): string[] {
  const names = tags.map((t) => t.suggestedName);
  if (names.includes('id')) return ['id'];
  const ids = names.filter((n) => n.endsWith('_id') && n !== 'user_id');
  if (ids.includes('order_id') && ids.includes('item_id')) return ['order_id', 'item_id'];
  if (ids.includes('order_id') && ids.includes('sku_id')) return ['order_id', 'sku_id'];
  if (ids.length) return [ids[0]];
  return names.slice(0, 1);
}

function sqlType(type: string): string {
  const t = type.toLowerCase();
  if (t.includes('decimal')) return 'DECIMAL(18,2)';
  if (t.includes('bigint') || t.includes('int')) return /tiny|small/.test(t) ? 'INT' : 'BIGINT';
  if (t.includes('timestamp') || t.includes('datetime')) return 'STRING';
  return 'STRING';
}

function isTimeField(type: string, dst: string): boolean {
  return /time|timestamp|datetime/i.test(type) || dst.endsWith('_ts') || dst === 'dt' || dst.endsWith('_dt');
}

function timeFmt(dst: string, timeFormat?: string): string {
  if (dst === 'dt' || dst.endsWith('_dt')) return 'yyyy-MM-dd';
  if (timeFormat?.includes('yyyy-MM-dd HH:mm:ss')) return 'yyyy-MM-dd HH:mm:ss';
  if (timeFormat?.includes('yyyy-MM-dd')) return 'yyyy-MM-dd';
  return 'yyyy-MM-dd HH:mm:ss';
}

function maskSql(src: string, masking: MaskingPolicy): string {
  if (masking === 'keep') return src;
  if (masking === 'mask') return `CONCAT(LEFT(CAST(${src} AS STRING), 3), '****')`;
  if (masking === 'encrypt') return `HEX(AES_ENCRYPT(CAST(${src} AS STRING), '\${dw_mask_key}'))`;
  return `MD5(CAST(${src} AS STRING))`;
}

function fillLiteral(type: string, name: string): string {
  if (isMeasureType(type) || /amt|cnt|qty|gmv|num/.test(name)) return '0';
  return "'未知'";
}

function applyNull(expr: string, type: string, name: string, policy: NullPolicy | undefined): string {
  if (policy !== 'fill' || isTimeField(type, name)) return expr;
  return `COALESCE(${expr}, ${fillLiteral(type, name)})`;
}

function isPiiColumn(col: Column | undefined, grades: DataGrade[]): boolean {
  if (!col) return false;
  if (col.sensitive) return true;
  if (/phone|mobile|idcard|email|user_name|oaid|imei|idfa/i.test(col.name)) return true;
  const g = grades.find((x) => x.code === col.grade);
  return Boolean(g && g.level >= 3);
}

export function buildDwdDraft(opts: {
  projectId: string;
  source: WarehouseTable;
  domains: Domain[];
  roots: WordRoot[];
  layerRule?: LayerRule;
}): ModelingDraft {
  const { projectId, source, domains, roots } = opts;
  const policy = hydrateLayerRule(opts.layerRule ?? { layer: 'DWD', naming: '', retention: '', serve: 'forbid', note: '' });
  const process = inferProcess(source);
  const fieldTags = recognizeFields(source.columns, roots, process);
  const masking = policy.masking ?? 'hash';
  for (const t of fieldTags) {
    if (t.sensitive && masking === 'drop') {
      t.dropped = true;
      t.transform = 'drop';
    } else if (t.sensitive) {
      t.transform = masking;
    } else if (isTimeField(t.type, t.suggestedName)) {
      t.transform = 'time';
    } else if (policy.nullHandling === 'fill') {
      t.transform = 'fill';
    }
  }
  const kept = fieldTags.filter((t) => !t.dropped);
  const domain = assignDomain(source, fieldTags, domains);
  const grain = inferGrain(source, fieldTags);
  const primaryKeys = inferPrimaryKeys(kept);
  const tableName = suggestDwdName(domain.code, process, grain);
  const ddl = renderCreateTable(
    {
      name: tableName,
      comment: `${domain.code}域-${grain}明细`,
      columns: kept.map((t) => ({
        name: t.suggestedName,
        type: sqlType(t.type),
        comment: t.comment || t.meaning,
        nullable: !primaryKeys.includes(t.suggestedName),
      })),
      partition: 'dt',
      primaryKeys,
    },
    'hive'
  );

  const mappings = kept
    .map((t) => {
      const src = t.field;
      const dst = t.suggestedName;
      let expr = src;
      if (isTimeField(t.type, dst)) {
        expr = `DATE_FORMAT(${src}, '${timeFmt(dst, policy.timeFormat)}')`;
      } else if (t.sensitive) {
        expr = maskSql(src, masking);
      }
      expr = applyNull(expr, t.type, dst, policy.nullHandling);
      return `    ${expr} AS ${dst}`;
    })
    .join(',\n');

  const rejectCols =
    policy.nullHandling === 'reject'
      ? kept.filter((t) => primaryKeys.includes(t.suggestedName) || t.field === 'dt').map((t) => t.field)
      : [];
  const whereExtra = rejectCols.length ? rejectCols.map((c) => `\n  AND ${c} IS NOT NULL`).join('') : '';
  const etlSql = `INSERT OVERWRITE TABLE ${tableName} PARTITION (dt = '\${bizdate}')\nSELECT\n${mappings}\nFROM ${source.name}\nWHERE dt = '\${bizdate}'${whereExtra};`;

  const qualityRules: QualityRule[] = [
    {
      id: `qr-${tableName}-pk`,
      table: tableName,
      type: 'pk_unique',
      field: primaryKeys.join(','),
      logic: `COUNT(*) = COUNT(DISTINCT ${primaryKeys.join(', ')})`,
      threshold: '重复率 = 0%',
      status: 'idle',
    },
    {
      id: `qr-${tableName}-null`,
      table: tableName,
      type: 'null_rate',
      field: primaryKeys[0],
      logic: `${primaryKeys[0]} IS NOT NULL`,
      threshold: policy.nullHandling === 'reject' ? '空值率 = 0%' : '空值率 ≤ 5%',
      status: 'idle',
    },
  ];
  const statusCol = fieldTags.find((t) => t.field.includes('status') || t.suggestedName.includes('status'));
  const srcStatus = source.columns.find((c) => c.name === statusCol?.field);
  if (statusCol && srcStatus?.enumValues?.length) {
    qualityRules.push({
      id: `qr-${tableName}-enum`,
      table: tableName,
      type: 'enum',
      field: statusCol.suggestedName,
      logic: `${statusCol.suggestedName} IN (${srcStatus.enumValues.map((v) => `'${v}'`).join(', ')})`,
      threshold: '未登记值 = 0',
      status: 'idle',
    });
  }
  qualityRules.push({
    id: `qr-${tableName}-vol`,
    table: tableName,
    type: 'volatility',
    logic: '今日行数 vs 昨日/上周同比',
    threshold: '波动 ≤ 30%',
    status: 'idle',
  });

  const knownRoots = roots.map((r) => r.code);
  const dropped = fieldTags.filter((t) => t.dropped);
  const specIssues: SpecIssue[] = [
    ...validateTableName(tableName, 'DWD'),
    ...kept.flatMap((t) => validateFieldName(t.suggestedName, knownRoots)),
  ];
  if (domain.confidence < 0.8) {
    specIssues.unshift({
      level: 'warn',
      rule: 'domain',
      message: `主题域 ${domain.code} 置信度 ${domain.confidence}，低于 0.8，需人工确认`,
    });
  }
  const sensitiveKept = kept.filter((t) => t.sensitive);
  if (dropped.length) {
    specIssues.push({
      level: 'warn',
      rule: 'masking',
      message: `本层脱敏策略为「${maskingLabel(masking)}」，已禁止下沉：${dropped.map((t) => t.field).join('、')}`,
    });
  } else if (sensitiveKept.length && masking === 'keep') {
    specIssues.push({
      level: 'warn',
      rule: 'masking',
      message: `本层策略为保持原文，敏感字段 ${sensitiveKept.map((t) => t.field).join('、')} 未脱敏，请确认`,
    });
  } else if (sensitiveKept.length) {
    specIssues.push({
      level: 'info',
      rule: 'masking',
      message: `敏感字段已按分层规范「${maskingLabel(masking)}」处理：${sensitiveKept.map((t) => t.field).join('、')}`,
    });
  }
  specIssues.push({
    level: 'info',
    rule: 'null',
    message: `空值处理：${nullLabel(policy.nullHandling)}${policy.nullFill ? `（${policy.nullFill}）` : ''}`,
  });

  return {
    id: `draft-${Date.now()}`,
    projectId,
    sourceTableId: source.id,
    sources: [{ tableId: source.id, alias: 's' }],
    targetLayer: 'DWD',
    domainCode: domain.code,
    domainConfidence: domain.confidence,
    grain,
    primaryKeys,
    fieldTags,
    ddl,
    etlSql,
    qualityRules,
    specIssues,
    status: 'pending_review',
    createdAt: new Date().toISOString(),
  };
}

export function parseDdlColumns(ddl: string): Column[] {
  const cols: Column[] = [];
  const re =
    /^\s+`?([a-zA-Z_][\w]*)`?\s+([A-Z][\w()0-9,]*)\s+(?:NOT NULL\s+)?COMMENT\s+'([^']*)'/gm;
  let m: RegExpExecArray | null;
  while ((m = re.exec(ddl))) {
    cols.push({ name: m[1], type: m[2], comment: m[3] });
  }
  return cols;
}

function logicFromDwdTag(tag: FieldTag | undefined): FieldLogic | undefined {
  if (!tag || tag.dropped || tag.field === '*') return undefined;
  const op =
    tag.transform === 'hash' ? 'HASH' : tag.transform === 'mask' ? 'MASK' : tag.transform === 'encrypt' ? 'ENCRYPT' : undefined;
  return {
    kind: tag.transform && tag.transform !== 'keep' ? 'transform' : 'passthrough',
    desc: tag.meaning || tag.comment,
    sources: [{ alias: 's', column: tag.field }],
    op,
  };
}

export function tableFromDwdDraft(draft: ModelingDraft, source: WarehouseTable): WarehouseTable {
  const columns = columnsFromDraft(draft).map((col) => {
    const tag = draft.fieldTags.find((t) => t.suggestedName === col.name);
    const src = source.columns.find((c) => c.name === tag?.field);
    return {
      ...col,
      sensitive: Boolean(tag?.sensitive && !tag.dropped),
      grade: src?.grade,
      logic: tag?.logic ?? logicFromDwdTag(tag),
    };
  });
  const remainingSensitive = columns.some((c) => c.sensitive || c.grade === 'L3' || c.grade === 'L4');
  const sources: TableSourceRef[] = draft.sources ?? [{ tableId: source.id, alias: 's' }];
  return {
    id: `tbl-${draft.id}`,
    projectId: draft.projectId,
    layer: 'DWD',
    name: draft.ddl.match(/CREATE TABLE\s+`?(\w+)`?/)?.[1] ?? suggestDwdName(draft.domainCode, inferProcess(source), draft.grain),
    comment: `${draft.domainCode}域-${draft.grain}明细`,
    domain: draft.domainCode,
    grain: draft.grain,
    period: 'di',
    columns,
    partition: 'dt',
    status: 'published',
    createdFrom: source.id,
    sources,
    joins: draft.joins,
    grade: remainingSensitive ? source.grade || 'L3' : source.grade === 'L4' ? 'L3' : source.grade || 'L2',
  };
}

export type DwsMeasureInput = {
  alias: string;
  column: string;
  op: string;
  name: string;
  comment: string;
  filter?: string;
  type?: string;
};

const ALIASES = ['o', 'r', 'p', 'q', 'u', 's'];

function defaultSourceRefs(tables: WarehouseTable[]): TableSourceRef[] {
  return tables.map((t, i) => ({ tableId: t.id, alias: ALIASES[i] || `t${i}` }));
}

function inferJoins(tables: WarehouseTable[], refs: TableSourceRef[]): TableJoin[] {
  const joins: TableJoin[] = [];
  for (let i = 1; i < tables.length; i++) {
    const left = tables[0];
    const right = tables[i];
    const keys = ['dt', 'user_type', 'item_category', 'shop_id', 'user_id', 'order_id'].filter(
      (k) => left.columns.some((c) => c.name === k) && right.columns.some((c) => c.name === k)
    );
    const key = keys[0] ?? 'dt';
    joins.push({
      leftAlias: refs[0].alias,
      leftColumn: key,
      rightAlias: refs[i].alias,
      rightColumn: key,
      type: 'left',
    });
  }
  return joins;
}

function autoMeasures(tables: WarehouseTable[], refs: TableSourceRef[]): DwsMeasureInput[] {
  const out: DwsMeasureInput[] = [];
  for (let i = 0; i < tables.length; i++) {
    const t = tables[i];
    const alias = refs[i].alias;
    const orderId = t.columns.find((c) => c.name === 'order_id');
    if (orderId && !out.some((m) => m.name === 'order_cnt')) {
      out.push({
        alias,
        column: 'order_id',
        op: 'COUNT_DISTINCT',
        name: 'order_cnt',
        comment: '订单数',
        type: 'BIGINT',
      });
    }
    const amt = t.columns.find((c) => /refund_amt/.test(c.name));
    if (amt) {
      out.push({
        alias,
        column: amt.name,
        op: 'SUM',
        name: 'refund_amt',
        comment: amt.comment || '退款金额',
        type: amt.type,
      });
      continue;
    }
    const gmv = t.columns.find((c) => /pay_amt|gmv|amount/.test(c.name));
    if (gmv && !out.some((m) => m.name === 'gmv')) {
      out.push({
        alias,
        column: gmv.name,
        op: 'SUM',
        name: 'gmv',
        comment: '成交金额',
        filter: t.columns.some((c) => c.name === 'order_status') ? `${alias}.order_status = 'paid'` : undefined,
        type: gmv.type,
      });
    }
    const uid = t.columns.find((c) => c.name === 'user_id');
    if (uid && !out.some((m) => m.name === 'pay_user_cnt')) {
      out.push({
        alias,
        column: 'user_id',
        op: 'COUNT_DISTINCT',
        name: 'pay_user_cnt',
        comment: '支付用户数',
        type: 'BIGINT',
      });
    }
  }
  return out;
}

export function buildDwsDraft(opts: {
  projectId: string;
  sources: WarehouseTable[];
  domains: Domain[];
  preferredDims?: string[];
  measures?: DwsMeasureInput[];
  layerRule?: LayerRule;
  grades?: DataGrade[];
  roots?: WordRoot[];
}): ModelingDraft {
  const tables = opts.sources.filter(Boolean);
  const source = tables[0];
  const refs = defaultSourceRefs(tables);
  const joins = inferJoins(tables, refs);
  const policy = hydrateLayerRule(opts.layerRule ?? { layer: 'DWS', naming: '', retention: '', serve: 'approval', note: '' });
  const grades = opts.grades ?? [];
  const domain = source.domain || opts.domains[0]?.code || 'TRD';
  const process = inferProcess(source);
  const dimPool = tables.flatMap((t) =>
    t.columns.filter((c) => /id$|type|category|region|status|dt/.test(c.name) && !/amt|cnt|qty|amount/.test(c.name))
  );
  const dimCandidates = [...new Set(dimPool.map((c) => c.name))];
  const requested = (opts.preferredDims?.length ? opts.preferredDims : dimCandidates).slice(0, 6);
  const piiRequested = requested.filter((d) =>
    tables.some((t) => isPiiColumn(t.columns.find((c) => c.name === d), grades))
  );
  const dropPii = (policy.masking ?? 'drop') === 'drop';
  const dims = (dropPii ? requested.filter((d) => !piiRequested.includes(d)) : requested).slice(0, 5);
  if (!dims.includes('dt') && tables.some((t) => t.columns.some((c) => c.name === 'dt'))) dims.unshift('dt');

  const measures = opts.measures?.length ? opts.measures : autoMeasures(tables, refs);
  const dimTags: FieldTag[] = dims.map((d) => {
    const host = tables.find((t) => t.columns.some((c) => c.name === d)) ?? source;
    const col = host.columns.find((c) => c.name === d)!;
    const alias = refs[tables.indexOf(host)]?.alias ?? refs[0].alias;
    return {
      field: d,
      type: col.type,
      comment: col.comment,
      suggestedName: d,
      roots: [],
      meaning: `透传 ${alias}.${d}`,
      confidence: 0.9,
      sensitive: false,
      logic: {
        kind: 'passthrough',
        desc: col.comment || d,
        sources: [{ alias, column: d }],
      },
    };
  });
  const measureTags: FieldTag[] = measures.map((m) => ({
    field: m.column,
    type: m.type || (m.op.includes('COUNT') ? 'BIGINT' : 'DECIMAL(18,2)'),
    comment: m.comment,
    suggestedName: m.name,
    roots: m.name.includes('cnt') ? ['cnt'] : m.name.includes('gmv') ? ['gmv'] : [],
    meaning: `${m.op}(${m.alias}.${m.column})`,
    confidence: 0.88,
    sensitive: false,
    logic: {
      kind: 'aggregate',
      desc: m.comment,
      sources: [{ alias: m.alias, column: m.column }],
      op: m.op,
      filter: m.filter,
    },
  }));
  const fieldTags: FieldTag[] = [...dimTags, ...measureTags];
  if (fieldTags.some((t) => t.suggestedName === 'gmv') && fieldTags.some((t) => t.suggestedName === 'refund_amt')) {
    fieldTags.push({
      field: 'gmv',
      type: 'DECIMAL(18,2)',
      comment: '净成交额',
      suggestedName: 'net_gmv',
      roots: ['gmv'],
      meaning: 'gmv - refund_amt',
      confidence: 0.9,
      sensitive: false,
      logic: {
        kind: 'derive',
        desc: '成交金额减去退款金额',
        expr: 'gmv - refund_amt',
      },
    });
  }

  const tableName = suggestDwsName(domain, `${process}_sum`);
  const columns: Column[] = fieldTags.map((t) => ({
    name: t.suggestedName,
    type: t.type,
    comment: t.comment,
    nullable: false,
    logic: t.logic,
  }));
  const ddl = renderCreateTable(
    {
      name: tableName,
      comment: `${domain}域-${process}日汇总`,
      columns,
      partition: 'dt',
      primaryKeys: dims,
    },
    'hive'
  );
  const etlSql = renderEtlSql(
    {
      name: tableName,
      sources: refs,
      joins,
      columns,
      partition: 'dt',
    },
    tables
  );

  const knownRoots = (opts.roots ?? []).map((r) => r.code);
  const specIssues: SpecIssue[] = [
    ...validateTableName(tableName, 'DWS'),
    ...(knownRoots.length ? fieldTags.flatMap((t) => validateFieldName(t.suggestedName, knownRoots)) : []),
  ];
  if (tables.length > 1) {
    specIssues.push({
      level: 'info',
      rule: 'source',
      message: `来源 ${tables.map((t, i) => `${refs[i].alias}=${t.name}`).join('、')}`,
    });
  }
  if (dropPii && piiRequested.length) {
    specIssues.push({
      level: 'error',
      rule: 'masking',
      message: `DWS 分层规范禁止保留 PII，请去掉维度：${piiRequested.join('、')} 后重新生成`,
    });
  } else if (dropPii) {
    specIssues.push({
      level: 'info',
      rule: 'masking',
      message: `汇总层脱敏策略为「${maskingLabel(policy.masking)}」，草案未带入敏感字段`,
    });
  }
  specIssues.push({
    level: 'info',
    rule: 'null',
    message: `空值处理：${nullLabel(policy.nullHandling)}${policy.nullFill ? `（${policy.nullFill}）` : ''}`,
  });

  return {
    id: `draft-${Date.now()}`,
    projectId: opts.projectId,
    sourceTableId: source.id,
    sources: refs,
    joins,
    targetLayer: 'DWS',
    domainCode: domain,
    domainConfidence: 0.91,
    grain: '日汇总',
    primaryKeys: dims,
    fieldTags,
    ddl,
    etlSql,
    qualityRules: [
      {
        id: `qr-${tableName}-vol`,
        table: tableName,
        type: 'volatility',
        logic: fieldTags.some((t) => t.suggestedName === 'gmv') ? 'gmv 日同比' : '行数日同比',
        threshold: '波动 ≤ 30%',
        status: 'idle',
      },
    ],
    specIssues,
    status: 'pending_review',
    createdAt: new Date().toISOString(),
  };
}

export function tableFromDwsDraft(draft: ModelingDraft): WarehouseTable {
  const pii = draft.fieldTags.some((t) => t.sensitive);
  const columns = columnsFromDraft(draft).map((col) => {
    const tag = draft.fieldTags.find((t) => t.suggestedName === col.name);
    return { ...col, logic: tag?.logic };
  });
  return {
    id: `tbl-${draft.id}`,
    projectId: draft.projectId,
    layer: 'DWS',
    name: draft.ddl.match(/CREATE TABLE\s+`?(\w+)`?/)?.[1] ?? 'dws_unknown',
    comment: `${draft.domainCode}域-日汇总`,
    domain: draft.domainCode,
    grain: draft.grain,
    period: 'di',
    columns,
    partition: 'dt',
    status: 'published',
    createdFrom: draft.sourceTableId,
    sources: draft.sources,
    joins: draft.joins,
    grade: pii ? 'L3' : 'L1',
  };
}
