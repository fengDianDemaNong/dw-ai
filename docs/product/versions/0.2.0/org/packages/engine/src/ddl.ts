import { exportLabel, queryLabel } from './config/grades';
import type { Column, DataGrade, ModelingDraft, WarehouseTable } from './types';

export type DdlDialect = 'hive' | 'doris' | 'mysql';

export const DDL_DIALECTS: { value: DdlDialect; label: string }[] = [
  { value: 'hive', label: 'Hive' },
  { value: 'doris', label: 'Doris' },
  { value: 'mysql', label: 'MySQL' },
];

export interface DdlColumn {
  name: string;
  type: string;
  comment?: string;
  nullable?: boolean;
  defaultValue?: string;
}

export interface DdlTableSpec {
  name: string;
  comment?: string;
  columns: DdlColumn[];
  partition?: string;
  primaryKeys?: string[];
}

export interface DdlSpecOptions {
  grades?: DataGrade[];
}

function quoteIdent(name: string, dialect: DdlDialect): string {
  if (dialect === 'hive') return name;
  return `\`${name}\``;
}

function escComment(text: string): string {
  return text.replace(/'/g, "''");
}

function joinComment(parts: Array<string | undefined>): string {
  return parts.map((p) => p?.trim()).filter(Boolean).join(' | ');
}

function describeGrade(code: string | undefined, grades?: DataGrade[], withPolicy = false): string[] {
  if (!code) return [];
  const g = grades?.find((x) => x.code === code);
  const head = g ? `等级:${g.code} ${g.name}` : `等级:${code}`;
  if (!g || !withPolicy) return [head];
  return [head, `查询:${queryLabel(g.query)}`, `导出:${exportLabel(g.export)}`];
}

function isNumericLogical(type: string): boolean {
  const t = type.toLowerCase();
  return /int|decimal|double|float|number|bigint/.test(t) && !/interval/.test(t);
}

function formatDefault(value: string, type: string, dialect: DdlDialect): string {
  const v = value.trim();
  if (!v) return '';
  if (/^null$/i.test(v)) return 'NULL';
  if (/^(current_timestamp|current_date|now\(\))$/i.test(v)) {
    if (dialect === 'mysql' && /^now\(\)$/i.test(v)) return 'CURRENT_TIMESTAMP';
    return v.toUpperCase().startsWith('NOW') ? 'CURRENT_TIMESTAMP' : v.toUpperCase();
  }
  if (isNumericLogical(type) && /^-?\d+(\.\d+)?$/.test(v)) return v;
  if (/boolean|bool/.test(type.toLowerCase())) {
    if (/^(true|1|yes)$/i.test(v)) return dialect === 'mysql' ? '1' : 'TRUE';
    if (/^(false|0|no)$/i.test(v)) return dialect === 'mysql' ? '0' : 'FALSE';
  }
  return `'${escComment(v)}'`;
}

function colSuffix(col: DdlColumn, dialect: DdlDialect): string {
  const nulls = col.nullable === false ? ' NOT NULL' : '';
  const def =
    col.defaultValue?.trim() && col.nullable === false
      ? ` DEFAULT ${formatDefault(col.defaultValue, col.type, dialect)}`
      : '';
  const comment = col.comment ? ` COMMENT '${escComment(col.comment)}'` : '';
  return `${nulls}${def}${comment}`;
}

function decimalType(raw: string): string {
  const m = raw.match(/decimal\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)/i);
  return m ? `DECIMAL(${m[1]},${m[2]})` : 'DECIMAL(18,2)';
}

/** 逻辑类型 → 方言类型。建表页用 STRING / DATETIME 等逻辑名，不绑定引擎。 */
export function mapSqlType(logical: string, dialect: DdlDialect): string {
  const raw = logical.trim();
  const t = raw.toLowerCase();
  if (t.includes('decimal')) return decimalType(raw);
  if (t.includes('boolean') || t === 'bool') {
    if (dialect === 'mysql') return 'TINYINT(1)';
    return 'BOOLEAN';
  }
  if (t.includes('double') || t.includes('float')) return t.includes('float') ? 'FLOAT' : 'DOUBLE';
  if (t.includes('bigint')) return 'BIGINT';
  if (t.includes('tinyint') || t.includes('smallint')) return 'INT';
  if (/\bint\b/.test(t) || t === 'integer') return 'INT';
  if (t.includes('datetime') || t.includes('timestamp')) {
    if (dialect === 'hive') return 'STRING';
    return 'DATETIME';
  }
  if (t.includes('date') && !t.includes('update')) {
    if (dialect === 'hive') return 'STRING';
    if (dialect === 'mysql') return 'DATE';
    return 'DATE';
  }
  if (dialect === 'hive') return 'STRING';
  if (dialect === 'doris') return 'VARCHAR(65533)';
  return 'VARCHAR(255)';
}

function resolvePrimaryKeys(spec: DdlTableSpec): string[] {
  const names = new Set(spec.columns.map((c) => c.name));
  const fromSpec = (spec.primaryKeys ?? []).filter((k) => names.has(k));
  if (fromSpec.length) return fromSpec;
  const idLike = spec.columns.find((c) => c.name === 'id' || c.name.endsWith('_id'));
  if (idLike) return [idLike.name];
  const first = spec.columns.find((c) => c.name !== spec.partition);
  return first ? [first.name] : spec.columns[0] ? [spec.columns[0].name] : [];
}

function hiveBody(spec: DdlTableSpec): string {
  const part = spec.partition;
  return spec.columns
    .filter((c) => c.name !== part)
    .map((c) => {
      return `    ${c.name.padEnd(22)} ${mapSqlType(c.type, 'hive').padEnd(16)}${colSuffix(c, 'hive')}`;
    })
    .join(',\n');
}

function renderHive(spec: DdlTableSpec): string {
  const body = hiveBody(spec);
  const tableComment = spec.comment ? ` COMMENT '${escComment(spec.comment)}'` : '';
  const partCol = spec.partition ? spec.columns.find((c) => c.name === spec.partition) : undefined;
  const partType = partCol ? mapSqlType(partCol.type, 'hive') : 'STRING';
  const partComment = partCol?.comment ? ` COMMENT '${escComment(partCol.comment)}'` : '';
  const part = spec.partition ? `\nPARTITIONED BY (${spec.partition} ${partType}${partComment})` : '';
  return `CREATE TABLE ${spec.name} (\n${body}\n)${tableComment}${part};`;
}

function dorisOrdered(spec: DdlTableSpec, keys: string[]): DdlColumn[] {
  const keySet = new Set(keys);
  const keyed = keys
    .map((k) => spec.columns.find((c) => c.name === k))
    .filter((c): c is DdlColumn => Boolean(c));
  const rest = spec.columns.filter((c) => !keySet.has(c.name));
  return [...keyed, ...rest];
}

function renderDoris(spec: DdlTableSpec): string {
  const keys = resolvePrimaryKeys(spec);
  const ordered = dorisOrdered(spec, keys);
  const body = ordered
    .map((c) => {
      return `    ${quoteIdent(c.name, 'doris')} ${mapSqlType(c.type, 'doris')}${colSuffix(c, 'doris')}`;
    })
    .join(',\n');
  const keyList = keys.map((k) => quoteIdent(k, 'doris')).join(', ');
  const dist = quoteIdent(keys[0] ?? spec.columns[0]?.name ?? 'id', 'doris');
  const tableComment = spec.comment ? `\nCOMMENT '${escComment(spec.comment)}'` : '';
  const part = spec.partition
    ? `\nPARTITION BY RANGE(${quoteIdent(spec.partition, 'doris')}) ()`
    : '';
  return [
    `CREATE TABLE ${quoteIdent(spec.name, 'doris')} (`,
    body,
    `)`,
    `DUPLICATE KEY(${keyList})`,
    tableComment.trimStart(),
    part.trimStart(),
    `DISTRIBUTED BY HASH(${dist}) BUCKETS 8;`,
  ]
    .filter(Boolean)
    .join('\n');
}

function renderMysql(spec: DdlTableSpec): string {
  const keys = resolvePrimaryKeys(spec).filter((k) => spec.columns.some((c) => c.name === k));
  const lines = spec.columns.map((c) => {
    return `    ${quoteIdent(c.name, 'mysql')} ${mapSqlType(c.type, 'mysql')}${colSuffix(c, 'mysql')}`;
  });
  if (keys.length) {
    lines.push(`    PRIMARY KEY (${keys.map((k) => quoteIdent(k, 'mysql')).join(', ')})`);
  }
  const tableComment = spec.comment ? ` COMMENT='${escComment(spec.comment)}'` : '';
  return `CREATE TABLE ${quoteIdent(spec.name, 'mysql')} (\n${lines.join(',\n')}\n)${tableComment};`;
}

export function renderCreateTable(spec: DdlTableSpec, dialect: DdlDialect): string {
  if (!spec.name || !spec.columns.length) return '';
  if (dialect === 'doris') return renderDoris(spec);
  if (dialect === 'mysql') return renderMysql(spec);
  return renderHive(spec);
}

export function specFromTable(
  table: Pick<WarehouseTable, 'name' | 'comment' | 'columns' | 'partition'> & {
    primaryKeys?: string[];
    grade?: string;
    domain?: string;
    grain?: string;
  },
  opts?: DdlSpecOptions
): DdlTableSpec {
  const grades = opts?.grades;
  return {
    name: table.name,
    comment: joinComment([
      table.comment,
      ...describeGrade(table.grade, grades, true),
      table.domain ? `主题域:${table.domain}` : undefined,
      table.grain ? `粒度:${table.grain}` : undefined,
    ]),
    columns: table.columns.map((c) => {
      const grade = c.grade || table.grade;
      return {
        name: c.name,
        type: c.type,
        comment: joinComment([
          c.comment,
          ...describeGrade(grade, grades, Boolean(c.grade)),
          c.sensitive ? '敏感' : undefined,
        ]),
        nullable: c.nullable,
        defaultValue: c.nullable === false ? c.defaultValue : undefined,
      };
    }),
    partition: table.partition,
    primaryKeys: table.primaryKeys,
  };
}

export function specFromModelingDraft(draft: ModelingDraft, opts?: DdlSpecOptions): DdlTableSpec {
  const name = draft.ddl.match(/CREATE TABLE\s+`?(\w+)`?/)?.[1] ?? 'unknown_table';
  const columns = draft.fieldTags
    .filter((t) => !t.dropped)
    .map((t) => ({
      name: t.suggestedName,
      type: t.type,
      comment: joinComment([t.comment || t.meaning, t.sensitive ? '敏感' : undefined]),
      nullable: !draft.primaryKeys.includes(t.suggestedName),
    }));
  return {
    name,
    comment: joinComment([`${draft.domainCode}域-${draft.grain}`]),
    columns,
    partition: 'dt',
    primaryKeys: draft.primaryKeys,
  };
}

export function columnsFromDraft(draft: ModelingDraft): Column[] {
  return draft.fieldTags
    .filter((t) => !t.dropped)
    .map((t) => ({
      name: t.suggestedName,
      type: t.type,
      comment: t.comment || t.meaning,
      nullable: !draft.primaryKeys.includes(t.suggestedName),
      sensitive: Boolean(t.sensitive),
    }));
}
