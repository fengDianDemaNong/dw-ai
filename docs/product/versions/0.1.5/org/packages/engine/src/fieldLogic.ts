import type {
  Column,
  FieldLogic,
  FieldLogicKind,
  TableJoin,
  TableSourceRef,
  WarehouseTable,
} from './types';

export const LOGIC_KIND_ORDER: FieldLogicKind[] = [
  'passthrough',
  'transform',
  'aggregate',
  'derive',
  'constant',
];

export const LOGIC_KIND_LABEL: Record<FieldLogicKind, string> = {
  passthrough: '透传',
  transform: '转换',
  aggregate: '聚合',
  derive: '派生',
  constant: '常量',
};

/** 规范中心与字段抽屉共用的类型说明（产品写死，不是第二套词根）。 */
export interface LogicKindGuide {
  definition: string;
  typicalLayers: string;
  when: string;
  vs: string;
  goodExample: string;
  badExample: string;
}

export const LOGIC_KIND_GUIDE: Record<FieldLogicKind, LogicKindGuide> = {
  passthrough: {
    definition: '上游一列原样落到本列，不改值、不压行。',
    typicalLayers: '常见于 ODS→DWD，以及汇总表上的维度。',
    when: '维度、业务键或需要原样带下的属性。',
    vs: '和转换的差别：不处理值。和聚合的差别：不把多行收成一行。',
    goodExample: 'o.user_type → user_type',
    badExample: '把 SUM(amt) 标成透传',
  },
  transform: {
    definition: '仍是一行对一行，但值被处理（脱敏、映射、格式）。',
    typicalLayers: '常见于 ODS→DWD。',
    when: '需要改值但不改变粒度，例如哈希、掩码、状态码映射。',
    vs: '不是汇总（那是聚合）；不是用本表其它字段再算（那是派生）。',
    goodExample: 'hash(u.mobile) → mobile_hash',
    badExample: '把 COUNT(order_id) 标成转换',
  },
  aggregate: {
    definition: '多行收成一行。必须有 SUM / COUNT 等运算，粒度由维度决定。',
    typicalLayers: '常见于 DWD→DWS。',
    when: '度量需要按维度汇总。',
    vs: '和转换的差别：压行。和派生的差别：直接扫源表聚合，而不是用本表已算出的字段。',
    goodExample: "SUM(o.order_pay_amt) → gmv",
    badExample: '把 o.shop_id 原样带下却标成聚合',
  },
  derive: {
    definition: '用本表已算出的字段再算，不再直接扫源表聚合。',
    typicalLayers: '常见于 DWD→DWS，也可用于明细上的组合字段。',
    when: '净额、占比、拼接等依赖本表其它列。',
    vs: '和聚合的差别：输入是本表字段，不是源表多行。和转换的差别：转换读的是上游列。',
    goodExample: 'gmv - refund_amt → net_gmv',
    badExample: '把 SUM(o.amt) 标成派生',
  },
  constant: {
    definition: '与源数据无关的固定值或分区键。',
    typicalLayers: '各层都可用。',
    when: '分区 dt、业务日、字面量标记。',
    vs: '不引用上游列；有来源列就应改用透传或转换。',
    goodExample: "分区 dt、字面量 'CN'",
    badExample: '把 o.dt 原样带下却标成常量（应标透传）',
  },
};

export function resolvedSources(t: Pick<WarehouseTable, 'sources' | 'createdFrom'>): TableSourceRef[] {
  if (t.sources?.length) return t.sources;
  if (t.createdFrom) return [{ tableId: t.createdFrom, alias: 's' }];
  return [];
}

export function hasFieldLogic(col: Column): boolean {
  const l = col.logic;
  if (!l) return false;
  return Boolean(l.desc || l.kind || l.op || l.expr || l.sources?.length);
}

export function logicSummary(col: Column): string {
  const l = col.logic;
  if (!l || (!l.desc && !l.kind && !l.expr && !l.sources?.length)) return '尚未定义';
  const kind = l.kind ? LOGIC_KIND_LABEL[l.kind] : '';
  const expr = renderFieldExpr(col);
  if (l.desc && expr) return `${kind} · ${l.desc}`;
  return l.desc || expr || kind || '尚未定义';
}

export function renderFieldExpr(col: Column): string {
  const l = col.logic;
  if (!l) return '';
  if (l.kind === 'constant') return l.expr || col.name;
  if (l.kind === 'derive') {
    if (!l.expr) return '';
    return l.filter ? `${l.expr} /* ${l.filter} */` : l.expr;
  }
  const src = (l.sources ?? []).map((s) => `${s.alias}.${s.column}`).join(', ');
  if (l.op && src) {
    const body = l.op === 'COUNT_DISTINCT' ? `COUNT(DISTINCT ${src})` : `${l.op}(${src})`;
    if (l.filter) {
      return l.kind === 'aggregate' || l.op === 'COUNT_DISTINCT' || l.op === 'SUM' || l.op === 'COUNT' || l.op === 'AVG'
        ? `${body} FILTER (WHERE ${l.filter})`
        : `${body} /* ${l.filter} */`;
    }
    return body;
  }
  if (l.expr) return l.filter ? `${l.expr} /* ${l.filter} */` : l.expr;
  if (src) return l.filter ? `${src} /* ${l.filter} */` : src;
  return '';
}

/** 该列可执行的 SELECT 片段（逻辑 SQL）。未定义加工时返回空串。 */
export function renderFieldSql(col: Column): string {
  const expr = renderFieldExpr(col);
  if (!expr) return '';
  return `${expr} AS ${col.name}`;
}

type TableSqlInput = Pick<
  WarehouseTable,
  'name' | 'sources' | 'createdFrom' | 'joins' | 'filter' | 'columns' | 'partition'
>;

function joinSql(j: TableJoin): string {
  const how = j.type === 'left' ? 'LEFT JOIN' : 'INNER JOIN';
  return `${how} ${j.rightAlias} ON ${j.leftAlias}.${j.leftColumn} = ${j.rightAlias}.${j.rightColumn}`;
}

function renderFromWhere(
  table: TableSqlInput,
  catalog: WarehouseTable[]
): { fromLines: string[]; where: string } | null {
  const srcs = resolvedSources(table);
  if (!srcs.length) return null;
  const find = (id: string) => catalog.find((t) => t.id === id);
  const from0 = srcs[0];
  const fromName = find(from0.tableId)?.name ?? from0.tableId;
  const joinLines = (table.joins ?? []).map((j) => {
    const right = srcs.find((s) => s.alias === j.rightAlias);
    const rName = right ? find(right.tableId)?.name ?? right.tableId : j.rightAlias;
    return `  ${joinSql({ ...j })}`.replace(` ${j.rightAlias} ON`, ` ${rName} ${j.rightAlias} ON`);
  });
  const extraJoins = srcs.slice(1).filter((s) => !(table.joins ?? []).some((j) => j.rightAlias === s.alias));
  for (const s of extraJoins) {
    const n = find(s.tableId)?.name ?? s.tableId;
    joinLines.push(`  -- 缺少与 ${s.alias}（${n}）的关联`);
  }
  const where = table.filter
    ? `WHERE ${table.filter}`
    : table.partition
      ? `WHERE ${from0.alias}.${table.partition} = '\${bizdate}'`
      : '';
  return {
    fromLines: [`FROM ${fromName} ${from0.alias}`, ...joinLines],
    where,
  };
}

function dimColumns(table: TableSqlInput): Column[] {
  return table.columns.filter((c) => c.logic?.kind === 'passthrough' || c.logic?.kind === 'constant');
}

/** 查询这一列的逻辑 SQL（SELECT … FROM …）。未定义加工时返回空串。 */
export function renderFieldQuerySql(table: TableSqlInput, col: Column, catalog: WarehouseTable[]): string {
  const snippet = renderFieldSql(col);
  if (!snippet) return '';
  const from = renderFromWhere(table, catalog);
  if (!from) {
    return `-- 尚未声明来源，无法生成查询 SQL\nSELECT\n    ${snippet};`;
  }
  const isAgg = col.logic?.kind === 'aggregate';
  const dims = isAgg ? dimColumns(table).filter((c) => c.name !== col.name) : [];
  const dimSnippets = dims.map((c) => renderFieldSql(c)).filter(Boolean);
  const selects = [...dimSnippets, snippet].map((s) => `    ${s}`);
  const group = dimSnippets.length ? `GROUP BY ${dims.map((c) => c.name).join(', ')}` : '';
  return ['SELECT', selects.join(',\n'), ...from.fromLines, from.where, group].filter(Boolean).join('\n') + ';';
}

export function renderEtlSql(table: TableSqlInput, catalog: WarehouseTable[]): string {
  const from = renderFromWhere(table, catalog);
  if (!from) {
    return `-- ${table.name} 尚未声明来源，无法生成加工 DML`;
  }
  const selects = table.columns.map((c) => {
    const snippet = renderFieldSql(c);
    if (!snippet) return `    -- ${c.name} 尚未定义逻辑`;
    return `    ${snippet}`;
  });
  const grouped = table.columns.some((c) => c.logic?.kind === 'aggregate');
  const dims = dimColumns(table);
  const group = grouped && dims.length ? `GROUP BY ${dims.map((c) => c.name).join(', ')}` : '';
  const part = table.partition ? ` PARTITION (${table.partition} = '\${bizdate}')` : '';
  return [
    `INSERT OVERWRITE TABLE ${table.name}${part}`,
    `SELECT`,
    selects.join(',\n'),
    ...from.fromLines,
    from.where,
    group,
  ]
    .filter(Boolean)
    .join('\n') + ';';
}

export function cloneLogic(l?: FieldLogic): FieldLogic | undefined {
  if (!l) return undefined;
  return {
    ...l,
    sources: l.sources?.map((s) => ({ ...s })),
  };
}
