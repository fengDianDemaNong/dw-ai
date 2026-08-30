import { reactive, readonly } from 'vue';

export type ThemeId = 'cyan' | 'dark' | 'blue' | 'green' | 'orange';
export type MenuPos = 'left' | 'top';
export type AppearanceScope = 'platform' | 'tenant';

export interface Appearance {
  theme: ThemeId;
  menuPos: MenuPos;
}

export interface ThemeOption {
  id: ThemeId;
  label: string;
  desc: string;
  primary: string;
}

export const THEME_OPTIONS: ThemeOption[] = [
  { id: 'cyan', label: '青石', desc: '默认', primary: '#0e7490' },
  { id: 'dark', label: '深夜', desc: '深色界面', primary: '#22d3ee' },
  { id: 'blue', label: '拂晓', desc: '蓝色', primary: '#1677ff' },
  { id: 'green', label: '竹青', desc: '绿色', primary: '#059669' },
  { id: 'orange', label: '赤橙', desc: '暖色', primary: '#ea580c' },
];

export const DEFAULT_APPEARANCE: Appearance = { theme: 'cyan', menuPos: 'left' };

export type LlmProvider = 'openai' | 'dashscope' | 'zhipu' | 'deepseek' | 'custom';

export interface LlmConfig {
  enabled: boolean;
  provider: LlmProvider;
  baseUrl: string;
  apiKey: string;
  model: string;
}

export const LLM_PROVIDERS: { value: LlmProvider; label: string; baseUrl: string; model: string }[] = [
  { value: 'openai', label: 'OpenAI 兼容', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o' },
  { value: 'dashscope', label: '通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { value: 'zhipu', label: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4' },
  { value: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat' },
  { value: 'custom', label: '自定义', baseUrl: '', model: '' },
];

export const DEFAULT_LLM: LlmConfig = {
  enabled: false,
  provider: 'openai',
  baseUrl: LLM_PROVIDERS[0].baseUrl,
  apiKey: '',
  model: LLM_PROVIDERS[0].model,
};

const KEY = 'dw-ai.proto.0.1.1.prefs';

function asTheme(v: unknown): ThemeId {
  return THEME_OPTIONS.some((t) => t.id === v) ? (v as ThemeId) : 'cyan';
}

function asPos(v: unknown): MenuPos {
  return v === 'top' ? 'top' : 'left';
}

function asAppearance(raw: unknown, fallback: Appearance): Appearance {
  if (!raw || typeof raw !== 'object') return { ...fallback };
  const p = raw as { theme?: unknown; menuPos?: unknown };
  return { theme: asTheme(p.theme ?? fallback.theme), menuPos: asPos(p.menuPos ?? fallback.menuPos) };
}

function asLlm(raw: unknown): LlmConfig {
  if (!raw || typeof raw !== 'object') return { ...DEFAULT_LLM };
  const p = raw as Partial<LlmConfig>;
  const provider = LLM_PROVIDERS.some((x) => x.value === p.provider) ? (p.provider as LlmProvider) : 'openai';
  const preset = LLM_PROVIDERS.find((x) => x.value === provider) ?? LLM_PROVIDERS[0];
  return {
    enabled: Boolean(p.enabled),
    provider,
    baseUrl: typeof p.baseUrl === 'string' ? p.baseUrl : preset.baseUrl,
    apiKey: typeof p.apiKey === 'string' ? p.apiKey : '',
    model: typeof p.model === 'string' ? p.model : preset.model,
  };
}

function load() {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) {
      return {
        platform: { ...DEFAULT_APPEARANCE },
        tenantDefault: { ...DEFAULT_APPEARANCE },
        byTenant: {} as Record<string, Appearance>,
        llmByTenant: {} as Record<string, LlmConfig>,
      };
    }
    const p = JSON.parse(raw) as {
      platform?: unknown;
      tenantDefault?: unknown;
      byTenant?: Record<string, unknown>;
      llmByTenant?: Record<string, unknown>;
      theme?: unknown;
      platformMenuPos?: unknown;
      tenantMenuPos?: unknown;
    };
    const migratedTenant = {
      theme: asTheme(p.theme),
      menuPos: asPos(p.tenantMenuPos),
    };
    return {
      platform: asAppearance(p.platform, {
        theme: 'cyan',
        menuPos: asPos(p.platformMenuPos),
      }),
      tenantDefault: asAppearance(p.tenantDefault, migratedTenant),
      byTenant: Object.fromEntries(
        Object.entries(p.byTenant ?? {}).map(([id, v]) => [id, asAppearance(v, migratedTenant)])
      ) as Record<string, Appearance>,
      llmByTenant: Object.fromEntries(
        Object.entries(p.llmByTenant ?? {}).map(([id, v]) => [id, asLlm(v)])
      ) as Record<string, LlmConfig>,
    };
  } catch {
    return {
      platform: { ...DEFAULT_APPEARANCE },
      tenantDefault: { ...DEFAULT_APPEARANCE },
      byTenant: {} as Record<string, Appearance>,
      llmByTenant: {} as Record<string, LlmConfig>,
    };
  }
}

const state = reactive(load());

function persist() {
  localStorage.setItem(
    KEY,
    JSON.stringify({
      platform: state.platform,
      tenantDefault: state.tenantDefault,
      byTenant: state.byTenant,
      llmByTenant: state.llmByTenant,
    })
  );
}

export function applyTheme(id: ThemeId) {
  document.documentElement.dataset.theme = id;
}

applyTheme(DEFAULT_APPEARANCE.theme);

export const prefs = readonly(state);

export function appearanceOf(scope: AppearanceScope, tenantId?: string | null): Appearance {
  if (scope === 'platform') return state.platform;
  if (tenantId && state.byTenant[tenantId]) return state.byTenant[tenantId];
  return state.tenantDefault;
}

function writeTenant(tenantId: string, next: Appearance) {
  state.byTenant[tenantId] = next;
  state.tenantDefault = { ...next };
}

export function setTheme(scope: AppearanceScope, id: ThemeId, tenantId?: string | null) {
  if (scope === 'platform') {
    state.platform.theme = id;
  } else if (tenantId) {
    writeTenant(tenantId, { theme: id, menuPos: appearanceOf('tenant', tenantId).menuPos });
  } else {
    state.tenantDefault.theme = id;
  }
  persist();
}

export function setMenuPos(scope: AppearanceScope, pos: MenuPos, tenantId?: string | null) {
  if (scope === 'platform') {
    state.platform.menuPos = pos;
  } else if (tenantId) {
    writeTenant(tenantId, { theme: appearanceOf('tenant', tenantId).theme, menuPos: pos });
  } else {
    state.tenantDefault.menuPos = pos;
  }
  persist();
}

export function themePrimary(id: ThemeId = DEFAULT_APPEARANCE.theme): string {
  return THEME_OPTIONS.find((t) => t.id === id)?.primary ?? '#0e7490';
}

export function llmOf(tenantId?: string | null): LlmConfig {
  if (tenantId && state.llmByTenant[tenantId]) return state.llmByTenant[tenantId];
  return { ...DEFAULT_LLM };
}

export function setLlm(tenantId: string, input: LlmConfig) {
  state.llmByTenant[tenantId] = {
    enabled: Boolean(input.enabled),
    provider: LLM_PROVIDERS.some((x) => x.value === input.provider) ? input.provider : 'openai',
    baseUrl: input.baseUrl.trim(),
    apiKey: input.apiKey,
    model: input.model.trim(),
  };
  persist();
}
