import type { Router } from 'vue-router';
import { message } from 'ant-design-vue';
import type { ProductService } from '../api/client';
import { APP_HOME } from './paths';

export type UiProduct = 'org' | 'warehouse' | 'suite';

export function getUiProduct(): UiProduct {
  return 'org';
}

export function isOrgUi(): boolean {
  return getUiProduct() === 'org';
}

export function isWarehouseUi(): boolean {
  return getUiProduct() === 'warehouse';
}

/**
 * 别人（宿主壳）自己的 origin，随 `#boot=` 自报。
 *
 * <p>整页跳转时，被打开方除了 referrer 之外没有任何线索知道「谁把我打开的」，
 * 而 referrer 在跨站策略下可能被剥掉。以前靠部署时给被嵌方配对
 * `VITE_ORG_ORIGIN`，漏配的表现是<b>静默</b>的：页面画得出来，只是推过去的
 * 续期 token 被对方的消息白名单挡掉，令牌过期后整片 401。
 */
function selfOrigin(): string {
  return typeof window === 'undefined' ? '' : window.location.origin;
}

/** 上游壳在 `#boot=` 里自报的 origin（见 {@link consumeBootHash}）。 */
const HOST_ORIGIN_KEY = 'dw-ai.hostOrigin';

export function storedHostOrigin(): string | undefined {
  if (typeof window === 'undefined') return undefined;
  return sessionStorage.getItem(HOST_ORIGIN_KEY) || undefined;
}

/**
 * 产品码 → 它的站点根。
 *
 * <p>由 `stores/app.ts` 在加载完服务目录后注入。方向是 store → config：反过来会
 * 成环，因为 store 已经 import 了本模块的 `isOrgUi`。
 *
 * <p>为什么不继续用环境变量：那是构建期的，每加一个部署就要重新构建一次前端；
 * 而且漏配时**静默**跳到 `127.0.0.1:517x` —— 跨服务器部署时那正是用户自己的机器。
 * 现在地址只有一个来源：平台管理员在「服务注册」里填的那份。
 */
let productOriginResolver: (product: string) => string = () => '';
let productServicesSnapshot: () => ProductService[] = () => [];

export function configureProductOrigins(
  resolver: (product: string) => string,
  snapshot: () => ProductService[]
): void {
  productOriginResolver = resolver;
  productServicesSnapshot = snapshot;
}

/** 取产品站点根；没配就报错并返回空串，调用方据此中止跳转。 */
function requireProductOrigin(product: string, label: string): string {
  const url = productOriginResolver(product);
  if (url) return url;
  // 文案与 config/sysNav.ts 的 disabledReason 一致，免得同一件事有两种说法
  message.error(`未配置${label}前端地址（见「服务注册」）`);
  return '';
}

/**
 * 「我在这项目下各产品的角色」，由 `stores/app.ts` 的 enterProject 写好。
 *
 * <p>下游服务只拿它把菜单先画对 —— 真正的门禁在各服务后端，它们各自调组织平台的
 * `authz/check` 兜底。所以这一份即便被改，后果也只是看到一个点不进去的入口。
 */
function bootRoles(): unknown {
  try {
    return JSON.parse(sessionStorage.getItem('dw-ai.roles') ?? '{}');
  } catch {
    return {};
  }
}

function bootPayload(): string {
  const token = sessionStorage.getItem('dw-ai.token') ?? '';
  const refreshToken = sessionStorage.getItem('dw-ai.refreshToken') ?? '';
  const tokenExp = sessionStorage.getItem('dw-ai.tokenExp') ?? '';
  const tenant = sessionStorage.getItem('dw-ai.tenantId') ?? '';
  const project = sessionStorage.getItem('dw-ai.projectId') ?? '';
  const tenantCode = sessionStorage.getItem('dw-ai.tenantCode') ?? '';
  const projectCode = sessionStorage.getItem('dw-ai.projectCode') ?? '';
  const userId = sessionStorage.getItem('dw-ai.userId') ?? '';
  const roles = bootRoles();
  // hostOrigin 必须带上：整页跳转（openWarehouseApp / openLineageApp）走的正是这条路，
  // 而被打开方要靠它把自己加进消息来源白名单。以前只有 iframe 通道
  // （productEmbedUrl）带，于是「进入项目」这种整页跳转过去后，壳推的续期 token
  // 会被对方白名单静默丢弃 —— 要等令牌过期才以「整片 401」的形式暴露。
  return encodeURIComponent(
    JSON.stringify({
      token,
      refreshToken,
      tokenExp,
      tenant,
      project,
      tenantCode,
      projectCode,
      userId,
      roles,
      hostOrigin: selfOrigin(),
      // 下游嵌数据地图时要用（它自己没有服务注册表），见 EmbedBoot.services
      services: productServicesSnapshot(),
    })
  );
}

export type EmbedBoot = {
  token?: string;
  refreshToken?: string;
  tokenExp?: string;
  userId?: string;
  tenant?: string;
  project?: string;
  tenantCode?: string;
  projectCode?: string;
  tenantName?: string;
  projectName?: string;
  /** 「我在本项目各产品下的角色」，形状是 `{ warehouse: 'admin', metadata: 'viewer' }`。 */
  roles?: unknown;
  /**
   * 本壳（门户）自己的 origin，由子应用加进它的消息来源白名单。
   *
   * <p>子应用（数据地图）的白名单靠 `document.referrer` 与「运行期推导出的组织平台
   * 地址」兜底（见 `dw-lineage/ui/src/config/embed.ts` 的 `allowed`）。referrer 在跨站
   * 策略下可能被剥掉，运行期推导又要求后端配了 `org-base-url` —— 两种失效都<b>不报错</b>，
   * 只是宿主推的续期 token 被丢弃，页面在 token 过期后整片 401。
   * 由壳在启动参数里自报 origin，就不必让每个被嵌方去猜门户地址。
   */
  hostOrigin?: string;
  /**
   * 各产品的前端地址，随身份一起带给下游。
   *
   * <p>下游（仓建设）要嵌数据地图时得知道它在哪，而「服务注册」这份数据只有组织有。
   * 让它随 `#boot=` 走，比要求每个下游再配一遍环境变量可靠 —— 那种配法的漏配是
   * 静默的，且每加一个部署都要重新构建一次前端。
   */
  services?: ProductService[];
  embed?: boolean;
};

function sessionBoot(): EmbedBoot {
  return {
    token: sessionStorage.getItem('dw-ai.token') ?? '',
    refreshToken: sessionStorage.getItem('dw-ai.refreshToken') ?? '',
    tokenExp: sessionStorage.getItem('dw-ai.tokenExp') ?? '',
    userId: sessionStorage.getItem('dw-ai.userId') ?? '',
    tenant: sessionStorage.getItem('dw-ai.tenantId') ?? '',
    project: sessionStorage.getItem('dw-ai.projectId') ?? '',
    tenantCode: sessionStorage.getItem('dw-ai.tenantCode') ?? '',
    projectCode: sessionStorage.getItem('dw-ai.projectCode') ?? '',
    roles: bootRoles(),
  };
}

/** 产品码 → 它的站点根。菜单项自带地址时优先用它，否则问服务目录。 */
function productOrigin(product: string, frontendUrl?: string): string {
  const configured = (frontendUrl ?? '').trim().replace(/\/$/, '');
  if (configured) return configured;
  // 菜单与目录是两次读取，菜单项没带地址时再问一次目录（走的是同一份注册表）
  return productOriginResolver(product);
}

/**
 * 拼一个被嵌入产品的 iframe 地址（门户集成的主通道）。
 *
 * <p>形如 `{frontendUrl}{子应用路径}?embed=1#boot={...}`。`#boot=` <b>必须保留</b>：
 * 它是 iframe 首次加载拿到登录态的唯一途径 —— postMessage 那条通道只管<b>续期</b>
 * （见 `components/ProductEmbed.vue`）。删掉它，用户会在嵌进来的页面里被要求二次登录。
 *
 * <p>返回的是<b>整页 URL</b>，直接交给 iframe 的 `src`；`ProductEmbed` 只从里面
 * 解析出 origin 与路径，不解释 boot 内容。所以构造必须留在这里，
 * <b>不要挪进组件</b>：设计守卫会检查组件源码里不出现 token 字段。
 */
export function productEmbedUrl(
  product: string,
  path: string,
  frontendUrl?: string,
  boot?: EmbedBoot
): string {
  const p = path.startsWith('/') ? path : `/${path}`;
  const q = new URLSearchParams({ embed: '1' });
  const payload: EmbedBoot = {
    ...sessionBoot(),
    ...boot,
    embed: true,
    hostOrigin: typeof window === 'undefined' ? '' : window.location.origin,
  };
  return `${productOrigin(product, frontendUrl)}${p}?${q}#boot=${encodeURIComponent(JSON.stringify(payload))}`;
}

export function openWarehouseApp(path = '/model'): void {
  const origin = requireProductOrigin('warehouse', '数仓建模');
  if (!origin) return;
  const suffix = path.startsWith('/') ? path : `/${path}`;
  window.location.href = `${origin}${suffix}#boot=${bootPayload()}`;
}

export function openLineageApp(path = '/lineage'): void {
  const origin = requireProductOrigin('metadata', '数据地图');
  if (!origin) return;
  const suffix = path.startsWith('/') ? path : `/${path}`;
  window.location.href = `${origin}${suffix}#boot=${bootPayload()}`;
}

/** 组织进程里的仓建设 / 数据地图首页走外链，不在本 UI 渲染。 */
export function followHome(path: string, router: Router): void {
  if (path === APP_HOME || path.startsWith('/model')) {
    openWarehouseApp(path.startsWith('/model') ? path : '/model');
    return;
  }
  if (path.startsWith('/lineage')) {
    openLineageApp(path);
    return;
  }
  void router.push(path);
}

export function consumeBootHash(): void {
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
      hostOrigin?: string;
    };
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
