import type { FactoryConfig, FilterSpec, MaterializeRec, MeasureSpec, Metric, WarehouseTable } from './types';

const DIALECT_LIMIT: Record<string, (n: number) => string> = {
  Hive: (n) => `LIMIT ${n}`,
  Spark: (n) => `LIMIT ${n}`,
  StarRocks: (n) => `LIMIT ${n}`,
};

function quote(v: string): string {
  if (/^-?\d+(\.\d+)?$/.test(v)) return v;
  return `'${v.replace(/'/g, "''")}'`;
}

export function filterSql(f: FilterSpec): string {
  if (f.op === 'IN') return `${f.field} IN (${f.value.split(',').map((x) => quote(x.trim())).join(', ')})`;
  if (f.op === 'LIKE') return `${f.field} LIKE ${quote(f.value)}`;
  return `${f.field} ${f.op} ${quote(f.value)}`;
}

export function timeRangeToFilter(range: string): FilterSpec {
  const today = '2026-08-27';
  const map: Record<string, string> = {
    近7天: '2026-08-21',
    近30天: '2026-07-29',
    本月: '2026-08-01',
    近90天: '2026-05-30',
  };
  return { field: 'dt', op: '>=', value: map[range] ?? '2026-08-01' };
}

export function generateSql(cfg: FactoryConfig, dialect = 'StarRocks'): { sql: string; hit?: string } {
  const dims = cfg.dimensions.length ? cfg.dimensions : ['dt'];
  const measureSelect = cfg.measures
    .map((m) => `${m.agg}(${m.field}) AS ${m.alias}`)
    .join(',\n    ');
  const filters = [...cfg.filters];
  if (!filters.some((f) => f.field === 'dt')) filters.unshift(timeRangeToFilter(cfg.timeRange));
  const where = filters.map(filterSql).join('\n  AND ');
  const sql = `SELECT\n    ${dims.join(', ')},\n    ${measureSelect}\nFROM ${cfg.table}\nWHERE ${where}\nGROUP BY ${dims.join(', ')}\n${DIALECT_LIMIT[dialect]?.(100) ?? 'LIMIT 100'};`;
  return { sql };
}

export function routeQuery(
  cfg: FactoryConfig,
  recs: MaterializeRec[],
  tables: WarehouseTable[]
): { sql: string; hit?: string; rewritten: boolean } {
  const base = generateSql(cfg);
  const dims = new Set(cfg.dimensions);
  const online = recs.filter((r) => r.status === 'online' || (r.status === 'gray' && r.grayPercent >= 50));
  for (const rec of online) {
    const table = tables.find((t) => t.name === rec.targetTable);
    if (!table) continue;
    const tableDims = table.columns.filter((c) => !/amt|cnt|gmv|qty/.test(c.name)).map((c) => c.name);
    const covers = [...dims].every((d) => tableDims.includes(d) || d === 'dt');
    const measuresOk = cfg.measures.every((m) =>
      table.columns.some((c) => c.name === m.alias || c.name === m.field)
    );
    if (covers && measuresOk) {
      const rewrittenCfg: FactoryConfig = { ...cfg, table: rec.targetTable, layer: 'DWS' };
      const gen = generateSql(rewrittenCfg);
      return { sql: gen.sql, hit: rec.targetTable, rewritten: true };
    }
  }
  return { ...base, rewritten: false };
}

export function metricSignature(m: Pick<Metric, 'name' | 'calculationLogic'>): string {
  return `${m.name}::${m.calculationLogic.replace(/\s+/g, ' ').trim().toLowerCase()}`;
}

export function checkDuplicate(
  incoming: Pick<Metric, 'name' | 'calculationLogic'>,
  existing: Metric[]
): { action: 'ok' | 'reject' | 'reuse'; match?: Metric; reason: string } {
  const sameName = existing.filter((m) => m.name === incoming.name);
  if (!sameName.length) return { action: 'ok', reason: '名称未被占用' };
  const logic = incoming.calculationLogic.replace(/\s+/g, ' ').trim().toLowerCase();
  const sameLogic = sameName.find(
    (m) => m.calculationLogic.replace(/\s+/g, ' ').trim().toLowerCase() === logic
  );
  if (sameLogic) {
    return { action: 'reuse', match: sameLogic, reason: '名称与计算逻辑均相同，应复用已有指标' };
  }
  return {
    action: 'reject',
    match: sameName[0],
    reason: `已存在同名指标「${sameName[0].name}」但逻辑不同，禁止注册。请改名或合并逻辑。`,
  };
}

export function recommendMeasures(selectedDims: string[], table: WarehouseTable | undefined, metrics: Metric[]): string[] {
  if (!table) return [];
  const related = metrics.filter(
    (m) => m.sourceTable === table.name && selectedDims.every((d) => m.dimensions.includes(d) || d === 'dt')
  );
  const fromMetrics = related.map((m) => m.measure);
  const numeric = table.columns.filter((c) => /decimal|bigint|int|double/i.test(c.type)).map((c) => c.name);
  return [...new Set([...fromMetrics, ...numeric])].slice(0, 5);
}

export function previewRows(cfg: FactoryConfig): Record<string, string | number>[] {
  const dims = cfg.dimensions.length ? cfg.dimensions : ['dt'];
  const users = ['新用户', '老用户'];
  const regions = ['北京', '上海', '杭州'];
  const cats = ['手机', '家电', '服饰'];
  const rows: Record<string, string | number>[] = [];
  for (let i = 0; i < 6; i++) {
    const row: Record<string, string | number> = {};
    for (const d of dims) {
      if (d === 'dt') row.dt = `2026-08-${String(20 + (i % 7)).padStart(2, '0')}`;
      else if (d.includes('user')) row[d] = users[i % 2];
      else if (d.includes('region')) row[d] = regions[i % 3];
      else if (d.includes('category') || d.includes('item')) row[d] = cats[i % 3];
      else row[d] = `v${i}`;
    }
    for (const m of cfg.measures) {
      const base = m.agg.includes('COUNT') ? 800 + i * 37 : 1255000 + i * 188000;
      row[m.alias] = m.agg.includes('COUNT') ? base : Number((base / 10000).toFixed(1));
    }
    rows.push(row);
  }
  return rows;
}
