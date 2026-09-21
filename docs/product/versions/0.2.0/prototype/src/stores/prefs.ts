import { reactive, readonly } from 'vue';
import { DEFAULT_AI_PROMPTS } from '../config/aiPrompts';
import type { AiPromptSlot } from '../types';

export type ThemeId = 'cyan' | 'dark' | 'blue' | 'green' | 'orange';
export type MenuPos = 'drawer' | 'left' | 'top';
export type MenuColorId = 'ink' | 'light' | 'cyan' | 'blue' | 'green' | 'orange';
export type AppearanceScope = 'platform' | 'tenant';

export interface Appearance {
  theme: ThemeId;
  menuPos: MenuPos;
  menuColor: MenuColorId;
}

export const MENU_STYLE_OPTIONS: { id: MenuPos; label: string; desc: string }[] = [
  { id: 'drawer', label: '收起', desc: '点左上角弹出全部主菜单，进入后左侧只留当前主菜单的快捷项' },
  { id: 'left', label: '左侧', desc: '全部主菜单常驻在左侧栏' },
  { id: 'top', label: '顶部', desc: '全部主菜单排在顶部，悬停展开子项' },
];

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

export const MENU_COLOR_OPTIONS: { id: MenuColorId; label: string; color: string }[] = [
  { id: 'ink', label: '深色', color: '#0b1220' },
  { id: 'light', label: '浅色', color: '#ffffff' },
  { id: 'cyan', label: '青石', color: '#0e7490' },
  { id: 'blue', label: '拂晓', color: '#1677ff' },
  { id: 'green', label: '竹青', color: '#059669' },
  { id: 'orange', label: '赤橙', color: '#ea580c' },
];

export const DEFAULT_APPEARANCE: Appearance = { theme: 'cyan', menuPos: 'drawer', menuColor: 'ink' };

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

const KEY = 'dw-ai.proto.0.2.0.prefs';

function asTheme(v: unknown): ThemeId {
  return THEME_OPTIONS.some((t) => t.id === v) ? (v as ThemeId) : 'cyan';
}

function asPos(v: unknown): MenuPos {
  if (v === 'top' || v === 'left' || v === 'drawer') return v;
  return 'drawer';
}

function readPos(raw: { menuStyle?: unknown; menuPos?: unknown }, fallback: MenuPos): MenuPos {
  if (raw.menuStyle === 'top' || raw.menuStyle === 'left' || raw.menuStyle === 'drawer') return raw.menuStyle;
  if (raw.menuPos === 'top') return 'top';
  if (raw.menuPos === 'drawer') return 'drawer';
  return fallback;
}

function asMenuColor(v: unknown): MenuColorId {
  return MENU_COLOR_OPTIONS.some((c) => c.id === v) ? (v as MenuColorId) : 'ink';
}

function asAppearance(raw: unknown, fallback: Appearance): Appearance {
  if (!raw || typeof raw !== 'object') return { ...fallback };
  const p = raw as { theme?: unknown; menuPos?: unknown; menuStyle?: unknown; menuColor?: unknown };
  return {
    theme: asTheme(p.theme ?? fallback.theme),
    menuPos: readPos(p, fallback.menuPos),
    menuColor: asMenuColor(p.menuColor ?? fallback.menuColor),
  };
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
        promptsByTenant: {} as Record<string, Partial<Record<AiPromptSlot, string>>>,
      };
    }
    const p = JSON.parse(raw) as {
      platform?: unknown;
      tenantDefault?: unknown;
      byTenant?: Record<string, unknown>;
      llmByTenant?: Record<string, unknown>;
      promptsByTenant?: Record<string, unknown>;
      theme?: unknown;
      platformMenuPos?: unknown;
      tenantMenuPos?: unknown;
    };
    const migratedTenant = {
      theme: asTheme(p.theme),
      menuPos: p.tenantMenuPos === 'top' ? 'top' : DEFAULT_APPEARANCE.menuPos,
      menuColor: DEFAULT_APPEARANCE.menuColor,
    };
    return {
      platform: asAppearance(p.platform, {
        theme: 'cyan',
        menuPos: p.platformMenuPos === 'top' ? 'top' : DEFAULT_APPEARANCE.menuPos,
        menuColor: DEFAULT_APPEARANCE.menuColor,
      }),
      tenantDefault: asAppearance(p.tenantDefault, migratedTenant),
      byTenant: Object.fromEntries(
        Object.entries(p.byTenant ?? {}).map(([id, v]) => [id, asAppearance(v, migratedTenant)])
      ) as Record<string, Appearance>,
      llmByTenant: Object.fromEntries(
        Object.entries(p.llmByTenant ?? {}).map(([id, v]) => [id, asLlm(v)])
      ) as Record<string, LlmConfig>,
      promptsByTenant: asPromptMap(p.promptsByTenant),
    };
  } catch {
    return {
      platform: { ...DEFAULT_APPEARANCE },
      tenantDefault: { ...DEFAULT_APPEARANCE },
      byTenant: {} as Record<string, Appearance>,
      llmByTenant: {} as Record<string, LlmConfig>,
      promptsByTenant: {} as Record<string, Partial<Record<AiPromptSlot, string>>>,
    };
  }
}

function asPromptMap(raw: unknown): Record<string, Partial<Record<AiPromptSlot, string>>> {
  if (!raw || typeof raw !== 'object') return {};
  const out: Record<string, Partial<Record<AiPromptSlot, string>>> = {};
  for (const [tid, slots] of Object.entries(raw as Record<string, unknown>)) {
    if (!slots || typeof slots !== 'object') continue;
    const next: Partial<Record<AiPromptSlot, string>> = {};
    for (const [slot, body] of Object.entries(slots as Record<string, unknown>)) {
      if (slot in DEFAULT_AI_PROMPTS && typeof body === 'string') {
        next[slot as AiPromptSlot] = body;
      }
    }
    out[tid] = next;
  }
  return out;
}

const state = reactive(load());

function stamp(a: Appearance) {
  return { theme: a.theme, menuPos: a.menuPos, menuStyle: a.menuPos, menuColor: a.menuColor };
}

function persist() {
  localStorage.setItem(
    KEY,
    JSON.stringify({
      platform: stamp(state.platform),
      tenantDefault: stamp(state.tenantDefault),
      byTenant: Object.fromEntries(Object.entries(state.byTenant).map(([id, v]) => [id, stamp(v)])),
      llmByTenant: state.llmByTenant,
      promptsByTenant: state.promptsByTenant,
    })
  );
}

export function applyTheme(id: ThemeId) {
  document.documentElement.dataset.theme = id;
}

export function applyMenuColor(id: MenuColorId) {
  document.documentElement.dataset.menu = id;
}

applyTheme(DEFAULT_APPEARANCE.theme);
applyMenuColor(DEFAULT_APPEARANCE.menuColor);

export const prefs = readonly(state);

export function appearanceOf(scope: AppearanceScope, tenantId?: string | null): Appearance {
  const raw =
    scope === 'platform' ? state.platform : tenantId && state.byTenant[tenantId] ? state.byTenant[tenantId] : state.tenantDefault;
  return { ...DEFAULT_APPEARANCE, ...raw };
}

function writeTenant(tenantId: string, next: Appearance) {
  state.byTenant[tenantId] = next;
  state.tenantDefault = { ...next };
}

function patchAppearance(scope: AppearanceScope, tenantId: string | null | undefined, patch: Partial<Appearance>) {
  if (scope === 'platform') {
    Object.assign(state.platform, patch);
  } else if (tenantId) {
    writeTenant(tenantId, { ...appearanceOf('tenant', tenantId), ...patch });
  } else {
    Object.assign(state.tenantDefault, patch);
  }
  persist();
}

export function setTheme(scope: AppearanceScope, id: ThemeId, tenantId?: string | null) {
  patchAppearance(scope, tenantId, { theme: id });
}

export function setMenuPos(scope: AppearanceScope, pos: MenuPos, tenantId?: string | null) {
  patchAppearance(scope, tenantId, { menuPos: pos });
}

export function setMenuColor(scope: AppearanceScope, id: MenuColorId, tenantId?: string | null) {
  patchAppearance(scope, tenantId, { menuColor: id });
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

export function promptOverrideOf(tenantId: string | null | undefined, slot: AiPromptSlot): string | undefined {
  if (!tenantId) return undefined;
  const body = state.promptsByTenant[tenantId]?.[slot];
  return typeof body === 'string' ? body : undefined;
}

export function effectivePrompt(tenantId: string | null | undefined, slot: AiPromptSlot): string {
  return promptOverrideOf(tenantId, slot) ?? DEFAULT_AI_PROMPTS[slot];
}

export function setPromptOverride(tenantId: string, slot: AiPromptSlot, body: string) {
  const trimmed = body.trim();
  const cur = { ...(state.promptsByTenant[tenantId] ?? {}) };
  if (!trimmed || trimmed === DEFAULT_AI_PROMPTS[slot]) {
    delete cur[slot];
  } else {
    cur[slot] = body;
  }
  state.promptsByTenant[tenantId] = cur;
  persist();
}

export function resetPromptSlot(tenantId: string, slot: AiPromptSlot) {
  const cur = { ...(state.promptsByTenant[tenantId] ?? {}) };
  delete cur[slot];
  state.promptsByTenant[tenantId] = cur;
  persist();
}
