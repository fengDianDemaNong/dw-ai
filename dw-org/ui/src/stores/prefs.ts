import { reactive, readonly } from 'vue';
import type { RouteLocationMatched } from 'vue-router';
import { api, useRemoteApi } from '../api/client';
import { isMultiTenant } from '../config/runtime';

export type ThemeId = 'cyan' | 'dark' | 'blue' | 'green' | 'orange';
export type MenuPos = 'drawer' | 'left' | 'top';
export type MenuColorId = 'ink' | 'light' | 'cyan' | 'blue' | 'green' | 'orange';
/**
 * 外观的作用域 —— 一个壳一份，互不影响：
 * `platform` 平台后台 / `workbench` 租户工作台 / `project` 租户项目壳
 * （一个租户下的所有项目共用一份）。
 *
 * <p>这三个值要落进后端 `appearance_prefs.scope`。服务端还认一个**第四种**取值
 * `'tenant'`：那是拆分之前的老口径，只有 dw-model 前端还在用（它调这个端点时不带
 * `shell` 参数），org 自己不再读写它。
 */
export type AppearanceScope = 'platform' | 'workbench' | 'project';

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

/**
 * 租户侧两套外观的出厂值，与后端 `TenantAdminService.defaultAppearance` 一一对应 ——
 * 改这里就得同步改那边，否则「库里没行时」前后端会给出不同的观感。
 *
 * <p>`DEFAULT_APPEARANCE` 是**工作台**那套：左侧栏。工作台壳没有项目壳那条顶栏，
 * 而抽屉是 `absolute` + `top:100%` 挂在顶栏下面的 —— 没有锚点它就没有意义。
 * 项目那套默认收起（顶栏 + 抽屉 + 快捷栏）。平台后台一直是左侧。
 */
export const DEFAULT_APPEARANCE: Appearance = { theme: 'cyan', menuPos: 'left', menuColor: 'ink' };
export const DEFAULT_PROJECT_APPEARANCE: Appearance = { theme: 'cyan', menuPos: 'drawer', menuColor: 'ink' };
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
  workbench: { ...DEFAULT_APPEARANCE },
  project: { ...DEFAULT_PROJECT_APPEARANCE },
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

/**
 * 取某个壳的外观。
 *
 * <p>`_tenantId` 仍然只是个形参：`state` 是全局单例，同一浏览器会话里换租户是**覆盖式**
 * 的（bootstrap / 选租户时会重新 load 一次），并不是按租户各存一份。这是既有局限，
 * 别把这个签名读成「按租户隔离」。
 */
export function appearanceOf(scope: AppearanceScope, _tenantId?: string | null): Appearance {
  if (scope === 'platform') return state.platform;
  return scope === 'project' ? state.project : state.workbench;
}

/**
 * 当前路由属于哪个壳的外观作用域。
 *
 * <p>判据与 `layouts/SystemLayout.vue` 取菜单树的那两支**同源**（都看路由的 `meta.shell`）。
 * 特意不按路径字符串猜：`route.path.includes('/platform')` 那种写法在产品子路径里出现
 * `/platform` 时（`/org/embed/x/platform/y`）会把嵌入页误判成平台后台。
 */
export function scopeOfRoute(matched: readonly RouteLocationMatched[]): AppearanceScope {
  if (matched.some((r) => r.meta.shell === 'admin')) return 'platform';
  if (matched.some((r) => r.meta.shell === 'project')) return 'project';
  return 'workbench';
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

/**
 * 拉这个租户的两套外观（工作台 + 项目）。
 *
 * <p>**必须两套一起拉**，不能按当前落点惰性加载：`main.ts` 是在 `createApp()` 之前
 * `await bootstrapRemote()` 的，而那会儿还不知道用户会落在哪个壳（`currentProjectId`
 * 是在它之后才从 sessionStorage 恢复的）—— 惰性加载会让直连刷新 `/org/project/{code}/...`
 * 时先闪一下出厂外观。
 */
export async function loadTenantAppearance(tenantId: string | null | undefined) {
  if (!useRemoteApi() || !tenantId) return;
  // 并行发、落在两个不同的键上，彼此不会互相覆盖；一个失败也不影响另一个。
  const [wb, pj] = await Promise.all([
    api.org.appearance(tenantId, 'workbench').catch(() => null),
    api.org.appearance(tenantId, 'project').catch(() => null),
  ]);
  if (wb) state.workbench = parseAppearance(wb, DEFAULT_APPEARANCE);
  if (pj) state.project = parseAppearance(pj, DEFAULT_PROJECT_APPEARANCE);
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
  if (!useRemoteApi()) return;
  if (scope === 'platform') {
    await api.platform.putAppearance(state.platform);
    return;
  }
  if (!tenantId) return;
  // workbench / project 各写各的那一行 —— scope 的取值就是 shell 参数的取值。
  await api.org.putAppearance(tenantId, appearanceOf(scope), scope);
}

export async function setTheme(scope: AppearanceScope, id: ThemeId, tenantId?: string | null) {
  appearanceOf(scope).theme = id;
  applyTheme(id);
  await persist(scope, tenantId);
}

export async function setMenuPos(scope: AppearanceScope, pos: MenuPos, tenantId?: string | null) {
  // `drawer` 是项目壳专有的（工作台/平台没有那条顶栏，抽屉没有锚点）。与后端
  // `TenantAdminService.normalizeMenuPos` 同一口径，两边都归一，别让非法组合落库 ——
  // 一旦库里存下它，设置页的菜单风格 picker 会一项都不高亮。
  appearanceOf(scope).menuPos = scope !== 'project' && pos === 'drawer' ? 'left' : pos;
  await persist(scope, tenantId);
}

export async function setMenuColor(scope: AppearanceScope, id: MenuColorId, tenantId?: string | null) {
  appearanceOf(scope).menuColor = id;
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
