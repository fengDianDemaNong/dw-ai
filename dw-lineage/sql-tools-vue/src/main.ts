import { createApp } from 'vue';
import App from './App.vue';
import router from './router';
import { listenEmbedHost } from './config/embed';
import { consumeBootHash, loadRuntime } from './config/runtime';
import { refreshDefaultCatalog } from './stores/catalog';
import { applyOrgContext, ensureOrgProject, setNames, verifyContext } from './stores/tenant';
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
  await loadRuntime();
  await ensureOrgProject();

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
