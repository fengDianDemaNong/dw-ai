export type Layer = 'ODS' | 'DWD' | 'DWS' | 'ADS';
export type RootKind = 'biz' | 'tech' | 'time';
export type MetricType = 'atomic' | 'derived' | 'composite';
export type JobType = 'sync' | 'etl' | 'quality' | 'materialize';
export type JobStatus = 'idle' | 'running' | 'success' | 'failed';
export type RecommendAction = 'create' | 'extend' | 'retire';
export type RecommendStatus = 'pending' | 'approved' | 'rejected' | 'gray' | 'online';
export type Sensitivity = 'public' | 'internal' | 'secret';

export type TenantStatus = 'active' | 'disabled';
export type AccountStatus = 'active' | 'disabled';
export type TenantOrgRole = 'admin' | 'member';
export type ProductModule = 'warehouse' | 'serve' | 'quality' | 'materialize' | 'dev';
export type AiCap = 'spec_design' | 'spec_ask' | 'model_design';
export type AiPromptSlot = 'spec.system' | 'spec.ask.system' | 'model.system';
export type EngineKind = 'hive' | 'spark' | 'clickhouse' | 'doris';

export interface Tenant {
  id: string;
  code: string;
  name: string;
  owner: string;
  status?: TenantStatus;
  modules?: ProductModule[];
  /** 空 = 仓建设已开则三项全开 */
  aiCaps?: AiCap[];
}

export interface Project {
  id: string;
  tenantId: string;
  code: string;
  name: string;
  description: string;
  owner: string;
  createdAt: string;
  status?: string;
  /** 本项目开通的引擎知识库；空 = 未挂手册 */
  engines?: EngineKind[];
}

export interface Domain {
  id: string;
  projectId: string;
  code: string;
  name: string;
  definition: string;
  bizOwner: string;
  techOwner: string;
  dataOwner: string;
  related: string[];
  coreEntities: string[];
}

export type MaskingPolicy = 'keep' | 'mask' | 'hash' | 'encrypt' | 'drop';
export type NullPolicy = 'keep' | 'fill' | 'reject';

export interface LayerRule {
  /** ODS/DWD/DWS/ADS，也允许自定义如 DIM、STG */
  layer: string;
  /** 空 = 全局默认；有值 = 该项目覆盖 */
  projectId?: string;
  naming: string;
  retention: string;
  serve: 'forbid' | 'approval' | 'allow';
  note: string;
  /** 字段命名/格式 */
  fieldFormat?: string;
  /** 日期时间格式 */
  timeFormat?: string;
  /** 脱敏策略 */
  masking?: MaskingPolicy;
  maskingNote?: string;
  /** 空值处理 */
  nullHandling?: NullPolicy;
  /** 空值填充说明 */
  nullFill?: string;
}

export type QueryPolicy = 'allow' | 'login' | 'approval' | 'forbid';
export type ExportPolicy = 'allow' | 'approval' | 'forbid';

export interface GradeDraft {
  code: string;
  name: string;
  level: number;
  color: string;
  query: QueryPolicy;
  export: ExportPolicy;
  note: string;
  examples: string;
}

export interface DataGrade extends GradeDraft {
  id: string;
  projectId: string;
}

export interface SpecDomainDraft {
  code: string;
  name: string;
  definition: string;
  bizOwner: string;
  techOwner: string;
  dataOwner: string;
  related: string[];
  coreEntities: string[];
}

export interface SpecRootDraft {
  kind: RootKind;
  code: string;
  zh: string;
  en: string;
  domain?: string;
  formula?: string;
  dataType?: string;
  format?: string;
}

export interface SpecProposal {
  industry: string;
  industryLabel: string;
  summary: string;
  rationale: string[];
  fieldNaming: string;
  domains: SpecDomainDraft[];
  layers: LayerRule[];
  roots: SpecRootDraft[];
  grades: GradeDraft[];
}

export interface WordRoot {
  id: string;
  projectId: string;
  kind: RootKind;
  code: string;
  zh: string;
  en: string;
  domain?: string;
  formula?: string;
  dataType?: string;
  format?: string;
}

export interface Column {
  name: string;
  type: string;
  comment: string;
  nullable?: boolean;
  /** 不为空时可选，写入 DDL DEFAULT */
  defaultValue?: string;
  sensitive?: boolean;
  /** 字段等级编码，空则继承表等级 */
  grade?: string;
  enumValues?: string[];
}

export interface WarehouseTable {
  id: string;
  projectId: string;
  layer: string;
  name: string;
  comment: string;
  domain?: string;
  sourceSystem?: string;
  grain?: string;
  period?: string;
  columns: Column[];
  partition?: string;
  storedAs?: string;
  status: 'draft' | 'published' | 'deprecated';
  createdFrom?: string;
  /** 表等级编码，对应规范中心数据等级 */
  grade?: string;
  currentVersion?: number;
}

export interface TableSnapshot {
  name: string;
  comment: string;
  domain?: string;
  grain?: string;
  period?: string;
  partition?: string;
  status: WarehouseTable['status'];
  grade?: string;
  columns: Column[];
}

export interface TableVersion {
  id: string;
  tableId: string;
  projectId: string;
  version: number;
  createdAt: string;
  createdBy: string;
  note: string;
  snapshot: TableSnapshot;
}

export interface ModelTableDraft {
  key: string;
  mode: 'create' | 'update';
  tableId?: string;
  layer: string;
  name: string;
  comment: string;
  domain?: string;
  grain?: string;
  period?: string;
  partition?: string;
  grade?: string;
  columns: Column[];
  summary: string;
}

export interface ModelChatProposal {
  layer: string;
  summary: string;
  tables: ModelTableDraft[];
}

export interface FieldTag {
  field: string;
  type: string;
  comment: string;
  suggestedName: string;
  roots: string[];
  meaning: string;
  confidence: number;
  sensitive: boolean;
  /** 本层脱敏/时间/空值处理后的表达式说明 */
  transform?: MaskingPolicy | 'time' | 'fill';
  dropped?: boolean;
}

export interface QualityRule {
  id: string;
  table: string;
  type: 'pk_unique' | 'null_rate' | 'enum' | 'volatility' | 'format';
  field?: string;
  logic: string;
  threshold: string;
  status: 'ok' | 'warn' | 'fail' | 'idle';
  lastValue?: string;
}

export interface ModelingDraft {
  id: string;
  projectId: string;
  sourceTableId: string;
  targetLayer: Layer;
  domainCode: string;
  domainConfidence: number;
  grain: string;
  primaryKeys: string[];
  fieldTags: FieldTag[];
  ddl: string;
  etlSql: string;
  qualityRules: QualityRule[];
  specIssues: SpecIssue[];
  status: 'pending_review' | 'approved' | 'rejected';
  createdAt: string;
}

export interface SpecIssue {
  level: 'error' | 'warn' | 'info';
  rule: string;
  message: string;
}

export interface Job {
  id: string;
  projectId: string;
  name: string;
  type: JobType;
  engine: string;
  dependsOn: string[];
  status: JobStatus;
  lastRun?: string;
  durationMs?: number;
  table?: string;
}

export interface Metric {
  id: string;
  projectId: string;
  name: string;
  type: MetricType;
  businessProcess: string;
  measure: string;
  aggregation: string;
  dataType: string;
  unit: string;
  definition: string;
  calculationLogic: string;
  sourceTable: string;
  dimensions: string[];
  modifiers?: string[];
  timePeriod?: string;
  formula?: string;
  owner: string;
  status: 'draft' | 'review' | 'published';
  version: string;
  changelog: { version: string; date: string; change: string }[];
  sensitivity: Sensitivity;
  score: number;
  favorites: number;
}

export interface MeasureSpec {
  field: string;
  agg: string;
  alias: string;
}

export interface FilterSpec {
  field: string;
  op: string;
  value: string;
}

export interface QueryLog {
  id: string;
  projectId: string;
  userId: string;
  timestamp: string;
  datasource: string;
  dimensions: string[];
  measures: MeasureSpec[];
  filters: FilterSpec[];
  sqlFingerprint: string;
  executionTimeMs: number;
  scanRows: number;
  costScore: number;
}

export interface QueryCluster {
  id: string;
  projectId: string;
  queryIds: string[];
  dimensions: string[];
  measures: MeasureSpec[];
  monthlyCount: number;
  avgMs: number;
  suggestion: string;
}

export interface MaterializeRec {
  id: string;
  projectId: string;
  clusterId: string;
  action: RecommendAction;
  targetTable: string;
  ddl: string;
  precomputeSql: string;
  scores: {
    frequency: number;
    compute: number;
    freshness: number;
    storage: number;
    reuse: number;
    total: number;
  };
  status: RecommendStatus;
  grayPercent: number;
  roi?: { savedCompute: string; storageCost: string };
}

export interface ApiCall {
  id: string;
  projectId: string;
  appKey: string;
  metricId: string;
  userId: string;
  returnRows: number;
  costMs: number;
  cacheHit: boolean;
  timestamp: string;
}

export interface FactoryConfig {
  layer: Layer;
  table: string;
  dimensions: string[];
  measures: MeasureSpec[];
  filters: FilterSpec[];
  timeGrain: 'day' | 'week' | 'month';
  timeRange: string;
  nullFill: boolean;
  yoy: boolean;
}

export interface ProjectMember {
  projectId: string;
  userId: string;
  role: 'admin' | 'modeler' | 'viewer';
}

export interface TenantLicense {
  tenantId: string;
  modules: ProductModule[];
  aiCaps?: AiCap[];
}

export interface KnowledgeSection {
  heading: string;
  body: string;
  sql?: string;
  sqlCaption?: string;
  note?: string;
}

/** 工作台导入的一篇手册，属于租户 + 引擎 */
export interface TenantKnowledgeArticle {
  id: string;
  tenantId: string;
  engine: EngineKind;
  title: string;
  summary: string;
  body: string;
  sourceUrl?: string;
  sourceLabel?: string;
  sections: KnowledgeSection[];
  notes?: string[];
  importedAt: string;
  importedBy: string;
}

export interface AppState {
  tenants: Tenant[];
  projects: Project[];
  currentTenantId: string;
  currentProjectId: string | null;
  currentUser: string;
  members: ProjectMember[];
  licenses: TenantLicense[];
  domains: Domain[];
  layerRules: LayerRule[];
  grades: DataGrade[];
  roots: WordRoot[];
  tables: WarehouseTable[];
  drafts: ModelingDraft[];
  jobs: Job[];
  qualityRules: QualityRule[];
  metrics: Metric[];
  queryLogs: QueryLog[];
  clusters: QueryCluster[];
  recs: MaterializeRec[];
  apiCalls: ApiCall[];
  /** 数据服务（新）：与旧 metrics 隔离 */
  serveFolders: ServeFolder[];
  serveMetrics: ServeMetric[];
  knowledgeArticles?: TenantKnowledgeArticle[];
}

export type ServeScope = 'public' | 'business' | 'personal';

export interface ServeFolder {
  id: string;
  projectId: string;
  scope: ServeScope;
  name: string;
  /** 个人目录归属；公共/业务可空 */
  owner?: string;
}

export interface ServeMetric {
  id: string;
  projectId: string;
  name: string;
  definition: string;
  calculationLogic: string;
  sourceTable: string;
  dimensions: string[];
  measure: string;
  aggregation: string;
  unit: string;
  owner: string;
  scope: ServeScope;
  folderId: string;
  status: 'draft' | 'review' | 'published';
  /** 从哪条个人指标发布而来 */
  clonedFrom?: string;
}
