import type { Column, TableSnapshot, WarehouseTable } from './types';

export function snapshotTable(t: Pick<WarehouseTable, keyof TableSnapshot>): TableSnapshot {
  return {
    name: t.name,
    comment: t.comment,
    domain: t.domain,
    grain: t.grain,
    period: t.period,
    partition: t.partition,
    status: t.status,
    grade: t.grade,
    columns: (t.columns ?? []).map((c) => ({ ...c })),
  };
}

export function sameSnapshot(a: TableSnapshot, b: TableSnapshot): boolean {
  return JSON.stringify(normalize(a)) === JSON.stringify(normalize(b));
}

function normalize(s: TableSnapshot) {
  return {
    name: s.name,
    comment: s.comment ?? '',
    domain: s.domain ?? '',
    grain: s.grain ?? '',
    period: s.period ?? '',
    partition: s.partition ?? '',
    status: s.status,
    grade: s.grade ?? '',
    columns: s.columns.map((c) => ({
      name: c.name,
      type: c.type,
      comment: c.comment ?? '',
      nullable: c.nullable !== false,
      defaultValue: c.defaultValue ?? '',
      sensitive: Boolean(c.sensitive),
      grade: c.grade ?? '',
      enumValues: c.enumValues ?? [],
    })),
  };
}

export interface FieldChange {
  field: string;
  from: string;
  to: string;
}

export interface ColumnDiff {
  name: string;
  changes: FieldChange[];
}

export interface TableDiff {
  meta: FieldChange[];
  added: Column[];
  removed: Column[];
  changed: ColumnDiff[];
}

export function diffSnapshots(from: TableSnapshot, to: TableSnapshot): TableDiff {
  const meta: FieldChange[] = [];
  const keys: (keyof Pick<TableSnapshot, 'name' | 'comment' | 'domain' | 'grain' | 'period' | 'partition' | 'status' | 'grade'>)[] =
    ['name', 'comment', 'domain', 'grain', 'period', 'partition', 'status', 'grade'];
  for (const k of keys) {
    const a = String(from[k] ?? '');
    const b = String(to[k] ?? '');
    if (a !== b) meta.push({ field: k, from: a || '—', to: b || '—' });
  }
  const aMap = new Map(from.columns.map((c) => [c.name, c]));
  const bMap = new Map(to.columns.map((c) => [c.name, c]));
  const added: Column[] = [];
  const removed: Column[] = [];
  const changed: ColumnDiff[] = [];
  for (const [name, col] of bMap) {
    const old = aMap.get(name);
    if (!old) {
      added.push(col);
      continue;
    }
    const changes: FieldChange[] = [];
    if (old.type !== col.type) changes.push({ field: '类型', from: old.type, to: col.type });
    if ((old.comment ?? '') !== (col.comment ?? '')) {
      changes.push({ field: '注释', from: old.comment || '—', to: col.comment || '—' });
    }
    if (Boolean(old.sensitive) !== Boolean(col.sensitive)) {
      changes.push({ field: '敏感', from: old.sensitive ? '是' : '否', to: col.sensitive ? '是' : '否' });
    }
    if ((old.grade ?? '') !== (col.grade ?? '')) {
      changes.push({ field: '等级', from: old.grade || '继承表', to: col.grade || '继承表' });
    }
    if ((old.nullable !== false) !== (col.nullable !== false)) {
      changes.push({ field: '可空', from: old.nullable === false ? '否' : '是', to: col.nullable === false ? '否' : '是' });
    }
    if (changes.length) changed.push({ name, changes });
  }
  for (const [name, col] of aMap) {
    if (!bMap.has(name)) removed.push(col);
  }
  return { meta, added, removed, changed };
}
