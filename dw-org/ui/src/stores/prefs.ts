import { reactive, readonly } from 'vue';
import { api, useRemoteApi } from '../api/client';
import { isMultiTenant } from '../config/runtime';

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

export const THEME_OPTIONS: { id: ThemeId; label: string; desc: string; primary: string }[] = [
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
export const DEFAULT_PLATFORM_APPEARANCE: Appearance = { theme: 'cyan', menuPos: 'left', menuColor: 'ink' };

export type LlmProvider = 'openai' | 'dashscope' | 'zhipu' | 'deepseek' | 'custom';

export interface LlmConfig {
  enabled: boolean;
  provider: LlmProvider;
  baseUrl: string;
  apiKey: string;
  model: string;
  hasKey?: boolean;
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
  hasKey: false,
};

function asTheme(v: unknown): ThemeId {
  return THEME_OPTIONS.some((t) => t.id === v) ? (v as ThemeId) : 'cyan';
}

function asPos(v: unknown, fallback: MenuPos): MenuPos {
  if (v === 'top' || v === 'left' || v === 'drawer') return v;
  return fallback;
}

function asMenuColor(v: unknown): MenuColorId {
  return MENU_COLOR_OPTIONS.some((c) => c.id === v) ? (v as MenuColorId) : 'ink';
}

function parseAppearance(
  a: { theme?: string; menuPos?: string; menuColor?: string } | null | undefined,
  fallback: Appearance
): Appearance {
  return {
    theme: asTheme(a?.theme ?? fallback.theme),
    menuPos: asPos(a?.menuPos, fallback.menuPos),
    menuColor: asMenuColor(a?.menuColor ?? fallback.menuColor),
  };
}

const state = reactive({
  platform: { ...DEFAULT_PLATFORM_APPEARANCE },
  tenant: { ...DEFAULT_APPEARANCE },
  llm: { ...DEFAULT_LLM },
});

export function applyTheme(id: ThemeId) {
  document.documentElement.dataset.theme = id;
}

export function applyMenuColor(id: MenuColorId) {
  document.documentElement.dataset.menu = id;
}

applyTheme(DEFAULT_APPEARANCE.theme);
applyMenuColor(DEFAULT_APPEARANCE.menuColor);

export const prefs = readonly(state);

export function appearanceOf(scope: AppearanceScope, _tenantId?: string | null): Appearance {
  return scope === 'platform' ? state.platform : state.tenant;
}

export function themePrimary(id: ThemeId = DEFAULT_APPEARANCE.theme): string {
  return THEME_OPTIONS.find((t) => t.id === id)?.primary ?? '#0e7490';
}

export function llmOf(_tenantId?: string | null): LlmConfig {
  return { ...state.llm };
}

export async function loadPlatformAppearance() {
  if (!useRemoteApi() || !isMultiTenant()) return;
  try {
    state.platform = parseAppearance(await api.platform.appearance(), DEFAULT_PLATFORM_APPEARANCE);
  } catch {
    /* ignore */
  }
}

export async function loadTenantAppearance(tenantId: string | null | undefined) {
  if (!useRemoteApi() || !tenantId) return;
  try {
    state.tenant = parseAppearance(await api.org.appearance(tenantId), DEFAULT_APPEARANCE);
  } catch {
    /* ignore */
  }
}

export async function loadTenantLlm(tenantId: string | null | undefined) {
  if (!useRemoteApi() || !tenantId) return;
  try {
    const l = await api.org.llm(tenantId);
    state.llm = {
      enabled: l.enabled,
      provider: (l.provider as LlmProvider) || 'openai',
      baseUrl: l.baseUrl || '',
      apiKey: '',
      model: l.model || '',
      hasKey: l.hasKey,
    };
  } catch {
    /* ignore */
  }
}

async function persist(scope: AppearanceScope, tenantId?: string | null) {
  const body = scope === 'platform' ? state.platform : state.tenant;
  if (!useRemoteApi()) return;
  if (scope === 'platform') await api.platform.putAppearance(body);
  else if (tenantId) await api.org.putAppearance(tenantId, body);
}

export async function setTheme(scope: AppearanceScope, id: ThemeId, tenantId?: string | null) {
  if (scope === 'platform') state.platform.theme = id;
  else state.tenant.theme = id;
  applyTheme(id);
  await persist(scope, tenantId);
}

export async function setMenuPos(scope: AppearanceScope, pos: MenuPos, tenantId?: string | null) {
  if (scope === 'platform') state.platform.menuPos = pos === 'drawer' ? 'left' : pos;
  else state.tenant.menuPos = pos;
  await persist(scope, tenantId);
}

export async function setMenuColor(scope: AppearanceScope, id: MenuColorId, tenantId?: string | null) {
  if (scope === 'platform') state.platform.menuColor = id;
  else state.tenant.menuColor = id;
  applyMenuColor(id);
  await persist(scope, tenantId);
}

export async function setLlm(tenantId: string, input: LlmConfig) {
  state.llm = { ...input };
  if (useRemoteApi()) {
    await api.org.putLlm(tenantId, {
      enabled: input.enabled,
      provider: input.provider,
      baseUrl: input.baseUrl,
      model: input.model,
      apiKey: input.apiKey || undefined,
    });
    await loadTenantLlm(tenantId);
  }
}
