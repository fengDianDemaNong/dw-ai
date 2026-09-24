import { reactive } from 'vue';

const COLLAPSED_KEY = 'nav.collapsed';

export const SIDEBAR_WIDTH = 200;
export const SIDEBAR_WIDTH_COLLAPSED = 56;

export const uiState = reactive({
  /** 侧边栏是否收起 */
  collapsed: localStorage.getItem(COLLAPSED_KEY) === '1',
  /**
   * 内容区实际宽度。
   *
   * G6 画布要显式给宽高，不能靠 CSS 撑。以前页面直接拿
   * `document.documentElement.clientWidth`，那时候导航在顶部、内容区就是整屏宽；
   * 现在左边多了侧边栏，再用整屏宽画布会横向溢出。由 AppLayout 量好写在这里。
   */
  contentWidth: document.documentElement.clientWidth,
  contentHeight: document.documentElement.clientHeight,
  /**
   * 面包屑末尾那一段，由详情页自己填（比如表名）。
   *
   * 路由 meta 只知道「数据表」，不知道当前看的是哪张表；这一段要等数据回来
   * 才知道，所以只能由页面写进来。路由一变就清空，见 AppLayout。
   */
  crumb: '' as string,
});

/**
 * 折叠状态<b>只由用户控制</b>。
 *
 * 曾经让 SQL 解析页进去自动收起「给画布让宽」，实际体验是每次点解析页菜单栏
 * 就自己跑掉，而用户并没有点折叠按钮 —— 界面自作主张比省那 144px 更烦人。
 */
export function toggleSidebar() {
  uiState.collapsed = !uiState.collapsed;
  localStorage.setItem(COLLAPSED_KEY, uiState.collapsed ? '1' : '0');
}
