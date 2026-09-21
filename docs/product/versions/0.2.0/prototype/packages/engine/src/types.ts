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
export type ProjectRole = 'admin' | 'modeler' | 'viewer';
export type ProductModule =
  | 'warehouse'
  | 'metadata'
  | 'quality'
  | 'serve'
  | 'scheduler'
  | 'dev'
  | 'materialize';

/** @deprecated 地址改到平台服务注册，租户不再填 */
export type ProductEndpointStatus = 'unconfigured' | 'ok' | 'error';

export interface ProductEndpoint {
  product: ProductModule;
  enabled: boolean;
  baseUrl: string;
  status: ProductEndpointStatus;
  lastTestAt?: string;
  note?: string;
}

export type ServiceSource = 'manual' | 'heartbeat';
export type ServiceHealth = 'online' | 'offline';
/** 租户内：谁能看见该模块菜单 */
export type ModuleVisibleTo = 'tenant_admin' | 'project_admin' | 'role_holders' | 'all_members';

export interface TenantModulePolicy {
  product: ProductModule;
  /** 本组织是否启用。不能超出平台已开通。 */
  enabled: boolean;
  visibleTo: ModuleVisibleTo;
}

/** 平台级服务实例。各产品进程启动后向平台上报，也可由平台用户手工登记。 */
export interface PlatformService {
  id: string;
  product: ProductModule;
  name: string;
  baseUrl: string;
  source: ServiceSource;
  status: ServiceHealth;
  lastSeenAt?: string;
  note?: string;
}
export type AiCap = 'spec_design' | 'spec_ask' | 'model_design';
export type AiPromptSlot = 'spec.system' | 'spec.ask.system' | 'model.system';
export type EngineKind = 'hive' | 'spark' | 'clickhouse' | 'doris';
export type ComputeStatus = 'unconfigured' | 'ok' | 'error';

/** 租户自有计算资源：调度集群、数仓引擎。不进平台服务注册。 */
export interface TenantScheduler {
  provider: 'dolphinscheduler';
  enabled: boolean;
  baseUrl: string;
  token?: string;
  tokenMasked?: string;
  status: ComputeStatus;
  lastTestAt?: string;
  note?: string;
}

export interface TenantEngineBind {
  kind: EngineKind;
  name: string;
  enabled: boolean;
  status: ComputeStatus;
  note?: string;
}

export interface Tenant {
  id: string;
  code: string;
  name: string;
  owner: string;
  status: TenantStatus;
  /** 平台给该租户开通的产品（上限） */
  modules: ProductModule[];
  /** 租户内再裁一层：启用 + 谁能看见 */
  modulePolicies?: TenantModulePolicy[];
  /** 空 = 仓建设已开则三项全开 */
  aiCaps?: AiCap[];
  /** 本组织自己的 DolphinScheduler，不在平台登记 */
  scheduler?: TenantScheduler;
  /** 本组织自己的数仓引擎连接，后期配置 */
  engineBinds?: TenantEngineBind[];
  /** 已废弃：产品地址在平台服务注册，不在租户上 */
  endpoints?: ProductEndpoint[];
}

export interface Account {
  id: string;
  username: string;
  displayName: string;
  password: string;
  platformAdmin: boolean;
  status: AccountStatus;
}

export interface UserTenant {
  userId: string;
  tenantId: string;
  role: TenantOrgRole;
}

export interface ProjectMember {
  projectId: string;
  userId: string;
  /** 组织角色：admin = 项目管理员（各已开通产品默认按该产品管理角色）。modeler/viewer 视为成员，并作为仓建设角色的兜底。 */
  role: ProjectRole;
  /** 各独立产品自己的角色码。项目管理员不必填。 */
  productRoles?: Partial<Record<ProductModule, string>>;
}

export type GrantKind = 'permanent' | 'timed';

export interface GrantProjectScope {
  projectId: string;
  role: ProjectRole;
}

/** 租户发给平台侧的进入授权 */
export interface TenantGrant {
  id: string;
  tenantId: string;
  code: string;
  kind: GrantKind;
  expiresAt: string | null;
  createdBy: string;
  createdAt: string;
  revoked: boolean;
  /** 空 = 本租户已开通的全部功能 */
  modules?: ProductModule[];
  /** 空 = 全部项目；与 projectScopes 配套 */
  projectIds?: string[];
  /** 全部项目时的统一角色 */
  defaultRole?: ProjectRole;
  projectScopes?: GrantProjectScope[];
  /** 空 = 不限制，取租户已开通 */
  aiCaps?: AiCap[];
}

/** 平台用户凭授权码绑定后的进入资格 */
export interface PlatformAccess {
  userId: string;
  tenantId: string;
  grantId: string;
  boundAt: string;
}

export interface Project {
  id: string;
  tenantId: string;
  code: string;
  name: string;
  description: string;
  owner: string;
  createdAt: string;
  status?: 'active' | 'disabled';
  /** 本项目开通的引擎知识库；空 = 未挂手册 */
  engines?: EngineKind[];
}

export type ProjectBindTarget = ProductModule | 'scheduler';
export type ProjectBindStatus = 'ok' | 'pending' | 'skipped';

/** 组织项目落到各模块的镜像。remoteId 必须等于组织 projectId。 */
export interface ProjectModuleBind {
  projectId: string;
  target: ProjectBindTarget;
  remoteId: string;
  status: ProjectBindStatus;
  lastSyncAt?: string;
  note?: string;
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
  /** 所属项目。列表只认本项目，不再回退到无 projectId 的全局行 */
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

export type FieldLogicKind = 'passthrough' | 'transform' | 'aggregate' | 'derive' | 'constant';

export interface FieldSourceRef {
  alias: string;
  column: string;
}

export interface FieldLogic {
  kind: FieldLogicKind;
  /** 人读口径；空 = 尚未定义 */
  desc?: string;
  sources?: FieldSourceRef[];
  op?: string;
  expr?: string;
  filter?: string;
}

export interface TableSourceRef {
  tableId: string;
  alias: string;
}

export interface TableJoin {
  leftAlias: string;
  leftColumn: string;
  rightAlias: string;
  rightColumn: string;
  type?: 'inner' | 'left';
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
  logic?: FieldLogic;
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
  sources?: TableSourceRef[];
  joins?: TableJoin[];
  filter?: string;
  /** 表等级编码，对应规范中心数据等级 */
  grade?: string;
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
  createdFrom?: string;
  sources?: TableSourceRef[];
  joins?: TableJoin[];
  filter?: string;
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
  sources?: TableSourceRef[];
  joins?: TableJoin[];
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
  logic?: FieldLogic;
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
  sources?: TableSourceRef[];
  joins?: TableJoin[];
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

export interface AppState {
  tenants: Tenant[];
  projects: Project[];
  currentTenantId: string | null;
  currentProjectId: string | null;
  currentUserId: string | null;
  currentUser: string;
  accounts: Account[];
  userTenants: UserTenant[];
  members: ProjectMember[];
  grants: TenantGrant[];
  platformAccess: PlatformAccess[];
  domains: Domain[];
  layerRules: LayerRule[];
  grades: DataGrade[];
  roots: WordRoot[];
  tables: WarehouseTable[];
  tableVersions: TableVersion[];
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
  /** 租户导入的知识库篇，按引擎挂到内置手册后 */
  knowledgeArticles?: TenantKnowledgeArticle[];
  /** 平台服务注册表（建模 / 元数据 / 质量 / 服务） */
  services?: PlatformService[];
  /** 组织项目在各模块的同 ID 镜像 */
  projectBinds?: ProjectModuleBind[];
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
