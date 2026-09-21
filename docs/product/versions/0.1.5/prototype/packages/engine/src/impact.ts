import { resolvedSources } from './fieldLogic';
import type { Column, FieldLogic, TableSourceRef, WarehouseTable } from './types';

export type ImpactLevel = 'none' | 'confirm' | 'review' | 'table_warn';

export interface ImpactHit {
  tableId: string;
  tableName: string;
  field?: string;
  via: 'dimension' | 'measure' | 'join_key' | 'filter' | 'derive' | 'source';
  note: string;
}

export interface ImpactReport {
  level: ImpactLevel;
  hits: ImpactHit[];
  needConfirm: boolean;
  title: string;
  lines: string[];
}

export function tableUpstreamIds(t: WarehouseTable): string[] {
  return resolvedSources(t).map((s) => s.tableId);
}

export function tableDependentsOf(id: string, tables: WarehouseTable[]): WarehouseTable[] {
  return tables.filter((t) => t.id !== id && tableUpstreamIds(t).includes(id));
}

function aliasOf(t: WarehouseTable, sourceId: string): string | undefined {
  return resolvedSources(t).find((s) => s.tableId === sourceId)?.alias;
}

function fieldRefsSource(col: Column, alias: string, column: string): boolean {
  const l = col.logic;
  if (!l) return false;
  if (l.sources?.some((s) => s.alias === alias && s.column === column)) return true;
  if (l.expr && (l.expr.includes(`${alias}.${column}`) || l.kind === 'derive')) {
    if (l.expr.includes(`${alias}.${column}`)) return true;
  }
  if (l.filter?.includes(`${alias}.${column}`)) return true;
  return false;
}

export function fieldDependents(
  sourceId: string,
  column: string,
  tables: WarehouseTable[]
): ImpactHit[] {
  const hits: ImpactHit[] = [];
  for (const t of tableDependentsOf(sourceId, tables)) {
    const alias = aliasOf(t, sourceId);
    if (!alias) {
      hits.push({ tableId: t.id, tableName: t.name, via: 'source', note: '以来源表引用' });
      continue;
    }
    for (const j of t.joins ?? []) {
      if (
        (j.leftAlias === alias && j.leftColumn === column) ||
        (j.rightAlias === alias && j.rightColumn === column)
      ) {
        hits.push({ tableId: t.id, tableName: t.name, via: 'join_key', note: `关联键 ${alias}.${column}` });
      }
    }
    for (const col of t.columns) {
      const l = col.logic;
      if (!l) continue;
      if (fieldRefsSource(col, alias, column)) {
        const via =
          l.kind === 'aggregate' ? 'measure' : l.kind === 'derive' ? 'derive' : l.kind === 'passthrough' ? 'dimension' : 'measure';
        hits.push({
          tableId: t.id,
          tableName: t.name,
          field: col.name,
          via,
          note: l.desc || col.comment || col.name,
        });
      }
    }
    if (!hits.some((h) => h.tableId === t.id)) {
      hits.push({ tableId: t.id, tableName: t.name, via: 'source', note: '以来源表引用' });
    }
  }
  return hits;
}

function logicKey(l?: FieldLogic): string {
  if (!l) return '';
  return JSON.stringify({
    kind: l.kind,
    desc: l.desc ?? '',
    op: l.op ?? '',
    expr: l.expr ?? '',
    filter: l.filter ?? '',
    sources: l.sources ?? [],
  });
}

function sourcesKey(s?: TableSourceRef[]): string {
  return JSON.stringify((s ?? []).map((x) => `${x.alias}:${x.tableId}`).sort());
}

export function assessImpact(
  before: WarehouseTable,
  after: Pick<WarehouseTable, 'name' | 'comment' | 'grain' | 'joins' | 'sources' | 'createdFrom' | 'filter' | 'columns' | 'grade' | 'period' | 'partition' | 'status' | 'domain'>,
  tables: WarehouseTable[]
): ImpactReport {
  const hits: ImpactHit[] = [];
  let level: ImpactLevel = 'none';
  const bump = (next: ImpactLevel) => {
    const order: ImpactLevel[] = ['none', 'confirm', 'review', 'table_warn'];
    if (order.indexOf(next) > order.indexOf(level)) level = next;
  };

  const grainChanged = (before.grain ?? '') !== (after.grain ?? '');
  const joinChanged = JSON.stringify(before.joins ?? []) !== JSON.stringify(after.joins ?? []);
  const srcChanged = sourcesKey(resolvedSources(before)) !== sourcesKey(resolvedSources(after as WarehouseTable));
  if (grainChanged || joinChanged) {
    bump('table_warn');
    for (const t of tableDependentsOf(before.id, tables)) {
      hits.push({
        tableId: t.id,
        tableName: t.name,
        via: 'source',
        note: grainChanged ? '上游粒度变化' : '上游关联变化',
      });
    }
  }

  const beforeCols = new Map(before.columns.map((c) => [c.name, c]));
  const afterCols = new Map(after.columns.map((c) => [c.name, c]));

  for (const [name, old] of beforeCols) {
    const neu = afterCols.get(name);
    const downstream = fieldDependents(before.id, name, tables);
    if (!neu) {
      if (downstream.length) {
        bump('confirm');
        hits.push(...downstream.map((h) => ({ ...h, note: `删除字段 ${name}：${h.note}` })));
      }
      continue;
    }
    const typeChanged = old.type !== neu.type;
    const logicChanged = logicKey(old.logic) !== logicKey(neu.logic);
    const cosmetic =
      (old.comment ?? '') !== (neu.comment ?? '') ||
      (old.grade ?? '') !== (neu.grade ?? '') ||
      Boolean(old.sensitive) !== Boolean(neu.sensitive);
    if (typeChanged && downstream.length) {
      bump('confirm');
      hits.push(...downstream.map((h) => ({ ...h, note: `类型 ${old.type}→${neu.type}：${h.note}` })));
    }
    if (logicChanged && downstream.length) {
      bump('review');
      hits.push(...downstream.map((h) => ({ ...h, note: `口径/加工变更，待复核：${h.field || h.tableName}` })));
    }
    if (cosmetic && !typeChanged && !logicChanged) {
      /* 不拦 */
    }
  }

  const renamed = [...afterCols.keys()].filter((n) => !beforeCols.has(n));
  if (renamed.length && before.columns.length === after.columns.length) {
    for (const old of before.columns) {
      if (!afterCols.has(old.name)) {
        const ds = fieldDependents(before.id, old.name, tables);
        if (ds.length) {
          bump('confirm');
          hits.push(...ds.map((h) => ({ ...h, note: `字段更名（原 ${old.name}）：${h.note}` })));
        }
      }
    }
  }

  if (srcChanged && !hits.length) {
    /* 来源变化但无下游 */
  }

  const uniq = new Map<string, ImpactHit>();
  for (const h of hits) {
    uniq.set(`${h.tableId}|${h.field ?? ''}|${h.note}`, h);
  }
  const list = [...uniq.values()];
  const titles: Record<ImpactLevel, string> = {
    none: '无结构影响',
    confirm: '将影响下游，请确认',
    review: '下游字段待复核',
    table_warn: '下游整表可能受影响',
  };
  return {
    level,
    hits: list,
    needConfirm: level !== 'none' && list.length > 0,
    title: titles[level],
    lines: list.length
      ? list.map((h) => `${h.tableName}${h.field ? '.' + h.field : ''} — ${h.note}`)
      : ['只改了注释、等级等，不影响下游结构。'],
  };
}
