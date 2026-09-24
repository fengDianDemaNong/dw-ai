import { TREND_DAYS } from './api';
import type {
  DayCount,
  ProjectStats,
  TenantProjectRow,
  TenantStats,
} from './api';

/**
 * 概览页的开发期假数据。
 *
 * 后端 `/api/stats/**` 还没上线，但页面得先做出来、先能演示。这份数据的形状与
 * `docs/stats-api.md` 里的契约完全一致，接口一通就能整份删掉。
 *
 * <b>它进不了生产产物</b>：调用方是 `overview.vue` 里一个由
 * `import.meta.env.DEV && VITE_STATS_MOCK === 'true'` 守卫的 dead branch，
 * 里面用的是动态 import。生产构建时 `DEV` 被 Vite 替换成字面量 false，
 * 整个分支连同 import 一起被 Rollup 消除。
 *
 * 导出的是<b>函数而不是常量</b>：趋势的日期必须相对「今天」生成，写死日期的话
 * 过两周图就整段空了，而那时没人记得是假数据的锅。
 *
 * 每个函数都显式标注了返回类型，于是 `vue-tsc --noEmit` 会把这份假数据当成
 * 契约的一致性测试 —— 接口字段改名时这里立刻编译报错，而不是运行时才发现。
 */

/** 造一段有起伏的趋势：给定每天的次数，末尾对齐到今天。 */
function trendOf(counts: number[]): DayCount[] {
  const today = new Date();
  return counts.slice(-TREND_DAYS).map((count, i) => {
    const d = new Date(today);
    d.setDate(today.getDate() - (TREND_DAYS - 1 - i));
    // 用本地日期而不是 toISOString()：后者按 UTC 切，东八区的凌晨会退回前一天
    const day = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(
      2,
      '0'
    )}-${String(d.getDate()).padStart(2, '0')}`;
    return { day, count };
  });
}

/** 30 天里大半是 0，中间几波集中解析 —— 真实项目就是这个形状。 */
const PROJECT_TREND = [
  0, 0, 2, 5, 3, 0, 0, 0, 1, 7, 4, 0, 0, 0, 0, 2, 0, 0, 9, 6, 1, 0, 0, 3, 0, 0,
  0, 4, 8, 2,
];

const TENANT_TREND = [
  1, 0, 6, 12, 9, 2, 0, 3, 4, 15, 11, 0, 1, 0, 2, 7, 3, 0, 18, 14, 5, 0, 2, 8,
  1, 0, 3, 10, 16, 6,
];

export function projectStatsMock(): ProjectStats {
  return {
    scale: {
      lineageTables: 128,
      tempTables: 12,
      lineageColumns: 1893,
      currentEdges: 4210,
      versions: 356,
      metaTables: 140,
      metaColumns: 2201,
      dataCatalogs: 3,
      tempRules: 5,
    },
    quality: {
      lineageTablesNonTemp: 116,
      // 故意让「按全名」远低于「按库表」：这正是数据目录前缀没对齐的样子，
      // 也是这一屏最想让人看懂的一件事
      metaMatchedByFullName: 41,
      metaMatchedBySchemaTable: 109,
      metaTables: 140,
      metaInLineage: 41,
      lineageTables: 128,
      lineageTableCommentFilled: 60,
      metaTableCommentFilled: 132,
      metaColumns: 2201,
      metaColumnCommentFilled: 1804,
      isolatedTables: 9,
      metaOnlyTables: 99,
    },
    activity: {
      parses7d: 14,
      parses30d: 57,
      lastParseAt: '2026-08-23T18:04:11',
      trend: trendOf(PROJECT_TREND),
      recentParses: [
        {
          versionId: 981,
          tableId: 12,
          fullName: 'default.dwd.order_wide',
          versionNo: 4,
          dbType: 'hive',
          current: true,
          statTables: 5,
          statColumns: 42,
          statEdges: 118,
          createdAt: '2026-08-23T18:04:11',
        },
        {
          versionId: 977,
          tableId: 31,
          fullName: 'default.ads.gmv_daily',
          versionNo: 2,
          dbType: 'spark',
          current: true,
          statTables: 3,
          statColumns: 18,
          statEdges: 44,
          createdAt: '2026-08-23T11:20:03',
        },
        {
          versionId: 964,
          tableId: 12,
          fullName: 'default.dwd.order_wide',
          versionNo: 3,
          dbType: 'hive',
          current: false,
          statTables: 5,
          statColumns: 40,
          statEdges: 110,
          createdAt: '2026-08-21T09:41:55',
        },
        {
          versionId: 950,
          tableId: 7,
          fullName: 'default.ods.orders',
          versionNo: 1,
          dbType: 'mysql',
          current: true,
          statTables: 2,
          statColumns: 12,
          statEdges: 12,
          createdAt: '2026-08-19T16:02:30',
        },
      ],
    },
    distributions: {
      byDbType: [
        { name: 'hive', count: 210 },
        { name: 'spark', count: 96 },
        { name: 'mysql', count: 38 },
        { name: 'flink', count: 12 },
      ],
      byCatalog: [
        { name: 'default', count: 98 },
        { name: 'hive_prod', count: 24 },
        { name: 'tmp', count: 6 },
      ],
      byMetaSource: [
        { name: 'GRAVITINO', count: 120 },
        { name: 'DDL', count: 14 },
        { name: 'MANUAL', count: 6 },
      ],
      bySchema: [
        { name: 'ods', count: 54 },
        { name: 'dwd', count: 33 },
        { name: 'ads', count: 21 },
        { name: 'tmp', count: 12 },
        { name: null, count: 8 },
      ],
    },
    hubs: {
      topDownstream: [
        { tableId: 7, fullName: 'default.ods.orders', degree: 14 },
        { tableId: 9, fullName: 'default.ods.users', degree: 11 },
        { tableId: 21, fullName: 'default.dim.city', degree: 8 },
        { tableId: 15, fullName: 'default.ods.payments', degree: 5 },
        { tableId: 44, fullName: 'default.dim.channel', degree: 3 },
      ],
      topUpstream: [
        { tableId: 12, fullName: 'default.dwd.order_wide', degree: 9 },
        { tableId: 31, fullName: 'default.ads.gmv_daily', degree: 7 },
        { tableId: 33, fullName: 'default.ads.user_profile', degree: 6 },
        { tableId: 28, fullName: 'default.dwd.pay_detail', degree: 4 },
        { tableId: 52, fullName: 'default.ads.retention', degree: 2 },
      ],
    },
    sync: {
      days: 30,
      byStatus: [
        { name: 'SUCCESS', count: 8 },
        { name: 'PARTIAL', count: 1 },
        { name: 'FAILED', count: 2 },
      ],
      recentFailures: [
        {
          jobId: 31,
          status: 'FAILED',
          scope: 'SCHEMA',
          target: 'hive_prod.ods',
          failedCnt: 3,
          message: 'connection refused: gravitino:8090',
          finishedAt: '2026-08-20T09:12:00',
        },
        {
          jobId: 27,
          status: 'PARTIAL',
          scope: 'CATALOG',
          target: 'hive_prod',
          failedCnt: 12,
          message: '12 张表读取字段失败，其余已导入',
          finishedAt: '2026-08-14T22:31:40',
        },
      ],
    },
    lists: {
      metaOnlyTables: [
        { fullName: 'hive_prod.ods.legacy_orders', comment: '旧订单表' },
        { fullName: 'hive_prod.ods.legacy_users', comment: null },
        { fullName: 'hive_prod.dim.area_v1', comment: '已废弃的地区维表' },
      ],
      isolatedTables: [
        { id: 88, fullName: 'default.tmp.stage_x', comment: null },
        { id: 91, fullName: 'default.ods.unused_log', comment: '埋点日志' },
      ],
    },
  };
}

const TENANT_PROJECTS: TenantProjectRow[] = [
  {
    projectId: 1,
    code: 'default',
    name: '默认项目',
    enabled: true,
    lineageTables: 128,
    tempTables: 12,
    lineageColumns: 1893,
    currentEdges: 4210,
    versions: 356,
    metaTables: 140,
    metaColumns: 2201,
    parses30d: 57,
    lastParseAt: '2026-08-23T18:04:11',
  },
  {
    projectId: 2,
    code: 'growth',
    name: '增长分析',
    enabled: true,
    lineageTables: 240,
    tempTables: 20,
    lineageColumns: 4102,
    currentEdges: 12880,
    versions: 611,
    metaTables: 302,
    metaColumns: 5140,
    parses30d: 42,
    lastParseAt: '2026-08-22T14:09:00',
  },
  {
    projectId: 3,
    code: 'risk',
    name: '风控',
    enabled: true,
    lineageTables: 88,
    tempTables: 6,
    lineageColumns: 1402,
    currentEdges: 2610,
    versions: 190,
    metaTables: 96,
    metaColumns: 1330,
    parses30d: 11,
    lastParseAt: '2026-08-11T08:55:12',
  },
  // 空项目：建了但一条血缘都没有。对比表最该暴露的就是这一行
  {
    projectId: 4,
    code: 'sandbox',
    name: '沙箱',
    enabled: true,
    lineageTables: 0,
    tempTables: 0,
    lineageColumns: 0,
    currentEdges: 0,
    versions: 0,
    metaTables: 0,
    metaColumns: 0,
    parses30d: 0,
    lastParseAt: null,
  },
  {
    projectId: 5,
    code: 'archive',
    name: '归档',
    enabled: true,
    lineageTables: 0,
    tempTables: 0,
    lineageColumns: 0,
    currentEdges: 0,
    versions: 0,
    metaTables: 12,
    metaColumns: 88,
    parses30d: 0,
    lastParseAt: null,
  },
  {
    projectId: 6,
    code: 'legacy',
    name: '旧数仓（已停用）',
    enabled: false,
    lineageTables: 56,
    tempTables: 2,
    lineageColumns: 703,
    currentEdges: 611,
    versions: 47,
    metaTables: 50,
    metaColumns: 241,
    parses30d: 0,
    lastParseAt: '2026-03-02T10:00:00',
  },
];

export function tenantStatsMock(): TenantStats {
  const sum = (pick: (p: TenantProjectRow) => number) =>
    TENANT_PROJECTS.reduce((acc, p) => acc + pick(p), 0);

  return {
    overview: {
      projects: TENANT_PROJECTS.length,
      enabledProjects: TENANT_PROJECTS.filter((p) => p.enabled).length,
      disabledProjects: TENANT_PROJECTS.filter((p) => !p.enabled).length,
      emptyProjects: TENANT_PROJECTS.filter((p) => p.lineageTables === 0)
        .length,
    },
    scale: {
      lineageTables: sum((p) => p.lineageTables),
      tempTables: sum((p) => p.tempTables),
      lineageColumns: sum((p) => p.lineageColumns),
      currentEdges: sum((p) => p.currentEdges),
      versions: sum((p) => p.versions),
      metaTables: sum((p) => p.metaTables),
      metaColumns: sum((p) => p.metaColumns),
    },
    sources: {
      total: 4,
      enabled: 3,
      byType: [
        { name: 'GRAVITINO', count: 2, enabled: 2 },
        { name: 'DBX', count: 1, enabled: 1 },
        { name: 'CATALOG', count: 1, enabled: 0 },
      ],
    },
    activity: {
      parses7d: 31,
      parses30d: sum((p) => p.parses30d),
      lastParseAt: '2026-08-23T18:04:11',
      trend: trendOf(TENANT_TREND),
    },
    projects: TENANT_PROJECTS,
  };
}
