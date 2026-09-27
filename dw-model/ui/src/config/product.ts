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
  return orgOriginExplicit() ?? 'http://127.0.0.1:5171';
}

/**
 * 与 {@link orgOrigin} 同源，但**不含兜底值**：只有壳自报或后端配过才返回。
 *
 * <p>给「直连组织平台后端」的调用方用（见 `api/client.ts` 的 `orgApiBase`）。那里不能用
 * 兜底值 —— `127.0.0.1:5171` 只在单机开发态是对的，跨服务器部署时拿它发请求等于把用户的
 * 数据打到**他自己这台机器**上，静默且后果不明。取不到时宁可让调用方留在本进程
 * （拿一个能查到的 404），也不要发出去。
 */
export function orgOriginExplicit(): string | undefined {
  const fromBoot = storedHostOrigin();
  if (fromBoot) return fromBoot.replace(/\/$/, '');
  const fromApi = typeof window === 'undefined' ? null : sessionStorage.getItem(ORG_UI_KEY);
  return fromApi ? fromApi.replace(/\/$/, '') : undefined;
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

/** 当前页面的地址（本进程内，不含 hash）—— 交给组织平台作为登录回跳的目标。 */
export function currentLocation(): string {
  return `${window.location.pathname}${window.location.search}`;
}

/**
 * 回组织平台登录。
 *
 * <p>`returnTo` 是本进程内的一个地址（如 `/model/spec/layers`）。传了它，组织平台会在
 * 登录（以及必要的选租户）之后把身份与这个地址一起送回来，用户落回他本来要去的那一页 ——
 * 而不是被丢在门户首页。整页跳转带不过去任何内存状态，所以只能走 query。
 *
 * <p>不传 = 只是「去登录」，落点由组织平台决定（用户主动点某个需要登录的入口时用）。
 * 路由守卫拦下未登录的导航时会传 `to.fullPath`：那正是他要去而没去成的地方。
 *
 * <p>传过去的是**产品码 + 路径**，不是本进程的绝对地址：组织平台拿产品码去服务注册表里
 * 查出该往哪个地址跳。这样用户用 localhost / 127.0.0.1 / 局域网 IP / 域名哪一种进来，
 * 都跳得回来 —— 若传绝对地址，组织侧就得拿它跟注册表里那**一个**写法逐字比对，
 * 换个写法访问同一个服务就会失配，且失配是**静默回落门户首页**（看不出错）。
 *
 * <p>产品码写死 `warehouse` 而不是用 `getUiProduct()`：那个函数的取值是**这个前端
 * 跑成哪种 UI 变体**（`org` / `warehouse` / `suite`），而服务注册表登记的是**发布身份**，
 * 两者不是一回事 —— suite 变体将来也可能跑在 `warehouse` 这个服务名下。
 *
 * <p>不以单个 `/` 开头的值直接丢弃：那种串拼出来不是本进程的地址
 * （`//evil.com` 更是协议相对 URL），宁可不带参数。
 */
export function openOrgLogin(returnTo?: string): void {
  const path = returnTo && returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : '';
  const back = path
    ? `?returnSvc=warehouse&returnPath=${encodeURIComponent(path)}`
    : '';
  window.location.href = `${orgOrigin()}/org/login${back}`;
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
