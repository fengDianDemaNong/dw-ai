export type UiProduct = 'org' | 'warehouse' | 'suite';

export function getUiProduct(): UiProduct {
  return 'warehouse';
}

export function isOrgUi(): boolean {
  return getUiProduct() === 'org';
}

export function isWarehouseUi(): boolean {
  return getUiProduct() === 'warehouse';
}

/**
 * 后端 `GET /api/auth/config` 告知的组织平台**前端**地址，见 {@link orgOrigin} 的第 2 级。
 *
 * <p>存的是 `orgUiUrl` 而不是 `orgBaseUrl`：后者是组织后端的 API 基址，
 * 拿它拼 `/org/login` 会跳到接口服务上的 404（开发态 UI 5171 / API 18080，本就不是一个）。
 */
export const ORG_UI_KEY = 'dw-ai.orgUiUrl';
const HOST_ORIGIN_KEY = 'dw-ai.hostOrigin';
const EMBED_KEY = 'dw-ai.embed';

/** 上游壳在 `#boot=` 里自报的 origin（见 {@link consumeBootHash}）。 */
export function storedHostOrigin(): string | undefined {
  if (typeof window === 'undefined') return undefined;
  return sessionStorage.getItem(HOST_ORIGIN_KEY) || undefined;
}

/**
 * 本页是不是被壳 iframe 嵌着。
 *
 * <p>两个来源：URL 上的 `?embed=1`（壳拼地址时带的），以及 {@link consumeBootHash}
 * 存下来的那份。存下来是必须的 —— 子应用内部一跳转 query 就没了，而这个判断
 * 要在整个会话里都答得出来（`config/embed.ts` 靠它决定发不发 `EMBED_READY`，
 * `api/client.ts` 靠它决定 401 之后是问宿主还是回登录页）。
 */
export function isEmbed(): boolean {
  if (typeof window === 'undefined') return false;
  return (
    sessionStorage.getItem(EMBED_KEY) === '1' ||
    new URLSearchParams(window.location.search).get('embed') === '1'
  );
}

/**
 * 组织平台地址。三级回落，从最可靠到最兜底：
 *
 * <ol>
 *   <li><b>壳在 `#boot=` 里自报的 origin</b>（{@link storedHostOrigin}）—— 整页跳转与
 *       iframe 两条路都带，跨服务器部署时唯一正确的来源；</li>
 *   <li><b>后端告知的 `orgUiUrl`</b>（`GET /api/auth/config`，即配置项 `dwai.org-ui-url`）——
 *       覆盖「地址栏直接打开本页、没有 boot」的情形；</li>
 *   <li><b>内置默认</b> `127.0.0.1:5171` —— 只在单机开发态是对的。</li>
 * </ol>
 *
 * <p>以前这里只读构建期的 `VITE_ORG_ORIGIN`：每加一个部署就要重新构建前端，
 * 漏配的表现是<b>静默</b>指向 `127.0.0.1:5171` —— 跨服务器部署时那正是用户自己的机器。
 */
export function orgOrigin(): string {
  const fromBoot = storedHostOrigin();
  if (fromBoot) return fromBoot.replace(/\/$/, '');
  const fromApi = typeof window === 'undefined' ? null : sessionStorage.getItem(ORG_UI_KEY);
  if (fromApi) return fromApi.replace(/\/$/, '');
  return 'http://127.0.0.1:5171';
}

/**
 * 这里原先还有一组「把本进程当壳、往下游产品转发上下文」的函数
 * （`lineageOrigin` / `originOf` / `EmbedBoot` / `sessionBoot` / `bootPayload` /
 * `lineageEmbedUrl` / `bootServices`），随数据地图那一组菜单一并删除。
 *
 * <p><b>本进程只当子应用，不当壳。</b>要嵌谁、嵌到哪一层，由组织平台的项目壳决定
 * （它从各服务拉菜单、按 `scope` 摆位置），model 只负责把自己的页面画好 ——
 * 转发 `#boot=` 这套只在「我们嵌别人」时才需要。收的那一半（{@link consumeBootHash}）
 * 留着，我们仍然被壳嵌着。
 */

export function openOrgLogin(): void {
  window.location.href = `${orgOrigin()}/org/login`;
}

/**
 * 回组织平台的工作台。
 *
 * <p>multi 下本进程没有工作台（见 `config/pages.ts` 的 `hasWorkbench`）——项目在组织那边建、
 * 进项目也在组织那边完成，所以「返回工作台」对 multi 是一次<b>跨进程跳转</b>，不能写成
 * `router.push(SYS_HOME)`：`SYS_HOME` 是仓建设自己的工作台路径，multi 下会被路由守卫
 * 直接弹回去，点了等于没点（这正是它此前在 multi 下不可见的另一半原因）。
 *
 * <p>落点选 `workbench/projects` 而不是 `/org`：组织平台对普通成员只放行工作台的
 * 「项目管理」页，其余页会把他弹回上一级，指到那里会白跳一次。
 */
export function openOrgWorkbench(): void {
  window.location.href = `${orgOrigin()}/org/workbench/projects`;
}

export function consumeBootHash(): void {
  // 嵌进来的标记先记下：URL 上的 `?embed=1` 在子应用内部跳转后就没了，
  // 而 `isEmbed()` 整个会话都要答得出来（见该函数的说明）。
  if (new URLSearchParams(window.location.search).get('embed') === '1') {
    sessionStorage.setItem(EMBED_KEY, '1');
  }
  const raw = window.location.hash.startsWith('#boot=') ? window.location.hash.slice(6) : '';
  if (!raw) return;
  try {
    const boot = JSON.parse(decodeURIComponent(raw)) as {
      token?: string;
      refreshToken?: string;
      tokenExp?: string;
      tenant?: string;
      project?: string;
      tenantCode?: string;
      projectCode?: string;
      userId?: string;
      roles?: unknown;
      hostOrigin?: string;
    };
    // 壳给的「我在本项目各产品的角色」。存下来供侧栏按角色收口。
    if (boot.roles) sessionStorage.setItem('dw-ai.roles', JSON.stringify(boot.roles));
    if (boot.token) sessionStorage.setItem('dw-ai.token', boot.token);
    if (boot.refreshToken) sessionStorage.setItem('dw-ai.refreshToken', boot.refreshToken);
    if (boot.tokenExp) sessionStorage.setItem('dw-ai.tokenExp', boot.tokenExp);
    if (boot.tenant) sessionStorage.setItem('dw-ai.tenantId', boot.tenant);
    if (boot.project) sessionStorage.setItem('dw-ai.projectId', boot.project);
    if (boot.tenantCode) sessionStorage.setItem('dw-ai.tenantCode', boot.tenantCode);
    if (boot.projectCode) sessionStorage.setItem('dw-ai.projectCode', boot.projectCode);
    if (boot.userId) sessionStorage.setItem('dw-ai.userId', boot.userId);
    if (boot.hostOrigin) sessionStorage.setItem(HOST_ORIGIN_KEY, boot.hostOrigin);
  } catch {
    /* 忽略损坏的启动参数 */
  }
  history.replaceState(null, '', window.location.pathname + window.location.search);
}
