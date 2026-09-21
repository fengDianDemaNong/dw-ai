import { reactive, watch } from 'vue';

/**
 * 界面偏好。
 *
 * 改版前主题、水印、缩略图都藏在 SQL 解析页左上角那个齿轮里，而且<b>只存在内存中</b>：
 * `handleSaveSettings` 只是把值 emit 回父组件的 ref，刷新一次就全没了。
 * 这里收成一份持久化的偏好，「设置 › 基本信息」页统一改，各处读同一份。
 */

const KEY = 'sql-tools.preferences';

/** Monaco 的主题名，同时也作为 body 上的类名前缀，两边必须一致。 */
export type ThemeName = 'vs-light' | 'vs-dark' | 'vs-eyecare';

/** 菜单栏位置。侧边给画布留高度，顶部给画布留宽度，按屏幕比例选。 */
export type NavPosition = 'side' | 'top';

export interface Preferences {
  theme: ThemeName;
  navPosition: NavPosition;
  /** 血缘图上的文字水印，空串表示不加 */
  watermark: string;
  /** 血缘图右下角的缩略图 */
  showMinimap: boolean;
}

const DEFAULTS: Preferences = {
  theme: 'vs-light',
  navPosition: 'side',
  watermark: '',
  showMinimap: false,
};

function load(): Preferences {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...DEFAULTS };
    // 只认识的键才接受：旧版本写进去的、或者手改坏的键不该污染当前结构
    const saved = JSON.parse(raw) as Partial<Preferences>;
    return {
      theme: saved.theme ?? DEFAULTS.theme,
      navPosition: saved.navPosition ?? DEFAULTS.navPosition,
      watermark:
        typeof saved.watermark === 'string'
          ? saved.watermark
          : DEFAULTS.watermark,
      showMinimap: !!saved.showMinimap,
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

export function resetPreferences() {
  Object.assign(preferences, DEFAULTS);
}

/** 被仓建设嵌入时只还原血缘图相关项。 */
export function resetMapPreferences() {
  preferences.watermark = DEFAULTS.watermark;
  preferences.showMinimap = DEFAULTS.showMinimap;
}
