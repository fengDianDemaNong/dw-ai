import { createApp } from 'vue';
import App from './App.vue';
import router from './router';
import { appConfig, loadAppConfig } from './config/appConfig';
import { configureApiBase } from './config/api';
import { listenEmbedHost } from './config/embed';
import { consumeBootHash, isStandardMode, loadRuntime } from './config/runtime';
import { requiresLocalLogin } from './config/pages';
import { refreshDefaultCatalog } from './stores/catalog';
import { hasSession, loadMe } from './stores/auth';
import { applyOrgContext, pinDefaultTenant, setNames, verifyContext } from './stores/tenant';
import './index.css';
import './styles/page.css';

// 按需导入 ant-design-vue 组件
import {
  Button,
  Tooltip,
  Popover,
  Form,
  Input,
  InputNumber,
  Select,
  Switch,
  Table,
  Modal,
  Tag,
  Alert,
  Popconfirm,
  Tour,
} from 'ant-design-vue';

async function start() {
  consumeBootHash();
  const bootTenant = sessionStorage.getItem('sql-tools.bootTenant') ?? '';
  const bootProject = sessionStorage.getItem('sql-tools.bootProject') ?? '';
  if (bootTenant || bootProject) applyOrgContext(bootTenant, bootProject);
  const bootTenantName = sessionStorage.getItem('sql-tools.bootTenantName') ?? '';
  const bootProjectName = sessionStorage.getItem('sql-tools.bootProjectName') ?? '';
  if (bootTenantName || bootProjectName) setNames(bootTenantName, bootProjectName);

  // 必须排在 loadRuntime() 之前：runtime 自己就是一次 /api/runtime 请求，
  // 用的正是这里注入的基址。见 config/appConfig.ts 的优先级说明。
  await loadAppConfig();
  configureApiBase(appConfig().apiBaseUrl);

  await loadRuntime();
  // standard 固定单租户：把本地可能残留的租户选择钉回默认值，
  // 否则请求会打到上次选过的租户上。
  if (isStandardMode()) pinDefaultTenant();

  // standard 模式：先把身份拉回来再挂载。
  //
  // 必须 await —— 侧边栏与顶部菜单要按「是不是管理员」决定渲不渲染「账号管理」。
  // 菜单本身是 computed（`visibleNavGroups(route.path)`），晚到会自己补上，
  // 但首帧会先渲染出一个少一项的菜单再跳一下；更要紧的是路由守卫要看 `me.admin`
  // 决定放不放行，那个判断发生在这里之后的首次导航，不能等。
  //
  // 拉不到不阻断启动：可能是令牌已失效或后端没起来。这时会话已被清掉，
  // 路由守卫会把首屏直接送到登录页 —— 那正是该发生的事。
  if (requiresLocalLogin() && hasSession()) {
    await loadMe().catch(() => undefined);
  }

  const app = createApp(App);

  app.use(router);
  listenEmbedHost(router);

  // 注册需要的组件
  app.use(Button);
  app.use(Tooltip);
  app.use(Popover);
  app.use(Form);
  app.use(Input);
  app.use(InputNumber);
  app.use(Select);
  app.use(Switch);
  app.use(Table);
  app.use(Modal);
  app.use(Tag);
  app.use(Alert);
  app.use(Popconfirm);
  app.use(Tour);

  app.mount('#root');

  // 下面两个都是「该有身份时才发」的后台请求。
  //
  // 为什么 standard 未登录时要跳过：它们必然 401，而 401 拦截器会去换令牌、
  // 换不到就整页重载让路由守卫把人送到登录页。那会和守卫自己的重定向抢着跳 ——
  // 守卫刚把地址改成登录页、重载也同时发生，就可能来回刷。
  // 少发这两个请求，登录流程就只剩守卫一条路径，简单且不会打转。
  const authenticated = !requiresLocalLogin() || hasSession();
  if (!authenticated) return;

  // 拉默认数据目录名，页面上靠它把表名里的默认目录前缀隐去。
  // 不 await：这只影响表名多不多一截前缀，没必要为它推迟首屏；
  // 拿到之后 reactive 会把已经渲染的名字一起更新。
  void refreshDefaultCatalog();

  // 校验本地存的租户/项目在后端还在不在。不 await：不该为它推迟首屏，
  // 真发现对不上时它自己会切回默认租户并重载。
  //
  // 必须在这里跑，不能放回切换器组件 —— 切换器已经收进「设置 › 基本信息」，
  // 放组件里就只有访问那一页才自检，其余时间对着一个失效租户看空页面。
  void verifyContext();
}

void start();
