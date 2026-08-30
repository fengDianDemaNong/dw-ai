import { renderCreateTable } from './ddl';
import type { MaterializeRec, MeasureSpec, QueryCluster, QueryLog, WarehouseTable } from './types';

function jaccard(a: string[], b: string[]): number {
  const A = new Set(a);
  const B = new Set(b);
  let inter = 0;
  for (const x of A) if (B.has(x)) inter++;
  const union = A.size + B.size - inter;
  return union === 0 ? 1 : inter / union;
}

function measureKey(m: MeasureSpec): string {
  return `${m.agg}:${m.field}`;
}

function measureSim(a: MeasureSpec[], b: MeasureSpec[]): number {
  const A = new Set(a.map(measureKey));
  const B = new Set(b.map(measureKey));
  let inter = 0;
  for (const x of A) if (B.has(x)) inter++;
  const union = A.size + B.size - inter;
  return union === 0 ? 1 : inter / union;
}

export function fingerprint(log: Pick<QueryLog, 'dimensions' | 'measures' | 'datasource'>): string {
  const dims = [...log.dimensions].sort().join(',');
  const ms = log.measures.map(measureKey).sort().join(',');
  return `${log.datasource}|${dims}|${ms}`;
}

export function similarity(a: QueryLog, b: QueryLog): number {
  const dim = jaccard(a.dimensions, b.dimensions);
  const mea = measureSim(a.measures, b.measures);
  const sameSrc = a.datasource === b.datasource ? 1 : 0.4;
  return dim * 0.45 + mea * 0.4 + sameSrc * 0.15;
}

export function clusterLogs(logs: QueryLog[], threshold = 0.85): QueryCluster[] {
  const used = new Set<string>();
  const clusters: QueryCluster[] = [];
  for (const log of logs) {
    if (used.has(log.id)) continue;
    const members = [log];
    used.add(log.id);
    for (const other of logs) {
      if (used.has(other.id)) continue;
      if (similarity(log, other) >= threshold) {
        members.push(other);
        used.add(other.id);
      }
    }
    const dims = [...new Set(members.flatMap((m) => m.dimensions))];
    const measures = uniqueMeasures(members.flatMap((m) => m.measures));
    const avgMs = Math.round(members.reduce((s, m) => s + m.executionTimeMs, 0) / members.length);
    const monthlyCount = members.length * 300;
    const dimLabel = dims.filter((d) => d !== 'dt').join('_') || 'all';
    clusters.push({
      id: `cl-${members[0].id}`,
      projectId: log.projectId,
      queryIds: members.map((m) => m.id),
      dimensions: dims,
      measures,
      monthlyCount,
      avgMs,
      suggestion: `沉淀为 dws_trd_${measures[0]?.alias || 'sum'}_${dimLabel}_di`,
    });
  }
  return clusters.sort((a, b) => b.monthlyCount - a.monthlyCount);
}

function uniqueMeasures(list: MeasureSpec[]): MeasureSpec[] {
  const seen = new Set<string>();
  const out: MeasureSpec[] = [];
  for (const m of list) {
    const k = measureKey(m);
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(m);
  }
  return out;
}

function clamp(n: number, min = 0, max = 100): number {
  return Math.max(min, Math.min(max, n));
}

export function scoreCluster(
  cluster: QueryCluster,
  tables: WarehouseTable[]
): MaterializeRec {
  const frequency = clamp((cluster.monthlyCount / 1500) * 100);
  const compute = clamp((cluster.avgMs / 60000) * 80 + 20);
  const freshness = 72;
  const storage = 35;
  const reuse = clamp(cluster.queryIds.length * 22);
  const total = Number(
    (frequency * 0.3 + compute * 0.25 + freshness * 0.2 + (100 - storage) * 0.15 + reuse * 0.1).toFixed(1)
  );

  const existing = tables.filter((t) => t.layer === 'DWS');
  const cover = existing.find((t) => {
    const cols = new Set(t.columns.map((c) => c.name));
    const dimHit = cluster.dimensions.filter((d) => cols.has(d)).length / Math.max(cluster.dimensions.length, 1);
    return dimHit >= 0.8;
  });

  const action = cover ? 'extend' : total > 80 ? 'create' : 'create';
  const targetTable =
    action === 'extend' && cover
      ? cover.name
      : `dws_trd_${cluster.measures[0]?.alias || 'sum'}_${cluster.dimensions.filter((d) => d !== 'dt').join('_') || 'all'}_di`;

  const ddl = renderCreateTable(
    {
      name: targetTable,
      comment: '查询簇沉淀',
      columns: [
        ...cluster.dimensions.map((d) => ({ name: d, type: 'STRING', comment: d, nullable: false })),
        ...cluster.measures.map((m) => ({
          name: m.alias,
          type: m.agg.includes('SUM') ? 'DECIMAL(18,2)' : 'BIGINT',
          comment: m.alias,
          nullable: false,
        })),
      ],
      partition: 'dt',
      primaryKeys: cluster.dimensions,
    },
    'hive'
  );
  const group = cluster.dimensions.join(', ');
  const sel = [
    ...cluster.dimensions,
    ...cluster.measures.map((m) => `${m.agg}(${m.field}) AS ${m.alias}`),
  ].join(',\n    ');
  const src = 'dwd_trd_order_item_di';
  const precomputeSql = `INSERT OVERWRITE TABLE ${targetTable} PARTITION (dt = '\${bizdate}')\nSELECT\n    ${sel}\nFROM ${src}\nWHERE dt = '\${bizdate}'\nGROUP BY ${group};`;

  return {
    id: `rec-${cluster.id}`,
    projectId: cluster.projectId,
    clusterId: cluster.id,
    action,
    targetTable,
    ddl,
    precomputeSql,
    scores: {
      frequency: Number(frequency.toFixed(1)),
      compute: Number(compute.toFixed(1)),
      freshness,
      storage,
      reuse: Number(reuse.toFixed(1)),
      total,
    },
    status: 'pending',
    grayPercent: 0,
    roi: {
      savedCompute: `${Math.round(cluster.monthlyCount * cluster.avgMs * 0.85)} ms/月`,
      storageCost: action === 'extend' ? '增量列 < 2GB' : '约 18GB',
    },
  };
}

export function decide(rec: MaterializeRec): string {
  if (rec.action === 'extend') return '已有 DWS 能覆盖约 80% 需求，建议扩展现有表，不新建';
  if (rec.scores.total > 80) return '综合评分 > 80 且存储成本可接受，建议生成物化表';
  return '评分不足，继续观察查询簇';
}
