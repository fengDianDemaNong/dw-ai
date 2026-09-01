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
    createdFrom: t.createdFrom,
    sources: t.sources?.map((s) => ({ ...s })),
    joins: t.joins?.map((j) => ({ ...j })),
    filter: t.filter,
    columns: (t.columns ?? []).map((c) => ({ ...c, logic: c.logic ? { ...c.logic, sources: c.logic.sources?.map((s) => ({ ...s })) } : undefined })),
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
    createdFrom: s.createdFrom ?? '',
    sources: s.sources ?? [],
    joins: s.joins ?? [],
    filter: s.filter ?? '',
    columns: s.columns.map((c) => ({
      name: c.name,
      type: c.type,
      comment: c.comment ?? '',
      nullable: c.nullable !== false,
      defaultValue: c.defaultValue ?? '',
      sensitive: Boolean(c.sensitive),
      grade: c.grade ?? '',
      enumValues: c.enumValues ?? [],
      logic: c.logic ?? null,
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
  const keys: (keyof Pick<TableSnapshot, 'name' | 'comment' | 'domain' | 'grain' | 'period' | 'partition' | 'status' | 'grade' | 'filter'>)[] =
    ['name', 'comment', 'domain', 'grain', 'period', 'partition', 'status', 'grade', 'filter'];
  for (const k of keys) {
    const a = String(from[k] ?? '');
    const b = String(to[k] ?? '');
    if (a !== b) meta.push({ field: k, from: a || '—', to: b || '—' });
  }
  const srcA = JSON.stringify(from.sources ?? []);
  const srcB = JSON.stringify(to.sources ?? []);
  if (srcA !== srcB) meta.push({ field: '来源', from: srcA || '—', to: srcB || '—' });
  const joinA = JSON.stringify(from.joins ?? []);
  const joinB = JSON.stringify(to.joins ?? []);
  if (joinA !== joinB) meta.push({ field: '关联', from: joinA || '—', to: joinB || '—' });
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
    const logicA = JSON.stringify(old.logic ?? null);
    const logicB = JSON.stringify(col.logic ?? null);
    if (logicA !== logicB) {
      changes.push({
        field: '加工',
        from: old.logic?.desc || old.logic?.kind || '—',
        to: col.logic?.desc || col.logic?.kind || '—',
      });
    }
    if (changes.length) changed.push({ name, changes });
  }
  for (const [name, col] of aMap) {
    if (!bMap.has(name)) removed.push(col);
  }
  return { meta, added, removed, changed };
}

function sqlStr(s: string) {
  return s.replace(/'/g, "''");
}

const META_LABEL: Record<string, string> = {
  name: '表名',
  comment: '表注释',
  domain: '主题域',
  grain: '粒度',
  period: '周期',
  partition: '分区',
  status: '状态',
  grade: '等级',
  filter: '表过滤',
  来源: '来源',
  关联: '关联',
};

/** 两版快照的逻辑 DDL（添加 / 删除 / 修改字段）。不按方言拆，不执行。 */
export function renderAlterSql(from: TableSnapshot, to: TableSnapshot): string {
  const diff = diffSnapshots(from, to);
  const table = to.name || from.name;
  const stmts: string[] = [];
  if (from.name && to.name && from.name !== to.name) {
    stmts.push(`ALTER TABLE ${from.name} RENAME TO ${to.name};`);
  }
  for (const m of diff.meta) {
    if (m.field === 'name') continue;
    stmts.push(`-- ${META_LABEL[m.field] || m.field}: ${m.from} → ${m.to}`);
  }
  for (const c of diff.added) {
    const nn = c.nullable === false ? ' NOT NULL' : '';
    const def = c.nullable === false && c.defaultValue ? ` DEFAULT '${sqlStr(c.defaultValue)}'` : '';
    const cmt = c.comment ? ` COMMENT '${sqlStr(c.comment)}'` : '';
    stmts.push(`ALTER TABLE ${table} ADD COLUMNS (${c.name} ${c.type}${nn}${def}${cmt});`);
  }
  for (const c of diff.removed) {
    stmts.push(`ALTER TABLE ${table} DROP COLUMN ${c.name};`);
  }
  for (const ch of diff.changed) {
    const col = to.columns.find((x) => x.name === ch.name);
    if (!col) continue;
    const struct = ch.changes.filter((c) => c.field !== '加工');
    const logic = ch.changes.find((c) => c.field === '加工');
    if (struct.length) {
      const nn = col.nullable === false ? ' NOT NULL' : '';
      const cmt = col.comment ? ` COMMENT '${sqlStr(col.comment)}'` : '';
      stmts.push(`ALTER TABLE ${table} CHANGE COLUMN ${ch.name} ${ch.name} ${col.type}${nn}${cmt};`);
    }
    if (logic) stmts.push(`-- ${ch.name} 加工: ${logic.from} → ${logic.to}`);
  }
  return stmts.length ? stmts.join('\n') : '-- 这两版结构相同，无 DDL 变更。';
}
