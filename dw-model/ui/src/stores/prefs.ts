import { reactive, readonly } from 'vue';
import { api, useRemoteApi } from '../api/client';
import { hostAppearance, isEmbed } from '../config/product';
import { isMultiTenant } from '../config/runtime';

export type ThemeId = 'cyan' | 'dark' | 'blue' | 'green' | 'orange';
export type MenuPos = 'left' | 'top';
export type AppearanceScope = 'platform' | 'tenant';

export interface Appearance {
  theme: ThemeId;
  menuPos: MenuPos;
}

export const THEME_OPTIONS: { id: ThemeId; label: string; desc: string; primary: string }[] = [
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

const state = reactive({
  platform: { ...DEFAULT_APPEARANCE },
  tenant: { ...DEFAULT_APPEARANCE },
  llm: { ...DEFAULT_LLM },
});

export function applyTheme(id: ThemeId) {
  document.documentElement.dataset.theme = id;
}

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
    const a = await api.platform.appearance();
    state.platform = { theme: (a.theme as ThemeId) || 'cyan', menuPos: a.menuPos === 'top' ? 'top' : 'left' };
  } catch {
    /* ignore */
  }
}

export async function loadTenantAppearance(tenantId: string | null | undefined) {
  // 嵌在门户里时外观**跟着壳变**：页面画在宿主的框里，两套主题不一致会拼成两种颜色。
  // 宿主那套随 `#boot=` 推过来（见 `config/product.ts`），比本产品自己那份
  // （独立打开时留下的）权威 —— 所以嵌入态下先看它，有就不问接口了。
  const host = hostAppearance();
  if (isEmbed() && host) {
    state.tenant = { theme: (host.theme as ThemeId) || 'cyan', menuPos: host.menuPos === 'top' ? 'top' : 'left' };
    return;
  }
  if (!useRemoteApi() || !tenantId) return;
  try {
    const a = await api.org.appearance(tenantId);
    state.tenant = { theme: (a.theme as ThemeId) || 'cyan', menuPos: a.menuPos === 'top' ? 'top' : 'left' };
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

export async function setTheme(scope: AppearanceScope, id: ThemeId, tenantId?: string | null) {
  if (scope === 'platform') {
    state.platform.theme = id;
    if (useRemoteApi()) await api.platform.putAppearance(state.platform);
  } else {
    state.tenant.theme = id;
    if (useRemoteApi() && tenantId) await api.org.putAppearance(tenantId, state.tenant);
  }
  applyTheme(id);
}

export async function setMenuPos(scope: AppearanceScope, pos: MenuPos, tenantId?: string | null) {
  if (scope === 'platform') {
    state.platform.menuPos = pos;
    if (useRemoteApi()) await api.platform.putAppearance(state.platform);
  } else {
    state.tenant.menuPos = pos;
    if (useRemoteApi() && tenantId) await api.org.putAppearance(tenantId, state.tenant);
  }
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
