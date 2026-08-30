import { computed, reactive, readonly } from 'vue';
import { message } from 'ant-design-vue';
import type {
  AppState,
  DataGrade,
  Domain,
  FactoryConfig,
  GradeDraft,
  LayerRule,
  Metric,
  Project,
  ServeFolder,
  ServeMetric,
  ServeScope,
  SpecDomainDraft,
  SpecRootDraft,
  WarehouseTable,
  WordRoot,
} from '../types';
import { createSeed, seedServe } from '../mock/seed';
import { GRADE_TEMPLATES } from '../config/grades';
import { hydrateLayerRule } from '../config/layerPolicies';
import {
  buildDwdDraft,
  buildDwsDraft,
  tableFromDwdDraft,
  tableFromDwsDraft,
} from '../engine/modeling';
import { clusterLogs, scoreCluster } from '../engine/materialize';
import { checkDuplicate } from '../engine/metrics';
import { buildSpecPack, type SpecPack } from '../engine/specIo';

const STORAGE_KEY = 'dw-ai.proto.0.1.0';

function load(): AppState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as AppState;
      if (parsed?.tenants?.length) {
        if (!Array.isArray(parsed.grades)) parsed.grades = [];
        if (!Array.isArray(parsed.serveFolders) || !parsed.serveFolders.length) {
          const extra = seedServe('p-trade');
          parsed.serveFolders = extra.serveFolders;
          parsed.serveMetrics = extra.serveMetrics;
        }
        return parsed;
      }
    }
  } catch {
    /* ignore */
  }
  return createSeed();
}

const state = reactive<AppState>(load());

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    /* ignore */
  }
}

export const app = readonly(state);

export const currentTenant = computed(() => state.tenants.find((t) => t.id === state.currentTenantId));

export const tenantProjects = computed(() =>
  state.projects.filter((p) => p.tenantId === state.currentTenantId)
);

export const currentProject = computed(() =>
  state.projects.find((p) => p.id === state.currentProjectId)
);

function inProject<T extends { projectId: string }>(list: T[]): T[] {
  return list.filter((x) => x.projectId === state.currentProjectId);
}

export const projectDomains = computed(() => inProject(state.domains));
export const projectGrades = computed(() =>
  inProject(state.grades).slice().sort((a, b) => a.level - b.level)
);
export const projectRoots = computed(() => inProject(state.roots));
export const projectTables = computed(() => inProject(state.tables));
export const projectDrafts = computed(() => inProject(state.drafts));
export const projectJobs = computed(() => inProject(state.jobs));
export const projectMetrics = computed(() => inProject(state.metrics));
export const projectLogs = computed(() => inProject(state.queryLogs));
export const projectClusters = computed(() => inProject(state.clusters));
export const projectRecs = computed(() => inProject(state.recs));
export const projectApiCalls = computed(() => inProject(state.apiCalls));
export const currentUser = computed(() => state.currentUser);
export const projectServeFolders = computed(() => inProject(state.serveFolders));
export const projectServeMetrics = computed(() => inProject(state.serveMetrics));

export function visibleServeFolders(scope: ServeScope): ServeFolder[] {
  return projectServeFolders.value.filter((f) => {
    if (f.scope !== scope) return false;
    if (scope === 'personal') return f.owner === state.currentUser;
    return true;
  });
}

export function visibleServeMetrics(scope: ServeScope): ServeMetric[] {
  return projectServeMetrics.value.filter((m) => {
    if (m.scope !== scope) return false;
    if (scope === 'personal') return m.owner === state.currentUser;
    if (scope === 'public') return m.status === 'published' || m.status === 'review';
    return m.status !== 'draft';
  });
}
export const projectQuality = computed(() =>
  state.qualityRules.filter((q) => projectTables.value.some((t) => t.name === q.table))
);

export const projectLayerRules = computed(() => {
  const pid = state.currentProjectId;
  const own = state.layerRules.filter((r) => r.projectId === pid);
  if (own.length) return own;
  return state.layerRules.filter((r) => !r.projectId);
});

export function switchTenant(tenantId: string) {
  state.currentTenantId = tenantId;
  state.currentProjectId = null;
  persist();
}

export function enterProject(projectId: string) {
  const p = state.projects.find((x) => x.id === projectId);
  if (!p) return;
  state.currentTenantId = p.tenantId;
  state.currentProjectId = p.id;
  persist();
}

export function leaveProject() {
  state.currentProjectId = null;
  persist();
}

export function createProject(input: {
  code: string;
  name: string;
  description: string;
  owner: string;
  bootstrapSpec?: boolean;
}): Project {
  const project: Project = {
    id: `p-${Date.now()}`,
    tenantId: state.currentTenantId,
    code: input.code,
    name: input.name,
    description: input.description,
    owner: input.owner || state.currentUser,
    createdAt: new Date().toISOString().slice(0, 10),
  };
  state.projects.push(project);
  const techTime: WordRoot[] = state.roots
    .filter((r) => r.projectId === 'p-trade' && r.kind !== 'biz')
    .map((r) => ({ ...r, id: `${r.id}-${project.id}`, projectId: project.id }));
  state.roots.push(...techTime);
  if (input.bootstrapSpec) bootstrapProjectSpec(project.id);
  persist();
  message.success(
    input.bootstrapSpec
      ? `项目「${project.name}」已创建，并导入通用规范（分层 + 四级等级 + 基础词根）`
      : `项目「${project.name}」已创建`
  );
  return project;
}

function bootstrapProjectSpec(projectId: string) {
  if (!state.layerRules.some((r) => r.projectId === projectId)) {
    const defaults = state.layerRules.filter((r) => !r.projectId);
    for (const d of defaults) {
      state.layerRules.push({ ...hydrateLayerRule(d), projectId });
    }
  }
  if (!state.grades.some((g) => g.projectId === projectId)) {
    const tpl = GRADE_TEMPLATES.find((t) => t.id === 'std-4');
    tpl?.grades.forEach((draft, i) => {
      state.grades.push({
        ...draft,
        code: draft.code.toUpperCase(),
        id: `g-${projectId}-${i}`,
        projectId,
      });
    });
  }
}

export function addDomain(input: Omit<Domain, 'id' | 'projectId'>): Domain | null {
  if (!state.currentProjectId) return null;
  if (projectDomains.value.some((d) => d.code === input.code.toUpperCase())) {
    message.error('主题域编码必须全局唯一');
    return null;
  }
  const d: Domain = {
    ...input,
    id: `d-${Date.now()}`,
    projectId: state.currentProjectId,
    code: input.code.toUpperCase(),
  };
  state.domains.push(d);
  persist();
  message.success(`主题域 ${d.code} 已登记`);
  return d;
}

export function addRoot(input: Omit<WordRoot, 'id' | 'projectId'>): WordRoot | null {
  if (!state.currentProjectId) return null;
  if (projectRoots.value.some((r) => r.code === input.code)) {
    message.error('词根编码已存在');
    return null;
  }
  const r: WordRoot = { ...input, id: `r-${Date.now()}`, projectId: state.currentProjectId };
  state.roots.push(r);
  persist();
  message.success(`词根 ${r.code} 已入库`);
  return r;
}

export function updateDomain(
  id: string,
  input: Omit<Domain, 'id' | 'projectId'>
): Domain | null {
  const d = state.domains.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!d) return null;
  const code = input.code.toUpperCase();
  if (projectDomains.value.some((x) => x.code === code && x.id !== id)) {
    message.error('主题域编码必须全局唯一');
    return null;
  }
  Object.assign(d, { ...input, code });
  persist();
  message.success(`主题域 ${d.code} 已更新`);
  return d;
}

export function removeDomain(id: string) {
  const d = state.domains.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!d) return;
  const pid = state.currentProjectId;
  state.domains = state.domains.filter((x) => x.id !== id);
  for (const other of state.domains) {
    if (other.projectId === pid) {
      other.related = other.related.filter((c) => c !== d.code);
    }
  }
  for (const r of state.roots) {
    if (r.projectId === pid && r.domain === d.code) r.domain = undefined;
  }
  for (const t of state.tables) {
    if (t.projectId === pid && t.domain === d.code) t.domain = undefined;
  }
  persist();
  message.success(`主题域 ${d.code} 已删除`);
}

export function updateRoot(id: string, input: Omit<WordRoot, 'id' | 'projectId'>): WordRoot | null {
  const r = state.roots.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!r) return null;
  if (projectRoots.value.some((x) => x.code === input.code && x.id !== id)) {
    message.error('词根编码已存在');
    return null;
  }
  Object.assign(r, input);
  persist();
  message.success(`词根 ${r.code} 已更新`);
  return r;
}

export function removeRoot(id: string) {
  const r = state.roots.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!r) return;
  state.roots = state.roots.filter((x) => x.id !== id);
  persist();
  message.success(`词根 ${r.code} 已删除`);
}

function ensureProjectLayers() {
  const pid = state.currentProjectId;
  if (!pid) return;
  if (state.layerRules.some((r) => r.projectId === pid)) return;
  const defaults = state.layerRules.filter((r) => !r.projectId);
  for (const d of defaults) {
    state.layerRules.push({ ...d, projectId: pid });
  }
}

export function addLayer(input: Omit<LayerRule, 'projectId'>): LayerRule | null {
  if (!state.currentProjectId) return null;
  ensureProjectLayers();
  const layer = input.layer.toUpperCase();
  if (projectLayerRules.value.some((r) => r.layer === layer)) {
    message.error(`分层 ${layer} 已存在`);
    return null;
  }
  const row: LayerRule = { ...input, layer, projectId: state.currentProjectId };
  state.layerRules.push(row);
  persist();
  message.success(`分层 ${layer} 已新增`);
  return row;
}

export function updateLayer(prevLayer: string, input: Omit<LayerRule, 'projectId'>): LayerRule | null {
  if (!state.currentProjectId) return null;
  ensureProjectLayers();
  const row = state.layerRules.find(
    (r) => r.projectId === state.currentProjectId && r.layer === prevLayer
  );
  if (!row) return null;
  const layer = input.layer.toUpperCase();
  if (layer !== prevLayer && projectLayerRules.value.some((r) => r.layer === layer)) {
    message.error(`分层 ${layer} 已存在`);
    return null;
  }
  Object.assign(row, { ...input, layer });
  if (layer !== prevLayer) {
    for (const t of state.tables) {
      if (t.projectId === state.currentProjectId && t.layer === prevLayer) t.layer = layer;
    }
  }
  persist();
  message.success(`分层 ${layer} 已更新`);
  return row;
}

export function removeLayer(layer: string) {
  if (!state.currentProjectId) return;
  ensureProjectLayers();
  const before = state.layerRules.length;
  state.layerRules = state.layerRules.filter(
    (r) => !(r.projectId === state.currentProjectId && r.layer === layer)
  );
  if (state.layerRules.length === before) return;
  persist();
  message.success(`分层 ${layer} 已删除`);
}

export function addGrade(input: GradeDraft): DataGrade | null {
  if (!state.currentProjectId) return null;
  const code = input.code.toUpperCase();
  if (projectGrades.value.some((g) => g.code === code)) {
    message.error('等级编码必须项目内唯一');
    return null;
  }
  const g: DataGrade = { ...input, code, id: `g-${Date.now()}`, projectId: state.currentProjectId };
  state.grades.push(g);
  persist();
  message.success(`等级 ${g.code} 已新增`);
  return g;
}

export function updateGrade(id: string, input: GradeDraft): DataGrade | null {
  const g = state.grades.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!g) return null;
  const code = input.code.toUpperCase();
  if (projectGrades.value.some((x) => x.code === code && x.id !== id)) {
    message.error('等级编码必须项目内唯一');
    return null;
  }
  const prev = g.code;
  Object.assign(g, { ...input, code });
  if (prev !== code) {
    for (const t of state.tables) {
      if (t.projectId !== state.currentProjectId) continue;
      if (t.grade === prev) t.grade = code;
      for (const c of t.columns) if (c.grade === prev) c.grade = code;
    }
  }
  persist();
  message.success(`等级 ${g.code} 已更新`);
  return g;
}

export function removeGrade(id: string) {
  const g = state.grades.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!g) return;
  state.grades = state.grades.filter((x) => x.id !== id);
  for (const t of state.tables) {
    if (t.projectId !== state.currentProjectId) continue;
    if (t.grade === g.code) t.grade = undefined;
    for (const c of t.columns) if (c.grade === g.code) c.grade = undefined;
  }
  persist();
  message.success(`等级 ${g.code} 已删除`);
}

export function applyGradeTemplate(templateId: string, replace: boolean) {
  if (!state.currentProjectId) return null;
  const tpl = GRADE_TEMPLATES.find((t) => t.id === templateId);
  if (!tpl) {
    message.error('模板不存在');
    return null;
  }
  const pid = state.currentProjectId;
  if (replace) {
    state.grades = state.grades.filter((g) => g.projectId !== pid);
  }
  let added = 0;
  let skipped = 0;
  tpl.grades.forEach((draft, i) => {
    const code = draft.code.toUpperCase();
    if (state.grades.some((g) => g.projectId === pid && g.code === code)) {
      skipped++;
      return;
    }
    state.grades.push({
      ...draft,
      code,
      id: `g-${Date.now()}-${i}`,
      projectId: pid,
    });
    added++;
  });
  persist();
  message.success(
    replace
      ? `已替换为「${tpl.name}」，写入 ${added} 个等级`
      : `已导入「${tpl.name}」：新增 ${added}${skipped ? `，跳过 ${skipped}` : ''}`
  );
  return { added, skipped };
}

export function addTable(input: Omit<WarehouseTable, 'id' | 'projectId'>): WarehouseTable | null {
  if (!state.currentProjectId) return null;
  if (projectTables.value.some((t) => t.name === input.name)) {
    message.error('表名已存在');
    return null;
  }
  const t: WarehouseTable = {
    ...input,
    id: `tbl-${Date.now()}`,
    projectId: state.currentProjectId,
    columns: input.columns.map((c) => ({ ...c })),
  };
  state.tables.push(t);
  persist();
  message.success(`${t.name} 已新增`);
  return t;
}

export function updateTable(
  id: string,
  input: Omit<WarehouseTable, 'id' | 'projectId'>
): WarehouseTable | null {
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return null;
  if (projectTables.value.some((x) => x.name === input.name && x.id !== id)) {
    message.error('表名已存在');
    return null;
  }
  Object.assign(t, { ...input, columns: input.columns.map((c) => ({ ...c })) });
  persist();
  message.success(`${t.name} 已更新`);
  return t;
}

export function removeTable(id: string) {
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return;
  state.tables = state.tables.filter((x) => x.id !== id);
  state.qualityRules = state.qualityRules.filter((q) => q.table !== t.name);
  persist();
  message.success(`${t.name} 已删除`);
}

export function applySpecProposal(input: {
  domains: SpecDomainDraft[];
  layers: LayerRule[];
  roots: SpecRootDraft[];
  grades?: GradeDraft[];
  overwrite: boolean;
}) {
  if (!state.currentProjectId) return null;
  const pid = state.currentProjectId;
  const stamp = Date.now();
  let addedDomains = 0;
  let updatedDomains = 0;
  let skippedDomains = 0;
  let addedRoots = 0;
  let updatedRoots = 0;
  let skippedRoots = 0;

  input.domains.forEach((d, i) => {
    const code = d.code.toUpperCase();
    const exist = state.domains.find((x) => x.projectId === pid && x.code === code);
    if (exist) {
      if (input.overwrite) {
        exist.name = d.name;
        exist.definition = d.definition;
        exist.related = d.related;
        exist.coreEntities = d.coreEntities;
        updatedDomains++;
      } else {
        skippedDomains++;
      }
      return;
    }
    const row: Domain = {
      ...d,
      id: `d-${stamp}-${i}`,
      projectId: pid,
      code,
    };
    state.domains.push(row);
    addedDomains++;
  });

  if (input.layers.length) {
    state.layerRules = state.layerRules.filter((r) => r.projectId !== pid);
    for (const l of input.layers) {
      state.layerRules.push({ ...l, projectId: pid });
    }
  }

  input.roots.forEach((r, i) => {
    const exist = state.roots.find((x) => x.projectId === pid && x.code === r.code);
    if (exist) {
      if (input.overwrite) {
        exist.zh = r.zh;
        exist.en = r.en;
        exist.kind = r.kind;
        exist.domain = r.domain;
        exist.formula = r.formula;
        exist.dataType = r.dataType;
        exist.format = r.format;
        updatedRoots++;
      } else {
        skippedRoots++;
      }
      return;
    }
    const row: WordRoot = {
      ...r,
      id: `r-${stamp}-${i}`,
      projectId: pid,
    };
    state.roots.push(row);
    addedRoots++;
  });

  let addedGrades = 0;
  let updatedGrades = 0;
  let skippedGrades = 0;
  (input.grades ?? []).forEach((g, i) => {
    const code = g.code.toUpperCase();
    const exist = state.grades.find((x) => x.projectId === pid && x.code === code);
    if (exist) {
      if (input.overwrite) {
        Object.assign(exist, { ...g, code, projectId: pid });
        updatedGrades++;
      } else {
        skippedGrades++;
      }
      return;
    }
    state.grades.push({ ...g, code, id: `g-${stamp}-${i}`, projectId: pid });
    addedGrades++;
  });

  persist();
  const parts = [
    `主题域 +${addedDomains}`,
    updatedDomains ? `更新${updatedDomains}` : '',
    skippedDomains ? `跳过${skippedDomains}` : '',
    input.layers.length ? `分层 ${input.layers.length} 条已覆盖` : '',
    `词根 +${addedRoots}`,
    updatedRoots ? `更新${updatedRoots}` : '',
    skippedRoots ? `跳过${skippedRoots}` : '',
    input.grades?.length ? `等级 +${addedGrades}` : '',
    updatedGrades ? `更新${updatedGrades}` : '',
    skippedGrades ? `跳过${skippedGrades}` : '',
  ].filter(Boolean);
  message.success(`已同步进当前项目：${parts.join('，')}`);
  return { addedDomains, updatedDomains, skippedDomains, addedRoots, updatedRoots, skippedRoots, addedGrades };
}

export function buildCurrentSpecPack(): SpecPack {
  const tenant = currentTenant.value;
  const project = currentProject.value;
  return buildSpecPack(
    {
      tenantName: tenant?.name ?? '',
      tenantCode: tenant?.code ?? '',
      projectName: project?.name ?? '',
      projectCode: project?.code ?? '',
    },
    {
      domains: projectDomains.value.map(({ id: _id, projectId: _pid, ...rest }) => rest),
      layers: projectLayerRules.value.map(({ projectId: _pid, ...rest }) => rest),
      roots: projectRoots.value.map(({ id: _id, projectId: _pid, ...rest }) => rest),
      grades: projectGrades.value.map(({ id: _id, projectId: _pid, ...rest }) => rest),
    }
  );
}

export function importSpecPack(
  pack: SpecPack,
  opts: { overwrite: boolean; replace: boolean }
) {
  if (!state.currentProjectId) return null;
  const pid = state.currentProjectId;
  const stamp = Date.now();
  if (opts.replace) {
    state.domains = state.domains.filter((d) => d.projectId !== pid);
    state.roots = state.roots.filter((r) => r.projectId !== pid);
    state.layerRules = state.layerRules.filter((r) => r.projectId !== pid);
    state.grades = state.grades.filter((g) => g.projectId !== pid);
  } else {
    ensureProjectLayers();
  }

  let addedDomains = 0;
  let updatedDomains = 0;
  let skippedDomains = 0;
  let addedLayers = 0;
  let updatedLayers = 0;
  let skippedLayers = 0;
  let addedRoots = 0;
  let updatedRoots = 0;
  let skippedRoots = 0;

  pack.domains.forEach((d, i) => {
    const code = d.code.toUpperCase();
    const exist = state.domains.find((x) => x.projectId === pid && x.code === code);
    if (exist) {
      if (opts.overwrite) {
        exist.name = d.name;
        exist.definition = d.definition;
        exist.bizOwner = d.bizOwner;
        exist.techOwner = d.techOwner;
        exist.dataOwner = d.dataOwner;
        exist.related = d.related;
        exist.coreEntities = d.coreEntities;
        updatedDomains++;
      } else skippedDomains++;
      return;
    }
    state.domains.push({
      ...d,
      id: `d-${stamp}-${i}`,
      projectId: pid,
      code,
    });
    addedDomains++;
  });

  pack.layers.forEach((l) => {
    const layer = l.layer.toUpperCase();
    const exist = state.layerRules.find((x) => x.projectId === pid && x.layer === layer);
    if (exist) {
      if (opts.overwrite) {
        Object.assign(exist, { ...l, layer, projectId: pid });
        updatedLayers++;
      } else skippedLayers++;
      return;
    }
    state.layerRules.push({ ...l, layer, projectId: pid });
    addedLayers++;
  });

  pack.roots.forEach((r, i) => {
    const exist = state.roots.find((x) => x.projectId === pid && x.code === r.code);
    if (exist) {
      if (opts.overwrite) {
        exist.zh = r.zh;
        exist.en = r.en;
        exist.kind = r.kind;
        exist.domain = r.domain;
        exist.formula = r.formula;
        exist.dataType = r.dataType;
        exist.format = r.format;
        updatedRoots++;
      } else skippedRoots++;
      return;
    }
    state.roots.push({ ...r, id: `r-${stamp}-${i}`, projectId: pid });
    addedRoots++;
  });

  let addedGrades = 0;
  let updatedGrades = 0;
  let skippedGrades = 0;
  (pack.grades ?? []).forEach((g, i) => {
    const code = g.code.toUpperCase();
    const exist = state.grades.find((x) => x.projectId === pid && x.code === code);
    if (exist) {
      if (opts.overwrite) {
        Object.assign(exist, { ...g, code, projectId: pid });
        updatedGrades++;
      } else skippedGrades++;
      return;
    }
    state.grades.push({ ...g, code, id: `g-${stamp}-${i}`, projectId: pid });
    addedGrades++;
  });

  persist();
  const parts = [
    `主题域 +${addedDomains}`,
    updatedDomains ? `更新${updatedDomains}` : '',
    skippedDomains ? `跳过${skippedDomains}` : '',
    `分层 +${addedLayers}`,
    updatedLayers ? `更新${updatedLayers}` : '',
    skippedLayers ? `跳过${skippedLayers}` : '',
    `词根 +${addedRoots}`,
    updatedRoots ? `更新${updatedRoots}` : '',
    skippedRoots ? `跳过${skippedRoots}` : '',
    `等级 +${addedGrades}`,
    updatedGrades ? `更新${updatedGrades}` : '',
    skippedGrades ? `跳过${skippedGrades}` : '',
  ].filter(Boolean);
  message.success(`已导入当前项目：${parts.join('，')}`);
  return {
    addedDomains,
    updatedDomains,
    skippedDomains,
    addedLayers,
    updatedLayers,
    skippedLayers,
    addedRoots,
    updatedRoots,
    skippedRoots,
  };
}

export function runOdsToDwd(sourceId: string) {
  if (!state.currentProjectId) return null;
  const source = state.tables.find((t) => t.id === sourceId);
  if (!source) {
    message.error('找不到源表');
    return null;
  }
  const dwdRule = projectLayerRules.value.find((r) => r.layer === 'DWD');
  const draft = buildDwdDraft({
    projectId: state.currentProjectId,
    source,
    domains: projectDomains.value,
    roots: projectRoots.value,
    layerRule: hydrateLayerRule(dwdRule ?? { layer: 'DWD', naming: '', retention: '', serve: 'forbid', note: '' }),
  });
  state.drafts.unshift(draft);
  persist();
  message.success('AI 已生成 DWD 草案，请审核');
  return draft;
}

export function runDwdToDws(sourceId: string, dims?: string[]) {
  if (!state.currentProjectId) return null;
  const source = state.tables.find((t) => t.id === sourceId);
  if (!source) return null;
  const dwsRule = projectLayerRules.value.find((r) => r.layer === 'DWS');
  const draft = buildDwsDraft({
    projectId: state.currentProjectId,
    sources: [source],
    domains: projectDomains.value,
    preferredDims: dims,
    layerRule: hydrateLayerRule(dwsRule ?? { layer: 'DWS', naming: '', retention: '', serve: 'approval', note: '' }),
    grades: projectGrades.value,
    roots: projectRoots.value,
  });
  state.drafts.unshift(draft);
  persist();
  message.success('AI 已生成 DWS 草案，请审核');
  return draft;
}

export function approveDraft(draftId: string) {
  const draft = state.drafts.find((d) => d.id === draftId);
  if (!draft || !state.currentProjectId) return;
  const errors = draft.specIssues.filter((i) => i.level === 'error');
  if (errors.length) {
    message.error('规范校验未通过，禁止发布。请打回重生成。');
    return;
  }
  draft.status = 'approved';
  const source = state.tables.find((t) => t.id === draft.sourceTableId);
  let table: WarehouseTable;
  if (draft.targetLayer === 'DWD' && source) {
    table = tableFromDwdDraft(draft, source);
  } else {
    table = tableFromDwsDraft(draft);
  }
  if (!state.tables.some((t) => t.name === table.name)) state.tables.push(table);
  for (const q of draft.qualityRules) {
    if (!state.qualityRules.some((x) => x.id === q.id)) state.qualityRules.push({ ...q, status: 'ok' });
  }
  const jobId = `job-${table.name}`;
  if (!state.jobs.some((j) => j.id === jobId)) {
    const upstream = state.jobs.find((j) => j.table === source?.name);
    state.jobs.push({
      id: jobId,
      projectId: state.currentProjectId,
      name: `${table.name}.etl`,
      type: table.layer === 'DWS' ? 'etl' : 'etl',
      engine: 'Spark SQL',
      dependsOn: upstream ? [upstream.id] : [],
      status: 'success',
      lastRun: new Date().toISOString().slice(0, 16).replace('T', ' '),
      durationMs: 90000,
      table: table.name,
    });
  }
  persist();
  message.success(`${table.name} 已发布，ETL 任务已挂入调度`);
}

export function rejectDraft(draftId: string) {
  const draft = state.drafts.find((d) => d.id === draftId);
  if (!draft) return;
  draft.status = 'rejected';
  persist();
  message.info('草案已打回');
}

export function registerMetric(input: Omit<Metric, 'id' | 'projectId' | 'changelog' | 'score' | 'favorites'>): Metric | null {
  if (!state.currentProjectId) return null;
  const check = checkDuplicate(input, projectMetrics.value);
  if (check.action === 'reject') {
    message.error(check.reason);
    return null;
  }
  if (check.action === 'reuse' && check.match) {
    message.warning(check.reason);
    return check.match;
  }
  const m: Metric = {
    ...input,
    id: `m-${Date.now()}`,
    projectId: state.currentProjectId,
    changelog: [{ version: input.version, date: new Date().toISOString().slice(0, 10), change: '初始创建' }],
    score: 0,
    favorites: 0,
  };
  state.metrics.push(m);
  persist();
  message.success(`指标「${m.name}」已${m.status === 'published' ? '发布' : '保存为草稿'}`);
  return m;
}

export function favoriteMetric(id: string) {
  const m = state.metrics.find((x) => x.id === id);
  if (!m) return;
  m.favorites += 1;
  persist();
}

export function rateMetric(id: string, score: number) {
  const m = state.metrics.find((x) => x.id === id);
  if (!m) return;
  m.score = Number(((m.score * m.favorites + score) / (m.favorites + 1)).toFixed(1));
  persist();
}

export function refreshClusters() {
  if (!state.currentProjectId) return;
  const logs = projectLogs.value;
  const clusters = clusterLogs(logs);
  state.clusters = state.clusters.filter((c) => c.projectId !== state.currentProjectId).concat(clusters);
  const recs = clusters.map((c) => scoreCluster(c, projectTables.value));
  const old = projectRecs.value;
  for (const rec of recs) {
    const prev = old.find((r) => r.clusterId === rec.clusterId);
    if (prev) rec.status = prev.status, rec.grayPercent = prev.grayPercent;
  }
  state.recs = state.recs.filter((r) => r.projectId !== state.currentProjectId).concat(recs);
  persist();
  message.success('已按 SQL 指纹重算查询簇与物化推荐');
}

export function decideRec(id: string, status: 'approved' | 'rejected') {
  const rec = state.recs.find((r) => r.id === id);
  if (!rec) return;
  rec.status = status;
  persist();
  message.success(status === 'approved' ? '已进入灰度队列' : '已拒绝该推荐');
}

export function setGray(id: string, percent: number) {
  const rec = state.recs.find((r) => r.id === id);
  if (!rec || rec.status === 'rejected') return;
  rec.grayPercent = percent;
  rec.status = percent >= 100 ? 'online' : 'gray';
  if (percent >= 100 && !state.tables.some((t) => t.name === rec.targetTable)) {
    const dims = projectClusters.value.find((c) => c.id === rec.clusterId)?.dimensions ?? ['dt'];
    const measures = projectClusters.value.find((c) => c.id === rec.clusterId)?.measures ?? [];
    state.tables.push({
      id: `tbl-${rec.id}`,
      projectId: rec.projectId,
      layer: 'DWS',
      name: rec.targetTable,
      comment: '查询簇沉淀物化表',
      domain: 'TRD',
      period: 'di',
      partition: 'dt',
      status: 'published',
      columns: [
        ...dims.map((d) => ({ name: d, type: 'STRING', comment: d })),
        ...measures.map((m) => ({
          name: m.alias,
          type: m.agg.includes('SUM') ? 'DECIMAL(18,2)' : 'BIGINT',
          comment: m.alias,
        })),
      ],
    });
    state.jobs.push({
      id: `job-${rec.targetTable}`,
      projectId: rec.projectId,
      name: `${rec.targetTable}.materialize`,
      type: 'materialize',
      engine: 'Spark SQL',
      dependsOn: ['job-etl-dwd'],
      status: 'success',
      lastRun: new Date().toISOString().slice(0, 16).replace('T', ' '),
      table: rec.targetTable,
    });
  }
  persist();
}

export function addQueryFromFactory(cfg: FactoryConfig) {
  if (!state.currentProjectId) return;
  state.queryLogs.unshift({
    id: `q_${Date.now()}`,
    projectId: state.currentProjectId,
    userId: 'u_123',
    timestamp: new Date().toISOString().slice(0, 19).replace('T', ' '),
    datasource: cfg.table,
    dimensions: cfg.dimensions,
    measures: cfg.measures,
    filters: cfg.filters,
    sqlFingerprint: `SELECT ${cfg.dimensions.join(',')},${cfg.measures.map((m) => `${m.agg}(${m.field})`).join(',')} FROM ${cfg.table} WHERE dt>=? GROUP BY ${cfg.dimensions.join(',')}`,
    executionTimeMs: 180 + Math.round(Math.random() * 800),
    scanRows: cfg.layer === 'DWD' ? 80000000 : 2000000,
    costScore: cfg.layer === 'DWD' ? 7.2 : 1.4,
  });
  persist();
}

export function addServeFolder(input: { scope: ServeScope; name: string }): ServeFolder | null {
  if (!state.currentProjectId || !input.name.trim()) return null;
  if (input.scope === 'public') {
    message.error('公共分类请走规范/口径管理，个人不能直接新建');
    return null;
  }
  const row: ServeFolder = {
    id: `sf-${Date.now()}`,
    projectId: state.currentProjectId,
    scope: input.scope,
    name: input.name.trim(),
    owner: input.scope === 'personal' ? state.currentUser : undefined,
  };
  state.serveFolders.push(row);
  persist();
  message.success(`目录「${row.name}」已创建`);
  return row;
}

export function addServeMetric(input: {
  name: string;
  definition: string;
  calculationLogic: string;
  sourceTable: string;
  folderId: string;
  measure?: string;
  aggregation?: string;
  unit?: string;
  dimensions?: string[];
}): ServeMetric | null {
  if (!state.currentProjectId) return null;
  const folder = state.serveFolders.find((f) => f.id === input.folderId);
  if (!folder || folder.scope !== 'personal' || folder.owner !== state.currentUser) {
    message.error('只能在个人空间的目录下新建指标');
    return null;
  }
  if (!input.name.trim() || !input.calculationLogic.trim()) {
    message.warning('名称和计算逻辑必填');
    return null;
  }
  const row: ServeMetric = {
    id: `sm-${Date.now()}`,
    projectId: state.currentProjectId,
    name: input.name.trim(),
    definition: input.definition.trim(),
    calculationLogic: input.calculationLogic.trim(),
    sourceTable: input.sourceTable,
    dimensions: input.dimensions ?? ['dt'],
    measure: input.measure ?? '',
    aggregation: input.aggregation ?? 'SUM',
    unit: input.unit ?? '',
    owner: state.currentUser,
    scope: 'personal',
    folderId: input.folderId,
    status: 'draft',
  };
  state.serveMetrics.push(row);
  persist();
  message.success(`个人指标「${row.name}」已保存`);
  return row;
}

export function copyServeMetricToPersonal(id: string, folderId: string): ServeMetric | null {
  const src = state.serveMetrics.find((m) => m.id === id);
  const folder = state.serveFolders.find((f) => f.id === folderId);
  if (!src || !folder || !state.currentProjectId) return null;
  if (folder.scope !== 'personal' || folder.owner !== state.currentUser) {
    message.error('请选择自己的个人目录');
    return null;
  }
  const row: ServeMetric = {
    ...src,
    id: `sm-${Date.now()}`,
    projectId: state.currentProjectId,
    owner: state.currentUser,
    scope: 'personal',
    folderId,
    status: 'draft',
    clonedFrom: src.id,
    name: src.name,
  };
  state.serveMetrics.push(row);
  persist();
  message.success(`已复制到个人空间：${row.name}`);
  return row;
}

export function publishServeMetric(id: string, target: 'business' | 'public', folderId: string): ServeMetric | null {
  const src = state.serveMetrics.find((m) => m.id === id);
  if (!src || !state.currentProjectId) return null;
  if (src.scope !== 'personal' || src.owner !== state.currentUser) {
    message.error('只能发布自己的个人指标');
    return null;
  }
  const folder = state.serveFolders.find((f) => f.id === folderId && f.scope === target);
  if (!folder) {
    message.error('请选择目标目录');
    return null;
  }
  if (target === 'public') {
    const clash = state.serveMetrics.find(
      (m) =>
        m.projectId === state.currentProjectId &&
        m.scope === 'public' &&
        m.status === 'published' &&
        m.name === src.name
    );
    if (clash) {
      message.error(`公共指标已存在同名「${src.name}」，禁止覆盖公司口径`);
      return null;
    }
  }
  const row: ServeMetric = {
    ...src,
    id: `sm-${Date.now()}`,
    scope: target,
    folderId,
    status: target === 'public' ? 'review' : 'published',
    clonedFrom: src.id,
  };
  src.status = 'published';
  state.serveMetrics.push(row);
  persist();
  message.success(target === 'public' ? '已提交公共口径审核，通过前不会出现在市场货架' : `已发布到业务空间「${folder.name}」`);
  return row;
}

export function approveServeMetric(id: string) {
  const m = state.serveMetrics.find((x) => x.id === id);
  if (!m || m.scope !== 'public' || m.status !== 'review') return;
  m.status = 'published';
  persist();
  message.success(`「${m.name}」已通过，进入公共指标`);
}

export function rejectServeMetric(id: string) {
  const m = state.serveMetrics.find((x) => x.id === id);
  if (!m || m.status !== 'review') return;
  const home = state.serveFolders.find((f) => f.scope === 'personal' && f.owner === m.owner);
  m.status = 'draft';
  m.scope = 'personal';
  if (home) m.folderId = home.id;
  persist();
  message.info(`「${m.name}」已打回个人空间`);
}

export function resetDemo() {
  const fresh = createSeed();
  Object.assign(state, fresh);
  persist();
  try {
    const drop: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k?.startsWith('dw-ai.specChat.')) drop.push(k);
    }
    drop.forEach((k) => localStorage.removeItem(k));
  } catch {
    /* ignore */
  }
  message.success('已重置为演示数据');
}
