import request from '../utils/request';

/** 方言及其能力。ddlMetadataSupported 为 false 时必须依赖外部元数据服务。 */
export interface DialectInfo {
  type: string;
  columnLevelLineage: boolean;
  ddlMetadataSupported: boolean;
  note: string;
}

/** 解析失败的单条语句。 */
export interface FailedStatement {
  index: number;
  sql: string;
  message: string;
}

/** 血缘接口响应。 */
export interface LineageResponse {
  code: number;
  data: any;
  warnings: string[];
  failedStatements: FailedStatement[];
  unresolvedTables: string[];
  message?: string;
  traceId?: string;
}

/** 解析时可选的外部元数据来源。sourceId 来自「设置 › 元数据服务」页面上的配置。 */
export interface LineageSourceOption {
  sourceId?: number;
  /** 仅 Gravitino 来源需要 */
  metalake?: string;
  catalog?: string;
}

export async function getLineageData(
  dbType: string,
  querySql: string,
  columnName?: string,
  isCreateTable: boolean = false,
  source?: LineageSourceOption,
  includeTemp: boolean = false
): Promise<LineageResponse> {
  return request('/api/lineage/analyze', {
    method: 'POST',
    data: {
      dbType,
      querySql,
      columnName,
      isCreateTable,
      sourceId: source?.sourceId,
      metalake: source?.metalake,
      catalog: source?.catalog,
      // 默认不含临时表：所见即所存。保存接口一律过滤，不受这个开关影响
      includeTemp,
    },
  });
}

/** 支持的方言名称列表（保留以兼容旧调用）。 */
export async function getDbTypes(): Promise<string[]> {
  return request('/api/dbType', { method: 'GET' });
}

/** 支持的方言及其能力，用于提示「该方言需要外部元数据服务」。 */
export async function getDialects(): Promise<DialectInfo[]> {
  return request('/api/dialects', { method: 'GET' });
}

/**
 * 表级血缘。不需要任何元数据，速度快，
 * 可作为列级血缘因元数据缺失而失败时的降级方案。
 */
export async function getTableLineage(dbType: string, querySql: string) {
  return request('/api/lineage/table', {
    method: 'POST',
    data: { dbType, querySql },
  });
}

/** SQL 语法校验，返回带行列号的错误，供编辑器标注。 */
export async function validateSql(dbType: string, querySql: string) {
  return request('/api/sql/validate', {
    method: 'POST',
    data: { dbType, querySql },
  });
}

/** 指定方言的关键字，供编辑器补全。 */
export async function getSqlKeywords(dbType: string): Promise<string[]> {
  return request(`/api/sql/keywords?dbType=${encodeURIComponent(dbType)}`, {
    method: 'GET',
  });
}

// ---------------- 外部元数据（Gravitino） ----------------
//
// 这几个接口都接受可选的 sourceId，指向「设置 › 元数据服务」页面上配置的某条 Gravitino 服务。
// 不传时后端退回 application.yml 里的 gravitino.url。

export async function getMatelakes(sourceId?: number): Promise<string[]> {
  const query = sourceId ? `?sourceId=${sourceId}` : '';
  return request(`/api/mate/matelakes${query}`, { method: 'GET' });
}

export async function getCatalogs(
  mateLake: string,
  sourceId?: number
): Promise<string[]> {
  const params: Record<string, string> = { mateLake };
  if (sourceId) {
    params.sourceId = String(sourceId);
  }
  return request(
    `/api/mate/catalogs?${new URLSearchParams(params).toString()}`,
    { method: 'GET' }
  );
}

export async function getSchemas(
  mateLake: string,
  catalog: string,
  sourceId?: number
): Promise<string[]> {
  return request('/api/mate/schemas', {
    method: 'POST',
    data: {
      mateLake,
      catalog,
      sourceId: sourceId ? String(sourceId) : undefined,
    },
  });
}

export async function getLineageDataFromMate(
  mateLake: string,
  catalog: string,
  querySql: string,
  columnName?: string,
  sourceId?: number
): Promise<LineageResponse> {
  return request('/api/mate/lineage/analyze', {
    method: 'POST',
    data: {
      mateLake,
      catalog,
      querySql,
      columnName,
      sourceId: sourceId ? String(sourceId) : undefined,
    },
  });
}

// ---------------- 元数据服务配置 ----------------

/** CATALOG 是内置的「本地元数据目录」：不走网络、没有地址与凭据、不可删除。 */
export type MetadataSourceType = 'GRAVITINO' | 'DBX' | 'CATALOG';

/** 元数据服务配置。注意没有凭据明文字段，后端从不回传。 */
export interface MetadataSource {
  id: number;
  name: string;
  type: MetadataSourceType;
  baseUrl: string;
  /** 是否已配置凭据。页面只能知道「有没有」，看不到内容 */
  credentialConfigured: boolean;
  extraConfig?: string;
  priority: number;
  enabled: boolean;
  /** 适用范围提示，由后端给出，直接显示给用户 */
  applicableScope: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface MetadataSourcePayload {
  name: string;
  type: MetadataSourceType;
  baseUrl: string;
  /** 留空表示不修改已保存的凭据 */
  credential?: string;
  extraConfig?: string;
  priority?: number;
  enabled?: boolean;
}

export interface MetadataSourceTestResult {
  success: boolean;
  message: string;
}

export interface MetadataCapabilities {
  /** 后端是否配置了 METADATA_SECRET_KEY，未配置时保存带凭据的服务会失败 */
  credentialEncryptionAvailable: boolean;
}

export async function listMetadataSources(): Promise<MetadataSource[]> {
  return request('/api/metadata-sources', { method: 'GET' });
}

export async function getMetadataCapabilities(): Promise<MetadataCapabilities> {
  return request('/api/metadata-sources/capabilities', { method: 'GET' });
}

export async function createMetadataSource(
  payload: MetadataSourcePayload
): Promise<MetadataSource> {
  return request('/api/metadata-sources', { method: 'POST', data: payload });
}

export async function updateMetadataSource(
  id: number,
  payload: MetadataSourcePayload
): Promise<MetadataSource> {
  return request(`/api/metadata-sources/${id}`, {
    method: 'PUT',
    data: payload,
  });
}

export async function deleteMetadataSource(id: number): Promise<void> {
  return request(`/api/metadata-sources/${id}`, { method: 'DELETE' });
}

/**
 * 测试连接。
 *
 * 传 id 用库里已保存的凭据；传表单字段则测试尚未保存的配置。
 * 连不上会返回 success=false 而不是抛错，所以调用方不要只 catch。
 */
export async function testMetadataSource(payload: {
  id?: number;
  type?: MetadataSourceType;
  baseUrl?: string;
  credential?: string;
  extraConfig?: string;
}): Promise<MetadataSourceTestResult> {
  return request('/api/metadata-sources/test', {
    method: 'POST',
    data: payload,
  });
}

// ---------------- 元数据目录 ----------------
//
// 与血缘数据分开存储：这里是「数据库里真实存在的表结构」，供 SQL 解析时查用；
// 血缘是从 SQL 推导出来的，可能含临时表与推断列。混在一起会让一次错误推断污染元数据。

/** 一行元数据是怎么来的。MANUAL 的在同步时受保护，默认不被覆盖。 */
export type MetaSource = 'DDL' | 'GRAVITINO' | 'DBX' | 'MANUAL';

export interface MetaTable {
  id: number;
  /** 数据目录；不属于任何目录时为 null */
  catalogName?: string;
  schemaName: string;
  tableName: string;
  fullName: string;
  tableType?: string;
  /** 中文名 */
  comment?: string;
  remark?: string;
  dbType?: string;
  source: MetaSource;
  sourceId?: number;
  /** 最近一次从外部同步的时间；DDL 导入与手工录入为 null */
  syncedAt?: string;
  updatedAt?: string;
}

export interface MetaColumn {
  id: number;
  tableId: number;
  columnName: string;
  dataType?: string;
  comment?: string;
  remark?: string;
  ordinal: number;
  partition: boolean;
  nullable: boolean;
  primary: boolean;
  source: MetaSource;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

/** 导入与同步共用的结果结构，前端两处可以共用一个展示组件。 */
export interface MetaWriteResult {
  created: number;
  updated: number;
  skipped: number;
  failed: number;
  /** 因人工维护而被跳过的表 */
  skippedTables: string[];
  failures: { table: string; message: string }[];
}

export async function listMetaSchemas(): Promise<string[]> {
  return request('/api/meta/schemas', { method: 'GET' });
}

export async function listMetaTables(params: {
  catalog?: string;
  schema?: string;
  keyword?: string;
  page?: number;
  size?: number;
}): Promise<PageResult<MetaTable>> {
  const query = new URLSearchParams();
  if (params.catalog) query.set('catalog', params.catalog);
  if (params.schema) query.set('schema', params.schema);
  if (params.keyword) query.set('keyword', params.keyword);
  query.set('page', String(params.page ?? 1));
  query.set('size', String(params.size ?? 20));
  return request(`/api/meta/tables?${query.toString()}`, { method: 'GET' });
}

export async function getMetaTable(id: number): Promise<MetaTable> {
  return request(`/api/meta/tables/${id}`, { method: 'GET' });
}

export async function listMetaColumns(tableId: number): Promise<MetaColumn[]> {
  return request(`/api/meta/tables/${tableId}/columns`, { method: 'GET' });
}

/** 一、贴建表语句导入。非建表语句会被忽略，可以直接把整个脚本粘进来。 */
export async function importMetaDdl(payload: {
  dbType: string;
  ddl: string;
  /** 导入到哪个数据目录；不传用默认目录。建表语句自己写了三段名时以语句为准 */
  catalogName?: string;
  overwriteManual?: boolean;
}): Promise<MetaWriteResult> {
  return request('/api/meta/ddl', { method: 'POST', data: payload });
}

/**
 * 二、逐级浏览外部源。
 *
 * Gravitino 是 metalake → catalog → schema → table，dbx 是 database → schema → table。
 * 参数填到哪一级，就返回下一级的列表。
 */
export async function browseMetaSource(params: {
  sourceId: number;
  metalake?: string;
  catalog?: string;
  database?: string;
  schema?: string;
  /** dbx 专用：用户在弹窗里现选的连接。不传则回落到来源配置里的默认值 */
  connectionId?: string;
}): Promise<string[]> {
  const query = new URLSearchParams({ sourceId: String(params.sourceId) });
  if (params.connectionId) query.set('connectionId', params.connectionId);
  if (params.metalake) query.set('metalake', params.metalake);
  if (params.catalog) query.set('catalog', params.catalog);
  if (params.database) query.set('database', params.database);
  if (params.schema) query.set('schema', params.schema);
  return request(`/api/meta/sync/browse?${query.toString()}`, {
    method: 'GET',
  });
}

/** 远程一张表的字段结构（只读）。 */
export interface RemoteTableDetail {
  name: string;
  comment?: string | null;
  columns: RemoteColumn[];
}

export interface RemoteColumn {
  /** 从 1 开始，前端拿它当 row-key */
  ordinal: number;
  name: string;
  dataType?: string | null;
  comment?: string | null;
  nullable: boolean;
  /** dbx 恒为 false —— 它给不出分区信息 */
  partition: boolean;
  /** Gravitino 恒为 false —— 它的列模型里没有主键概念 */
  primaryKey: boolean;
}

/**
 * 看远程某张表的字段，不落库。
 *
 * <p>与导入共用同一份字段映射，所以这里看到的就是导进来的样子。
 */
export async function getRemoteTable(params: {
  sourceId: number;
  table: string;
  metalake?: string;
  catalog?: string;
  database?: string;
  schema?: string;
  connectionId?: string;
}): Promise<RemoteTableDetail> {
  const query = new URLSearchParams({
    sourceId: String(params.sourceId),
    table: params.table,
  });
  if (params.connectionId) query.set('connectionId', params.connectionId);
  if (params.metalake) query.set('metalake', params.metalake);
  if (params.catalog) query.set('catalog', params.catalog);
  if (params.database) query.set('database', params.database);
  if (params.schema) query.set('schema', params.schema);
  return request(`/api/meta/sync/table?${query.toString()}`, { method: 'GET' });
}

/** 二（续）、把勾选的表同步进目录。 */
export async function syncMetaTables(payload: {
  sourceId: number;
  metalake?: string;
  catalog?: string;
  database?: string;
  schema?: string;
  tables: string[];
  overwriteManual?: boolean;
}): Promise<MetaWriteResult> {
  return request('/api/meta/sync', { method: 'POST', data: payload });
}

/** 三、手工修改。改完该行 source 会变成 MANUAL，后续同步不再覆盖它。 */
export async function updateMetaTable(
  id: number,
  payload: { tableType?: string; comment?: string; remark?: string }
): Promise<MetaTable> {
  return request(`/api/meta/tables/${id}`, { method: 'PUT', data: payload });
}

export async function updateMetaColumn(
  id: number,
  payload: { dataType?: string; comment?: string; remark?: string }
): Promise<MetaColumn> {
  return request(`/api/meta/columns/${id}`, { method: 'PUT', data: payload });
}

/** 删除元数据表不会影响已保存的血缘 —— 两者隔离存储。 */
export async function deleteMetaTable(id: number): Promise<void> {
  return request(`/api/meta/tables/${id}`, { method: 'DELETE' });
}

export async function deleteMetaColumn(id: number): Promise<void> {
  return request(`/api/meta/columns/${id}`, { method: 'DELETE' });
}

// ---------------- 血缘目录（数据表列表 / 表详情 / 搜索） ----------------
//
// 读的是已保存的血缘；中文名等描述属性由后端按 full_name 从元数据侧关联带出，
// 关联不上就是空（血缘里可能有临时表，元数据目录里本来就不该有它们）。

export interface CatalogTable {
  id: number;
  /** 数据目录；不属于任何目录时为 null */
  catalogName?: string;
  schemaName: string;
  tableName: string;
  fullName: string;
  dbType?: string;
  /** 元数据侧的表 id；为 null 表示元数据目录里还没有这张表 */
  metaTableId?: number;
  tableType?: string;
  comment?: string;
  remark?: string;
}

export interface CatalogColumn {
  id: number;
  tableId: number;
  columnName: string;
  fullName: string;
  ordinal: number;
  partition: boolean;
  metaColumnId?: number;
  dataType?: string;
  comment?: string;
  remark?: string;
}

/** 搜索命中：命中表信息时 columnName 为空，命中字段时才有。 */
export interface CatalogSearchHit {
  tableId: number;
  catalogName?: string;
  schemaName: string;
  tableName: string;
  fullName: string;
  tableType?: string;
  tableComment?: string;
  remark?: string;
  columnId?: number;
  columnName?: string;
  columnComment?: string;
}

export interface LineageVersion {
  id: number;
  targetTableId: number;
  versionNo: number;
  dbType: string;
  sqlHash: string;
  current: boolean;
  statTables: number;
  statColumns: number;
  statEdges: number;
  createdAt?: string;
}

export interface LineageSaveResult {
  tables: number;
  columns: number;
  edges: number;
  versions: {
    versionId: number;
    targetTableId: number;
    targetTable: string;
    versionNo: number;
    edges: number;
  }[];
}

export async function listCatalogSchemas(): Promise<string[]> {
  return request('/api/catalog/schemas', { method: 'GET' });
}

export async function listCatalogTables(
  schema?: string,
  catalog?: string
): Promise<CatalogTable[]> {
  const query = new URLSearchParams();
  if (schema) query.set('schema', schema);
  if (catalog) query.set('catalog', catalog);
  const qs = query.toString();
  return request(`/api/catalog/tables${qs ? '?' + qs : ''}`, { method: 'GET' });
}

export async function getCatalogTable(id: number): Promise<CatalogTable> {
  return request(`/api/catalog/tables/${id}`, { method: 'GET' });
}

export async function listCatalogColumns(
  tableId: number
): Promise<CatalogColumn[]> {
  return request(`/api/catalog/tables/${tableId}/columns`, { method: 'GET' });
}

/** 直接上游表，由列级边聚合投影而来。 */
export async function listUpstreamTables(
  tableId: number
): Promise<CatalogTable[]> {
  return request(`/api/catalog/tables/${tableId}/upstream`, { method: 'GET' });
}

/** 被哪些下游表的哪些字段引用。删表前的保护检查。 */
export async function listTableReferences(
  tableId: number
): Promise<CatalogSearchHit[]> {
  return request(`/api/catalog/tables/${tableId}/referenced`, {
    method: 'GET',
  });
}

/** 字段的直接上游，供表格展开行懒加载。 */
export async function listUpstreamColumns(
  columnId: number
): Promise<CatalogColumn[]> {
  return request(`/api/catalog/columns/${columnId}/upstream`, {
    method: 'GET',
  });
}

export async function listDownstreamColumns(
  columnId: number
): Promise<CatalogColumn[]> {
  return request(`/api/catalog/columns/${columnId}/downstream`, {
    method: 'GET',
  });
}

/** 全局搜索。keyword 用空格分隔多个条件，词间 AND。 */
export async function searchCatalog(
  keyword: string,
  tableType?: string
): Promise<CatalogSearchHit[]> {
  const query = new URLSearchParams({ keyword });
  if (tableType) query.set('tableType', tableType);
  return request(`/api/catalog/search?${query.toString()}`, { method: 'GET' });
}

/** 概览首页的统计。 */
export interface CatalogStats {
  tables: number;
  columns: number;
  /** 当前版本的字段级边数，不含历史版本 */
  edges: number;
  versions: number;
  metaTables: number;
  recentParses: {
    versionId: number;
    tableId: number;
    fullName: string;
    versionNo: number;
    dbType: string;
    current: boolean;
    statTables: number;
    statColumns: number;
    statEdges: number;
    createdAt?: string;
  }[];
  /** 元数据里有结构、血缘里从没出现过的表。这些表在血缘侧没有 id */
  metaOnlyTables: { fullName: string; comment?: string }[];
  /** 血缘里有、但当前版本下既无上游也无下游的孤立表 */
  isolatedTables: { id: number; fullName: string; comment?: string }[];
}

/**
 * 概览统计，一次拿全。
 *
 * 不要改成前端拼：字段数、边数、最近解析在别的接口里都只能按表 id 逐个查，
 * 表一多就是几百次请求。
 *
 * <b>已无调用方</b>：概览页改版后走下面的 `/api/stats/**`。这里留着只是因为后端
 * 接口还在，等后端把它删掉时这一段跟着删。
 */
export async function getCatalogStats(): Promise<CatalogStats> {
  return request('/api/catalog/stats', { method: 'GET' });
}

// ---------------------------------------------------------------
// 概览统计（项目级 / 租户级两个口径）
//
// 口径边界由数据库的隔离层级决定，不是拍脑袋分的：
//   租户级（只有 tenant_id）：project、metadata_source
//   项目级（tenant_id + project_id）：血缘四表、元数据两表、data_catalog、
//                                    temp_rule、sync_job
// 所以「元数据服务」只出现在租户口径里 —— 同一租户下所有项目共用一份连接配置。
//
// 完整契约见 docs/stats-api.md，那份文档里的示例 JSON 就是这里这些接口的定义。
// ---------------------------------------------------------------

/** 与后端 TREND_DAYS 对齐。趋势数组恒为这么长，服务端补零。 */
export const TREND_DAYS = 30;

/** 与后端 OVERVIEW_LIST_LIMIT 对齐。两张清单取满这么多条就说明多半还有。 */
export const STATS_LIST_LIMIT = 20;

/**
 * 分布 / 计数条的一行。
 *
 * `name` 可能为 null（例如没有库名的表），渲染侧兜底成「(未指定)」，不要在这里补。
 */
export interface StatCount {
  name: string | null;
  count: number;
}

/** 趋势的一个桶。后端保证定长 TREND_DAYS 条、旧→新、无解析的那天补 0。 */
export interface DayCount {
  /** LocalDate，形如 2026-08-14 */
  day: string;
  count: number;
}

export interface ProjectScale {
  /** 含临时表 */
  lineageTables: number;
  tempTables: number;
  lineageColumns: number;
  /** 只含当前版本；历史版本的边不计入 */
  currentEdges: number;
  versions: number;
  metaTables: number;
  metaColumns: number;
  dataCatalogs: number;
  /** 含已停用的规则 */
  tempRules: number;
}

/**
 * 覆盖与质量。
 *
 * 每条比率的分子分母都在这里成对出现，分母与 `ProjectScale` 有重复是刻意的 ——
 * 前端不跨块取分母，免得哪天 scale 的口径变了把比率也带歪。
 *
 * 后端不算百分比：分母为 0 时 0/0 只能瞎编（0% 和 100% 都是错的），
 * 而渲染侧能干净地显示「—」。
 */
export interface ProjectQuality {
  /** 两条覆盖率的分母：非临时的血缘表 */
  lineageTablesNonTemp: number;
  /** 按三段全名严格匹配上元数据的血缘表数 */
  metaMatchedByFullName: number;
  /** 只按 库.表 匹配、忽略数据目录那一段的血缘表数。恒 >= 上面那个 */
  metaMatchedBySchemaTable: number;
  metaTables: number;
  /** 元数据表里已经出现在血缘中的张数 */
  metaInLineage: number;
  lineageTables: number;
  lineageTableCommentFilled: number;
  metaTableCommentFilled: number;
  metaColumns: number;
  metaColumnCommentFilled: number;
  /** 计数；清单在 lists 里 */
  isolatedTables: number;
  metaOnlyTables: number;
}

/** 一次解析留下的版本。字段与旧 CatalogStats.recentParses 完全一致。 */
export interface RecentParse {
  versionId: number;
  tableId: number;
  fullName: string;
  versionNo: number;
  dbType: string;
  current: boolean;
  statTables: number;
  statColumns: number;
  statEdges: number;
  createdAt?: string;
}

export interface ProjectActivity {
  parses7d: number;
  parses30d: number;
  /** 从未解析过时为 null */
  lastParseAt: string | null;
  trend: DayCount[];
  recentParses: RecentParse[];
}

export interface ProjectDistributions {
  /** 取 lineage_version.db_type —— 「用什么方言解析的」才是有意义的问题 */
  byDbType: StatCount[];
  byCatalog: StatCount[];
  /** DDL / GRAVITINO / DBX / MANUAL */
  byMetaSource: StatCount[];
  bySchema: StatCount[];
}

/** 枢纽表。degree 数的是<b>不同的上/下游表数</b>，不是边数。 */
export interface HubTable {
  tableId: number;
  fullName: string;
  degree: number;
}

export interface SyncFailure {
  jobId: number;
  status: string;
  scope: string;
  /** 后端拼好的展示串，前端不再拼 */
  target: string;
  failedCnt: number;
  message: string | null;
  finishedAt: string | null;
}

export interface ProjectSyncStats {
  days: number;
  /** 只列出现过的状态，前端自己补齐三种 */
  byStatus: StatCount[];
  recentFailures: SyncFailure[];
}

/** 清单里只需要认出是哪张表并能点进去。 */
export interface StatTableBrief {
  /** 元数据独有的表在血缘侧不存在，故没有 id */
  id?: number;
  fullName: string;
  comment?: string | null;
}

export interface ProjectStats {
  scale: ProjectScale;
  quality: ProjectQuality;
  activity: ProjectActivity;
  distributions: ProjectDistributions;
  hubs: {
    topDownstream: HubTable[];
    topUpstream: HubTable[];
  };
  sync: ProjectSyncStats;
  lists: {
    metaOnlyTables: StatTableBrief[];
    isolatedTables: StatTableBrief[];
  };
}

/** 租户下一个项目的横向对比行。空项目也在列，各项为 0。 */
export interface TenantProjectRow {
  projectId: number;
  code: string;
  name: string;
  enabled: boolean;
  lineageTables: number;
  tempTables: number;
  lineageColumns: number;
  currentEdges: number;
  versions: number;
  metaTables: number;
  metaColumns: number;
  parses30d: number;
  lastParseAt: string | null;
}

/** 元数据服务按类型的分布。这是租户级配置，项目口径里没有它。 */
export interface SourceTypeCount {
  name: string;
  count: number;
  enabled: number;
}

export interface TenantStats {
  overview: {
    projects: number;
    enabledProjects: number;
    disabledProjects: number;
    /** 一条血缘表都没有的项目数 */
    emptyProjects: number;
  };
  /** 全租户汇总，由 projects[] 求和得出 */
  scale: {
    lineageTables: number;
    tempTables: number;
    lineageColumns: number;
    currentEdges: number;
    versions: number;
    metaTables: number;
    metaColumns: number;
  };
  sources: {
    total: number;
    enabled: number;
    byType: SourceTypeCount[];
  };
  activity: {
    parses7d: number;
    parses30d: number;
    lastParseAt: string | null;
    trend: DayCount[];
  };
  projects: TenantProjectRow[];
}

/**
 * 当前项目口径的统计。
 *
 * 上下文由 request 拦截器注入的租户头决定，无需传参 —— 也正因如此，
 * 切换项目后整页重载就足以让这里拿到新数据。
 */
export async function getProjectStats(): Promise<ProjectStats> {
  return request('/api/stats/project', { method: 'GET' });
}

/** 本租户口径。后端忽略 X-Project-Id，统计该租户下的全部项目。 */
export async function getTenantStats(): Promise<TenantStats> {
  return request('/api/stats/tenant', { method: 'GET' });
}

/**
 * 已保存的血缘图。
 *
 * @param start 表名（整表）或字段全名（单字段）
 */
export async function getSavedLineageGraph(params: {
  start: string;
  direction: 'up' | 'down';
  depth?: number;
  versionId?: number;
}): Promise<LineageResponse> {
  const query = new URLSearchParams({
    start: params.start,
    direction: params.direction,
  });
  if (params.depth && params.depth > 0)
    query.set('depth', String(params.depth));
  if (params.versionId) query.set('versionId', String(params.versionId));
  return request(`/api/lineage/graph?${query.toString()}`, { method: 'GET' });
}

export async function listLineageVersions(
  tableId: number
): Promise<LineageVersion[]> {
  return request(`/api/catalog/tables/${tableId}/versions`, { method: 'GET' });
}

export async function markVersionCurrent(versionId: number): Promise<void> {
  return request(`/api/lineage/versions/${versionId}/current`, {
    method: 'PUT',
  });
}

export async function deleteLineageVersion(versionId: number): Promise<void> {
  return request(`/api/lineage/versions/${versionId}`, { method: 'DELETE' });
}

/**
 * 解析 SQL 并保存血缘。
 *
 * 后端会拿 SQL 重新解析一次再存，而不是收前端已有的图 —— 那个图经过列过滤等
 * 展示层处理，存进去会是残缺的。
 */
export async function saveLineage(payload: {
  dbType: string;
  querySql: string;
  isCreateTable?: boolean;
  sourceId?: number;
  metalake?: string;
  catalog?: string;
}): Promise<LineageSaveResult> {
  return request('/api/lineage/save', { method: 'POST', data: payload });
}

// ---------------------------------------------------------------
// 租户与项目
//
// 这几个接口是跨租户的管理面，作用对象由路径参数指定，而不是由
// utils/request.ts 注入的 X-Tenant-Id 头指定 —— 头对它们不起作用。
// 唯一的例外是 getActiveContext，它的职责恰恰是回报那个头解析出了什么。
// ---------------------------------------------------------------

export interface Project {
  id: number;
  tenantId: number;
  code: string;
  name: string;
  description?: string;
  status: number;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface Tenant {
  id: number;
  code: string;
  name: string;
  status: number;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  /** 内嵌项目列表，切换器一次请求即可拿到两级数据 */
  projects: Project[];
}

/** 当前请求实际生效的租户上下文。 */
export interface ActiveContext {
  tenantId: number;
  projectId: number;
  tenantName?: string;
  projectName?: string;
  /** false 表示本地存的租户在后端已不存在，前端应回落到默认租户 */
  tenantExists: boolean;
  projectExists: boolean;
  tenantEnabled: boolean;
  projectEnabled: boolean;
}

export async function getActiveContext(): Promise<ActiveContext> {
  return request('/api/context', { method: 'GET' });
}

export async function listTenants(): Promise<Tenant[]> {
  return request('/api/tenants', { method: 'GET' });
}

export async function createTenant(payload: {
  code: string;
  name: string;
}): Promise<Tenant> {
  return request('/api/tenants', { method: 'POST', data: payload });
}

export async function updateTenant(
  id: number,
  payload: { code: string; name: string; status?: number }
): Promise<Tenant> {
  return request(`/api/tenants/${id}`, { method: 'PUT', data: payload });
}

/** 其下还有业务数据时后端返回 409，错误消息里带着行数与替代方案。 */
export async function deleteTenant(id: number): Promise<void> {
  return request(`/api/tenants/${id}`, { method: 'DELETE' });
}

export async function listProjects(tenantId: number): Promise<Project[]> {
  return request(`/api/tenants/${tenantId}/projects`, { method: 'GET' });
}

export async function createProject(
  tenantId: number,
  payload: { code: string; name: string; description?: string }
): Promise<Project> {
  return request(`/api/tenants/${tenantId}/projects`, {
    method: 'POST',
    data: payload,
  });
}

export async function updateProject(
  tenantId: number,
  id: number,
  payload: { code: string; name: string; description?: string; status?: number }
): Promise<Project> {
  return request(`/api/tenants/${tenantId}/projects/${id}`, {
    method: 'PUT',
    data: payload,
  });
}

export async function deleteProject(
  tenantId: number,
  id: number
): Promise<void> {
  return request(`/api/tenants/${tenantId}/projects/${id}`, {
    method: 'DELETE',
  });
}

// ---------------------------------------------------------------
// 数据目录（catalog）
//
// catalog 参数两态：留空（undefined / ''）= 全部目录，'xxx' = 该目录下的表。
//
// 1.0.4 之前空串另有含义「只看不属于任何数据目录的表」。现在 catalog_name 是
// NOT NULL、表全名恒为三段，那种表不存在了，这一态一并取消。
// ---------------------------------------------------------------

/** 血缘侧出现过的数据目录。 */
export async function listCatalogCatalogs(): Promise<string[]> {
  return request('/api/catalog/catalogs', { method: 'GET' });
}

/** 元数据侧出现过的数据目录。 */
export async function listMetaCatalogs(): Promise<string[]> {
  return request('/api/meta/catalogs', { method: 'GET' });
}

// ---------------------------------------------------------------
// 元数据导入（异步任务）
//
// 提交后立刻拿到 jobId，导入在后台跑 —— 按数据目录导入可能是上万张表。
// 前端轮询 getMetaSyncJob 显示进度，finished 为 true 时停止。
// ---------------------------------------------------------------

/** dbx 中已保存的连接。脱敏摘要，不含密码。 */
export interface DbxConnection {
  id: string;
  name: string;
  dbType: string;
  database?: string;
}

export interface SyncJob {
  id: number;
  sourceId: number;
  scope: string;
  sourceCatalog?: string;
  sourceDatabase?: string;
  sourceSchema?: string;
  targetCatalog?: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';
  /** 已结束，前端据此停止轮询 */
  finished: boolean;
  /** 展开完成前为 0 —— 此时还不知道要导多少张 */
  total: number;
  done: number;
  percent: number;
  createdCnt: number;
  updatedCnt: number;
  skippedCnt: number;
  failedCnt: number;
  message?: string;
  failures?: string[];
  startedAt?: string;
  finishedAt?: string;
  createdAt?: string;
}

export async function listDbxConnections(
  sourceId: number
): Promise<DbxConnection[]> {
  return request(`/api/meta/sync/dbx-connections?sourceId=${sourceId}`, {
    method: 'GET',
  });
}

export async function submitMetaSync(payload: {
  sourceId: number;
  scope: 'CATALOG' | 'SCHEMA' | 'TABLE';
  connectionId?: string;
  metalake?: string;
  catalog?: string;
  database?: string;
  schema?: string;
  targetCatalog?: string;
  tables?: string[];
  overwriteManual?: boolean;
}): Promise<{ jobId: number }> {
  return request('/api/meta/sync', { method: 'POST', data: payload });
}

export async function getMetaSyncJob(jobId: number): Promise<SyncJob> {
  return request(`/api/meta/sync/jobs/${jobId}`, { method: 'GET' });
}

export async function listMetaSyncJobs(limit = 10): Promise<SyncJob[]> {
  return request(`/api/meta/sync/jobs?limit=${limit}`, { method: 'GET' });
}

// ---------------------------------------------------------------
// 数据目录与临时库表规则（配置 - 数据目录）
// ---------------------------------------------------------------

export interface DataCatalog {
  id: number;
  name: string;
  isDefault: boolean;
  description?: string;
  /** 该目录下已登记的表数量。列表里就带上，因为「能不能删」直接取决于它 */
  tableCount: number;
}

export type TempRuleTarget = 'SCHEMA' | 'TABLE';
export type TempMatchType = 'GLOB' | 'REGEX';

export interface TempRule {
  id: number;
  /** 空表示对本项目所有数据目录生效 */
  catalogName?: string | null;
  target: TempRuleTarget;
  matchType: TempMatchType;
  pattern: string;
  enabled: boolean;
  description?: string | null;
}

export interface TempRuleTestResult {
  input: string;
  temp: boolean;
  matchedRuleId: number | null;
  matchedPattern: string | null;
  explanation: string;
}

export async function listDataCatalogs(): Promise<DataCatalog[]> {
  return request('/api/data-catalogs', { method: 'GET' });
}

export async function createDataCatalog(payload: {
  name: string;
  description?: string;
}): Promise<DataCatalog> {
  return request('/api/data-catalogs', { method: 'POST', data: payload });
}

export async function updateDataCatalog(
  id: number,
  payload: { name: string; description?: string }
): Promise<DataCatalog> {
  return request(`/api/data-catalogs/${id}`, { method: 'PUT', data: payload });
}

/** 设为默认目录。默认只能有一个，后端会自动把旧的取消。 */
export async function setDefaultDataCatalog(id: number): Promise<DataCatalog> {
  return request(`/api/data-catalogs/${id}/default`, { method: 'POST' });
}

export async function deleteDataCatalog(id: number): Promise<void> {
  return request(`/api/data-catalogs/${id}`, { method: 'DELETE' });
}

export async function listTempRules(): Promise<TempRule[]> {
  return request('/api/data-catalogs/temp-rules', { method: 'GET' });
}

export async function createTempRule(payload: {
  catalogName?: string;
  target: TempRuleTarget;
  matchType: TempMatchType;
  pattern: string;
  enabled?: boolean;
  description?: string;
}): Promise<TempRule> {
  return request('/api/data-catalogs/temp-rules', {
    method: 'POST',
    data: payload,
  });
}

export async function updateTempRule(
  id: number,
  payload: {
    catalogName?: string;
    target: TempRuleTarget;
    matchType: TempMatchType;
    pattern: string;
    enabled?: boolean;
    description?: string;
  }
): Promise<TempRule> {
  return request(`/api/data-catalogs/temp-rules/${id}`, {
    method: 'PUT',
    data: payload,
  });
}

export async function deleteTempRule(id: number): Promise<void> {
  return request(`/api/data-catalogs/temp-rules/${id}`, { method: 'DELETE' });
}

/**
 * 规则试算。
 *
 * 正则写错了不会报错，只会默默匹配不上 —— 没有这个入口，用户要等到血缘图不对才发现。
 */
export async function testTempRule(name: string): Promise<TempRuleTestResult> {
  return request(
    `/api/data-catalogs/temp-rules/test?name=${encodeURIComponent(name)}`,
    {
      method: 'GET',
    }
  );
}
