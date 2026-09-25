import { createApp } from 'vue';
import Antd from 'ant-design-vue';
import App from './App.vue';
import router from './router';
import { appConfig, loadAppConfig } from './config/appConfig';
import { configureApiBase } from './api/client';
import { consumeBootHash } from './config/product';
import { bootstrapRemote } from './stores/app';
import './index.css';

async function start() {
  consumeBootHash();
  // 运行时配置必须在任何请求之前读到：后端地址此前是构建时烘焙进产物的常量，
  // 现在改由 /config.json 注入（见 config/appConfig.ts 的优先级说明）。
  // 晚一步就会有一批请求带着旧基址发出去。
  await loadAppConfig();
  configureApiBase(appConfig().apiBaseUrl);
  await bootstrapRemote();
  const app = createApp(App);
  app.use(router);
  app.use(Antd);
  app.mount('#app');
}

void start();
