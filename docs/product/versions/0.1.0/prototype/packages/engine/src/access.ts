import type { DataGrade, ExportPolicy, LayerRule, QueryPolicy, WarehouseTable } from './types';
import { exportLabel, queryLabel } from './config/grades';

export type AccessDecision = 'allow' | 'approval' | 'forbid';
export type AccessAction = 'query' | 'export';

export interface AccessVerdict {
  decision: AccessDecision;
  reasons: string[];
  layerServe?: LayerRule['serve'];
  tableGrade?: DataGrade;
  fieldGrades: DataGrade[];
}

const QUERY_RANK: Record<QueryPolicy, number> = {
  allow: 0,
  login: 1,
  approval: 2,
  forbid: 3,
};

const EXPORT_RANK: Record<ExportPolicy, number> = {
  allow: 0,
  approval: 2,
  forbid: 3,
};

function stricterQuery(a: QueryPolicy, b: QueryPolicy): QueryPolicy {
  return QUERY_RANK[a] >= QUERY_RANK[b] ? a : b;
}

function stricterExport(a: ExportPolicy, b: ExportPolicy): ExportPolicy {
  return EXPORT_RANK[a] >= EXPORT_RANK[b] ? a : b;
}

function serveToQuery(serve: LayerRule['serve']): QueryPolicy {
  if (serve === 'forbid') return 'forbid';
  if (serve === 'approval') return 'approval';
  return 'allow';
}

function serveToExport(serve: LayerRule['serve']): ExportPolicy {
  if (serve === 'forbid') return 'forbid';
  if (serve === 'approval') return 'approval';
  return 'allow';
}

export function findGrade(code: string | undefined, grades: DataGrade[]): DataGrade | undefined {
  if (!code) return undefined;
  return grades.find((g) => g.code === code);
}

export function effectiveColumnGrade(
  table: WarehouseTable,
  colName: string,
  grades: DataGrade[]
): DataGrade | undefined {
  const col = table.columns.find((c) => c.name === colName);
  return findGrade(col?.grade || table.grade, grades);
}

export function evaluateAccess(opts: {
  table?: WarehouseTable;
  layerRule?: LayerRule;
  grades: DataGrade[];
  fields?: string[];
  action: AccessAction;
}): AccessVerdict {
  const { table, layerRule, grades, fields, action } = opts;
  const reasons: string[] = [];
  let query: QueryPolicy = 'allow';
  let exp: ExportPolicy = 'allow';
  const fieldGrades: DataGrade[] = [];

  if (layerRule) {
    query = stricterQuery(query, serveToQuery(layerRule.serve));
    exp = stricterExport(exp, serveToExport(layerRule.serve));
    if (layerRule.serve === 'forbid') {
      reasons.push(`分层 ${layerRule.layer} 对外服务为禁止`);
    } else if (layerRule.serve === 'approval') {
      reasons.push(`分层 ${layerRule.layer} 对外需审批`);
    }
  }

  const tableGrade = findGrade(table?.grade, grades);
  if (tableGrade) {
    query = stricterQuery(query, tableGrade.query);
    exp = stricterExport(exp, tableGrade.export);
    reasons.push(`表等级 ${tableGrade.code} ${tableGrade.name}：查询${queryLabel(tableGrade.query)}，导出${exportLabel(tableGrade.export)}`);
  }

  const names = fields?.length ? fields : table?.columns.map((c) => c.name) ?? [];
  for (const name of names) {
    if (!table) break;
    const g = effectiveColumnGrade(table, name, grades);
    if (!g || g.code === tableGrade?.code) continue;
    fieldGrades.push(g);
    query = stricterQuery(query, g.query);
    exp = stricterExport(exp, g.export);
    reasons.push(`字段 ${name} 为 ${g.code} ${g.name}：${action === 'export' ? exportLabel(g.export) : queryLabel(g.query)}`);
  }

  const policy = action === 'export' ? exp : query === 'login' ? 'allow' : query;
  const decision: AccessDecision =
    action === 'export'
      ? exp === 'allow'
        ? 'allow'
        : exp === 'approval'
          ? 'approval'
          : 'forbid'
      : query === 'forbid'
        ? 'forbid'
        : query === 'approval'
          ? 'approval'
          : 'allow';

  if (!reasons.length && policy === 'allow') {
    reasons.push('当前分层与等级允许直接操作');
  }

  return {
    decision,
    reasons,
    layerServe: layerRule?.serve,
    tableGrade,
    fieldGrades,
  };
}

export function decisionLabel(d: AccessDecision) {
  if (d === 'allow') return '可直接查询';
  if (d === 'approval') return '需审批后查询';
  return '禁止查询';
}

export function decisionColor(d: AccessDecision) {
  if (d === 'allow') return 'green';
  if (d === 'approval') return 'orange';
  return 'red';
}
