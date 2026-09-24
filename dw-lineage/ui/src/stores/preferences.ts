import { reactive, watch } from 'vue';
import { tenantState } from './tenant';

/**
 * 界面偏好。
 *
 * 改版前主题、水印、缩略图都藏在 SQL 解析页左上角那个齿轮里，而且<b>只存在内存中</b>：
 * `handleSaveSettings` 只是把值 emit 回父组件的 ref，刷新一次就全没了。
 * 现在收成两份持久化的偏好，`stores/preferences` 统一管，各处读同一份。
 *
 * <h2>为什么分成两份</h2>
 *
 * <p>这两类东西的<b>归属</b>不一样，混在一个键里会让人以为它们同命：
 *
 * <ul>
 *   <li>{@link preferences}：主题、菜单栏位置。跟人走，跟项目无关 ——
 *       换到哪个项目，屏幕都是那个亮度。</li>
 *   <li>{@link mapPreferences}：血缘图的水印与缩略图。<b>按项目各存一份</b> ——
 *       盖的是当前项目的血缘图，导出的图片也只属于它。</li>
 * </ul>
 *
 * <p>界面上也照这个分：前者在「基本信息」（工作台），后者在「数据地图设置」（项目）。
 */

// ---------------------------------------------------------------------------
// 全局外观偏好：主题、菜单栏位置
// ---------------------------------------------------------------------------

const KEY = 'sql-tools.preferences';

/** Monaco 的主题名，同时也作为 body 上的类名前缀，两边必须一致。 */
export type ThemeName = 'vs-light' | 'vs-dark' | 'vs-eyecare';

/** 菜单栏位置。侧边给画布留高度，顶部给画布留宽度，按屏幕比例选。 */
export type NavPosition = 'side' | 'top';

export interface Preferences {
  theme: ThemeName;
  navPosition: NavPosition;
}

const DEFAULTS: Preferences = {
  theme: 'vs-light',
  navPosition: 'side',
};

function load(): Preferences {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...DEFAULTS };
    // 只认识的键才接受：旧版本写进去的、或者手改坏的键不该污染当前结构。
    // 上一版这里还有 watermark / showMinimap，它们搬去了 MAP_KEY（见下），
    // 于是老值会被这一句自然丢掉 —— 刻意不搬，理由见 `loadMapPreferences`。
    const saved = JSON.parse(raw) as Partial<Preferences>;
    return {
      theme: saved.theme ?? DEFAULTS.theme,
      navPosition: saved.navPosition ?? DEFAULTS.navPosition,
    };
  } catch {
    // localStorage 里是坏 JSON 时退回默认值，不要让整个应用起不来
    return { ...DEFAULTS };
  }
}

export const preferences = reactive<Preferences>(load());

watch(
  () => ({ ...preferences }),
  (value) => localStorage.setItem(KEY, JSON.stringify(value)),
  { deep: true }
);

/**
 * 主题对应的 CSS 类。
 *
 * <b>只挂在 SQL 解析页的根节点上，不挂 body。</b>
 * `index.css` 里那套 `.theme-dark` 规则是照着解析页的 DOM 写的
 * （`header` / `.splitPane` / `.pane1` / `.collapse-btn` …），挂到 body 上会
 * 命中新骨架里同名的元素 —— 侧边栏的收起按钮、顶栏都会变黑，而卡片和表格
 * 仍是白的，得到一个半深不深的界面。
 *
 * 真正的全站深色需要给 antd 配 darkAlgorithm、再把各页 scoped 样式里写死的
 * 颜色换成变量，那是另一件事。
 */
export const THEME_CLASS: Record<ThemeName, string> = {
  'vs-light': 'theme-light',
  'vs-dark': 'theme-dark',
  'vs-eyecare': 'theme-eyecare',
};

/**
 * 只重置外观项（主题、菜单栏位置）。「设置 › 基本信息」页用。
 *
 * <p>刻意不写成一个「重置全部」——水印与缩略图挪去了项目级的「数据地图设置」页
 * （`pages/settings/map.vue`），在基本信息页按一下按钮把另一个页面上的、屏幕上
 * 看不见的项悄悄清掉，用户只会觉得「我没动过它，怎么就没了」。
 */
export function resetAppearance() {
  preferences.theme = DEFAULTS.theme;
  preferences.navPosition = DEFAULTS.navPosition;
}

// ---------------------------------------------------------------------------
// 血缘图偏好：水印、缩略图。按项目各存一份
// ---------------------------------------------------------------------------

const MAP_KEY = 'sql-tools.mapPrefs';

export interface MapPreferences {
  /** 血缘图上的文字水印，空串表示不加 */
  watermark: string;
  /** 血缘图右下角的缩略图 */
  showMinimap: boolean;
}

const MAP_DEFAULTS: MapPreferences = {
  watermark: '',
  showMinimap: false,
};

/** 每个项目一份。键是 projectId 的字符串形式 —— JSON 的键只能是字符串。 */
type MapPrefsByProject = Record<string, MapPreferences>;

/**
 * 读盘。
 *
 * <p>**不迁移上一版的全局水印**，虽然技术上做得到（旧值是全局一份，没记自己属于
 * 哪个项目，只能认给某一个，或当成所有项目的缺省值）。不做的理由：认给谁都是瞎猜，
 * 而当缺省值会让「重置默认」按完又冒出旧值来 —— 一段只在升级那一刻跑一次、
 * 之后永远在暗中生效的兼容逻辑，比让用户重设一次水印贵得多。
 */
function loadMapPreferences(): MapPrefsByProject {
  try {
    const raw = localStorage.getItem(MAP_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, Partial<MapPreferences>>;
    const out: MapPrefsByProject = {};
    for (const [key, value] of Object.entries(parsed ?? {})) {
      // 只认识的键才接受，同 `load()`：坏掉的条目丢掉，别让它污染整份
      if (!/^\d+$/.test(key)) continue;
      out[key] = {
        watermark: typeof value?.watermark === 'string' ? value.watermark : MAP_DEFAULTS.watermark,
        showMinimap: !!value?.showMinimap,
      };
    }
    return out;
  } catch {
    return {};
  }
}

const mapByProject = reactive<MapPrefsByProject>(loadMapPreferences());

watch(
  mapByProject,
  (value) => localStorage.setItem(MAP_KEY, JSON.stringify(value)),
  { deep: true }
);

/** 某个项目的设置。没单独设过就是默认值。 */
function entryOf(projectId: number): MapPreferences {
  return mapByProject[String(projectId)] ?? MAP_DEFAULTS;
}

function patch(projectId: number, value: Partial<MapPreferences>): void {
  mapByProject[String(projectId)] = { ...entryOf(projectId), ...value };
}

/**
 * 当前项目的血缘图偏好。
 *
 * <p>写成一对访问器而不是一个快照对象，是为了让 `tenantState.projectId`
 * <b>每次读取时才求值</b>。multi 模式下当前项目是 `#boot=` 在 main.ts 里才落下来的，
 * 比本模块初始化晚；要是在模块顶层就把值读出来，那边会永远慢一步。
 *
 * <p>响应式没问题：getter 内部读的是 `tenantState` 与 `mapByProject`，
 * 两个都是 reactive，模板里的追踪照常建立。
 */
export const mapPreferences = {
  get watermark(): string {
    return entryOf(tenantState.projectId).watermark;
  },
  set watermark(value: string) {
    patch(tenantState.projectId, { watermark: value });
  },
  get showMinimap(): boolean {
    return entryOf(tenantState.projectId).showMinimap;
  },
  set showMinimap(value: boolean) {
    patch(tenantState.projectId, { showMinimap: value });
  },
};

/**
 * 只重置**当前项目**的血缘图设置 —— 别的项目不该被这一下殃及。
 *
 * <p>写回默认值而不是删掉条目：删掉会让它回落到「没设过」的状态，
 * 而这两者在实现上等价、在语义上不等价，显式写一份更经得起将来加缺省值。
 */
export function resetMapPreferences(): void {
  mapByProject[String(tenantState.projectId)] = { ...MAP_DEFAULTS };
}
